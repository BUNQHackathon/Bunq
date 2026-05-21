package com.bunq.javabackend.service.chat;

import com.bunq.javabackend.config.ChatConfig;
import com.bunq.javabackend.config.KnowledgeBaseConfig;
import com.bunq.javabackend.dto.request.ChatRequestDTO;
import com.bunq.javabackend.dto.response.ChatHistoryResponseDTO;
import com.bunq.javabackend.dto.response.ChatMessageResponseDTO;
import com.bunq.javabackend.dto.response.ChatSummaryResponseDTO;
import com.bunq.javabackend.dto.response.CitationDTO;
import com.bunq.javabackend.dto.response.KnowledgeBaseOptionDTO;
import com.bunq.javabackend.dto.response.TokenUsageDTO;
import com.bunq.javabackend.dto.response.events.ChatCitationsEvent;
import com.bunq.javabackend.dto.response.events.ChatCompletedEvent;
import com.bunq.javabackend.dto.response.events.ChatDeltaEvent;
import com.bunq.javabackend.dto.response.events.ChatFailedEvent;
import com.bunq.javabackend.dto.response.events.ChatStartedEvent;
import com.bunq.javabackend.helper.mapper.ChatMessageMapper;
import com.bunq.javabackend.model.chat.ChatMessage;
import com.bunq.javabackend.model.chat.Citation;
import com.bunq.javabackend.model.chat.TokenUsage;
import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.repository.ChatMessageRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.ai.bedrock.BedrockStreamingService;
import com.bunq.javabackend.service.ai.kb.KnowledgeBaseService;
import com.bunq.javabackend.service.ai.kb.KnowledgeBaseService.RetrievedChunk;
import com.bunq.javabackend.util.JurisdictionInference;
import com.bunq.javabackend.service.documents.DocumentService;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChatService {

  private static final long CHAT_SSE_TIMEOUT_MS = 300_000L;
  private static final Pattern DOCUMENT_ID_PATTERN = Pattern.compile("/documents/([a-fA-F0-9]{64})(?:\\.[^/#?]+)?");

  private static final String QA_EXAMPLES = """
      1. Question: What are our AML obligations in Germany?
        Answer: Under the German Money Laundering Act (GwG) enforced by BaFin, your primary obligations include conducting comprehensive Customer Due Diligence (KYC/CDD), implementing an internal risk management system, appointing an AML officer (Geldwäschebeauftragter) if applicable to your business size, and immediately reporting suspicious transactions to Germany's Financial Intelligence Unit (FIU).

      2. Question: Can we offer crypto custody under MiCA?
        Answer: Yes, you can offer crypto custody services within the EU, provided you are authorized as a Crypto-Asset Service Provider (CASP) under the Markets in Crypto-Assets (MiCA) regulation. This requires approval from your national competent authority and strict compliance with MiCA's rules on safeguarding client assets, segregating funds, and maintaining operational resilience. Existing financial institutions (like banks) may follow a streamlined notification process.

      3. Question: GDPR retention rules for transaction data
        Answer: While GDPR enforces the principle of "storage limitation" (keeping data only as long as necessary), intersecting financial regulations dictate the actual timeline. For transaction data, you are generally legally required by the EU's Anti-Money Laundering Directives (AMLD) and national laws to retain records for 5 to 10 years following the execution of the transaction or termination of the business relationship, after which the data must be securely deleted.

      4. Question: EMI passporting requirements for France
        Answer: If you are an authorized Electronic Money Institution (EMI) in another EEA state, you can passport your services to France under the freedom to provide services or right of establishment. You must submit a passporting notification to your home state regulator, who will assess it and forward it to the French regulator (ACPR). If operating through a local branch or network of agents in France, you must also adhere to French host-state rules, including local consumer protection and AML reporting guidelines.

      5. Question: Which third-party provider does bunq use to detect deepfakes during the digital identity verification process, and where is its headquarters?
        Answer: For targeted deepfake detection during the KYC onboarding process, bunq uses DuckDuckGoose AI, which is headquartered in Delft, Netherlands. Additionally, they partner with Incode Technologies, headquartered in San Francisco, California, for broader identity verification and AI-driven liveness checks.

      6. Question: At what age does a minor child get full access to their bunq bank account?
        Answer: A minor gains full, independent access to their bunq account at age 18. About a month prior to their 18th birthday, they are prompted to accept the Terms & Conditions themselves. Once they turn 18, the link giving parents or legal guardians oversight is automatically removed.

      7. Question: What is the email address of bunq's Data Protection Officer (DPO)?
        Answer: You can reach bunq's Data Protection Officer regarding the processing of personal data or privacy concerns by emailing privacy@bunq.com.
      """;

  private static final String SYSTEM_PROMPT = """
      You are Prism, a compliance Q&A assistant. Answer using only
      the <context> blocks provided. Each context block is tagged with
      its source knowledge base (regulations | policies | controls).
      When citing, use [kbType:chunkId] format — e.g. [regulations:c1].
      If the user asks a question of type "What do you have access to" –
      answer that you have acess to "Regulations", "Bunq Policies", "Internal controls".
      If user asks to list the files or summarise all of them, politely
      redirect the question to be more specific (e.g. ask about certain file
      or policy).
      """
      + QA_EXAMPLES +
      """
          If the answer is not in the context, say "I don't have enough
          information to answer that" — do not speculate.
          """;

  private final KnowledgeBaseService knowledgeBaseService;
  private final BedrockService bedrockService;
  private final BedrockStreamingService bedrockStreamingService;
  private final ChatMessageRepository chatMessageRepository;
  private final DocumentRepository documentRepository;
  private final DocumentService documentService;
  private final SessionRepository sessionRepository;
  private final SseEmitterService sseEmitterService;
  private final ChatConfig chatConfig;
  private final KnowledgeBaseConfig knowledgeBaseConfig;
  private final ObjectMapper objectMapper;
  private final Executor pipelineExecutor;

  public ChatService(
      KnowledgeBaseService knowledgeBaseService,
      BedrockService bedrockService,
      BedrockStreamingService bedrockStreamingService,
      ChatMessageRepository chatMessageRepository,
      DocumentRepository documentRepository,
      DocumentService documentService,
      SessionRepository sessionRepository,
      SseEmitterService sseEmitterService,
      ChatConfig chatConfig,
      KnowledgeBaseConfig knowledgeBaseConfig,
      ObjectMapper objectMapper,
      @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
    this.knowledgeBaseService = knowledgeBaseService;
    this.bedrockService = bedrockService;
    this.bedrockStreamingService = bedrockStreamingService;
    this.chatMessageRepository = chatMessageRepository;
    this.documentRepository = documentRepository;
    this.documentService = documentService;
    this.sessionRepository = sessionRepository;
    this.sseEmitterService = sseEmitterService;
    this.chatConfig = chatConfig;
    this.knowledgeBaseConfig = knowledgeBaseConfig;
    this.objectMapper = objectMapper;
    this.pipelineExecutor = pipelineExecutor;
  }

  public List<KnowledgeBaseOptionDTO> listKnowledgeBases() {
    List<KnowledgeBaseOptionDTO> options = new java.util.ArrayList<>();
    options.add(KnowledgeBaseOptionDTO.builder()
        .key("all")
        .label("All sources")
        .knowledgeBaseId(null)
        .kbType(null)
        .defaultOption(true)
        .build());
    knowledgeBaseConfig.getConfiguredEntries().stream()
        .map(this::toKnowledgeBaseOption)
        .forEach(options::add);
    return options;
  }

  public List<ChatSummaryResponseDTO> listChats(int limit) {
    Map<String, List<ChatMessage>> byChat = new LinkedHashMap<>();
    for (ChatMessage m : chatMessageRepository.findAll()) {
      if (m.getChatId() == null)
        continue;
      byChat.computeIfAbsent(m.getChatId(), k -> new java.util.ArrayList<>()).add(m);
    }

    return byChat.entrySet().stream()
        .map(entry -> {
          List<ChatMessage> msgs = entry.getValue();
          msgs.sort(Comparator.comparing(ChatMessage::getTimestamp));
          ChatMessage first = msgs.get(0);
          ChatMessage last = msgs.get(msgs.size() - 1);
          String title = msgs.stream()
              .filter(m -> "USER".equalsIgnoreCase(m.getRole()))
              .map(ChatMessage::getContent)
              .filter(c -> c != null && !c.isBlank())
              .findFirst()
              .map(c -> c.length() > 120 ? c.substring(0, 120) : c)
              .orElse("Untitled chat");
          return ChatSummaryResponseDTO.builder()
              .chatId(entry.getKey())
              .sessionId(first.getSessionId())
              .title(title)
              .createdAt(first.getTimestamp())
              .updatedAt(last.getTimestamp())
              .messageCount(msgs.size())
              .build();
        })
        .sorted(Comparator.comparing(ChatSummaryResponseDTO::getUpdatedAt).reversed())
        .limit(limit)
        .toList();
  }

  public ChatHistoryResponseDTO getHistory(String chatId) {
    List<ChatMessageResponseDTO> messages = chatMessageRepository.findByChatId(chatId, chatConfig.getHistoryLimit())
        .stream()
        .map(ChatMessageMapper::toDto)
        .toList();

    return ChatHistoryResponseDTO.builder()
        .chatId(chatId)
        .messages(messages)
        .build();
  }

  public SseEmitter startChat(ChatRequestDTO req) {
    KnowledgeBaseConfig.Entry selectedKnowledgeBase = resolveSelectedKnowledgeBase(req.getKnowledgeBaseId());

    String chatId = req.getChatId() != null && !req.getChatId().isBlank()
        ? req.getChatId()
        : UUID.randomUUID().toString();

    SseEmitter emitter = sseEmitterService.register(chatId, CHAT_SSE_TIMEOUT_MS);

    ChatMessage userMessage = ChatMessage.builder()
        .id(UUID.randomUUID().toString())
        .chatId(chatId)
        .sessionId(req.getSessionId())
        .role("USER")
        .content(req.getQuery())
        .timestamp(Instant.now())
        .build();
    chatMessageRepository.save(userMessage);

    pipelineExecutor.execute(() -> runChat(chatId, req, emitter, selectedKnowledgeBase));

    return emitter;
  }

  private KnowledgeBaseConfig.Entry resolveSelectedKnowledgeBase(String knowledgeBaseId) {
    if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
      return null;
    }
    return knowledgeBaseConfig.findByKnowledgeBaseId(knowledgeBaseId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown knowledgeBaseId: " + knowledgeBaseId));
  }

  private void runChat(String chatId, ChatRequestDTO req, SseEmitter emitter,
      KnowledgeBaseConfig.Entry selectedKnowledgeBase) {
    try {
      sseEmitterService.send(chatId, ChatStartedEvent.builder()
          .sessionId(chatId)
          .chatId(chatId)
          .timestamp(Instant.now())
          .build());

      String jurisdictionCode = req.getSessionId() != null
          ? sessionRepository.findById(req.getSessionId())
              .map(s -> s.getJurisdictionCode()).orElse(null)
          : null;
      List<String> jurisdictions = JurisdictionInference.expandForRetrieval(jurisdictionCode);
      List<RetrievedChunk> chunks = selectedKnowledgeBase == null
          ? knowledgeBaseService
              .retrieveAllWithFilter(req.getQuery(), chatConfig.getTopKPerKb(), chatConfig.getTopNMerged(),
                  jurisdictions)
              .join()
          : knowledgeBaseService
              .retrieveKnowledgeBaseWithFilter(selectedKnowledgeBase, req.getQuery(), chatConfig.getTopNMerged(),
                  jurisdictions)
              .join();

      String docText = req.getDocumentId() != null
          ? documentService.getExtractedText(req.getDocumentId()).orElse(null)
          : null;

      List<CitationDTO> citations = chunks.stream().map(this::toCitationDTO).toList();

      sseEmitterService.send(chatId, ChatCitationsEvent.builder()
          .sessionId(chatId)
          .chatId(chatId)
          .timestamp(Instant.now())
          .citations(citations)
          .build());

      String userContent = buildUserContent(chunks, req.getQuery(), docText);

      StringBuilder fullText = new StringBuilder();
      AtomicReference<TokenUsageDTO> usageRef = new AtomicReference<TokenUsageDTO>(null);

      List<Citation> citationModels = chunks.stream().map(this::toCitationModel).toList();
      String sessionId = req.getSessionId();

      Flux<BedrockStreamingService.StreamingDelta> chatStream = bedrockStreamingService
          .streamWithCachedSystem(BedrockModel.SONNET.getModelId(), SYSTEM_PROMPT, userContent)
          .onErrorResume(ex -> {
            if (isModelAccessFailure(ex)) {
              log.warn("Anthropic streaming unavailable for chat {}; falling back to non-streaming Bedrock completion",
                  chatId);
              return fallbackChatCompletion(sessionId, userContent);
            }
            return Flux.error(ex);
          });

      chatStream
          .doOnNext(delta -> {
            if (delta.text() != null) {
              fullText.append(delta.text());
              sseEmitterService.send(chatId, ChatDeltaEvent.builder()
                  .sessionId(chatId)
                  .chatId(chatId)
                  .timestamp(Instant.now())
                  .delta(delta.text())
                  .build());
            }
            if (delta.inputTokens() != null) {
              usageRef.set(TokenUsageDTO.builder()
                  .inputTokens(delta.inputTokens())
                  .outputTokens(delta.outputTokens())
                  .cacheReadTokens(delta.cacheReadTokens())
                  .cacheCreationTokens(delta.cacheCreationTokens())
                  .build());
            }
          })
          .doOnComplete(() -> {
            String assistantId = UUID.randomUUID().toString();
            TokenUsageDTO usage = usageRef.get();
            ChatMessage assistantMessage = ChatMessage.builder()
                .id(assistantId)
                .chatId(chatId)
                .sessionId(sessionId)
                .role("ASSISTANT")
                .content(fullText.toString())
                .citations(citationModels)
                .timestamp(Instant.now())
                .tokenUsage(usage != null ? toTokenUsageModel(usage) : null)
                .build();
            chatMessageRepository.save(assistantMessage);
            sseEmitterService.send(chatId, ChatCompletedEvent.builder()
                .sessionId(chatId)
                .chatId(chatId)
                .timestamp(Instant.now())
                .messageId(assistantId)
                .tokenUsage(usage)
                .build());
            sseEmitterService.complete(chatId);
          })
          .doOnError(ex -> {
            log.warn("Chat {} stream failed: {}", chatId, ex.getMessage(), ex);
            sseEmitterService.send(chatId, ChatFailedEvent.builder()
                .sessionId(chatId)
                .chatId(chatId)
                .timestamp(Instant.now())
                .errorCode(errorCodeFor(ex))
                .message(errorMessageFor(ex))
                .build());
            sseEmitterService.complete(chatId);
          })
          .subscribe();

    } catch (Exception ex) {
      log.warn("Chat {} failed: {}", chatId, ex.getMessage(), ex);
      sseEmitterService.send(chatId, ChatFailedEvent.builder()
          .sessionId(chatId)
          .chatId(chatId)
          .timestamp(Instant.now())
          .errorCode(errorCodeFor(ex))
          .message(errorMessageFor(ex))
          .build());
      sseEmitterService.complete(chatId);
    }
  }

  private String buildUserContent(List<RetrievedChunk> chunks, String query, String docText) {
    StringBuilder sb = new StringBuilder("<context>\n");
    if (docText != null) {
      sb.append("<chunk source=\"document\" id=\"d1\" document=\"uploaded-document\">\n")
          .append(docText).append("\n</chunk>\n");
    }
    int index = 1;
    for (var chunk : chunks) {
      String contextChunkId = "c" + index++;
      String displayName = displayNameFor(chunk);
      sb.append("<chunk source=\"").append(chunk.kbType().name().toLowerCase())
          .append("\" id=\"").append(contextChunkId)
          .append("\" document=\"").append(xmlAttribute(displayName != null ? displayName : chunk.knowledgeBaseLabel()))
          .append("\" score=\"").append(chunk.score()).append("\">\n")
          .append(chunk.text()).append("\n</chunk>\n");
    }
    sb.append("</context>\n\nQuestion: ").append(query);
    // TODO(ChatService:buildUserContent): include last N chat history turns as
    // prior messages
    // for multi-turn conversation. See ChatMessageRepository.findByChatId().
    return sb.toString();
  }

  private static String xmlAttribute(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  private Flux<BedrockStreamingService.StreamingDelta> fallbackChatCompletion(String sessionId, String userContent) {
    return Mono.fromCallable(() -> {
      String requestJson = """
          {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": 4096,
            "system": [
              {
                "type": "text",
                "text": %s
              }
            ],
            "messages": [
              {
                "role": "user",
                "content": %s
              }
            ]
          }
          """.formatted(
          objectMapper.writeValueAsString(SYSTEM_PROMPT),
          objectMapper.writeValueAsString(userContent));
      JsonNode response = bedrockService.invokeModel(sessionId, "chat", BedrockModel.SONNET.getModelId(), requestJson);
      return new BedrockStreamingService.StreamingDelta(extractText(response), null, null, null, null);
    }).flux();
  }

  private static String extractText(JsonNode response) {
    StringBuilder text = new StringBuilder();
    JsonNode content = response.path("content");
    if (content.isArray()) {
      for (JsonNode block : content) {
        String blockText = block.path("text").asText("");
        if (!blockText.isBlank()) {
          text.append(blockText);
        }
      }
    }
    if (text.isEmpty()) {
      throw new IllegalStateException("Bedrock returned no chat text");
    }
    return text.toString();
  }

  private static String errorCodeFor(Throwable ex) {
    return isModelAccessFailure(ex) ? "BEDROCK_MODEL_ACCESS" : rootCause(ex).getClass().getSimpleName();
  }

  private static String errorMessageFor(Throwable ex) {
    if (isModelAccessFailure(ex)) {
      return "Bedrock model access is not enabled for the requested Anthropic model. Enable Anthropic model access/use-case details in AWS Bedrock, or use the Nova fallback.";
    }
    String message = rootCause(ex).getMessage();
    return message != null && !message.isBlank() ? message : "Chat failed";
  }

  private static boolean isModelAccessFailure(Throwable ex) {
    Throwable current = ex;
    int depth = 0;
    while (current != null && depth++ < 10) {
      if (current instanceof ResourceNotFoundException || current instanceof AccessDeniedException) {
        String message = current.getMessage();
        return message == null || message.contains("Model") || message.contains("model")
            || message.contains("Anthropic");
      }
      current = current.getCause();
    }
    return false;
  }

  private static Throwable rootCause(Throwable ex) {
    Throwable current = ex;
    int depth = 0;
    while (current.getCause() != null && depth++ < 10) {
      current = current.getCause();
    }
    return current;
  }

  private KnowledgeBaseOptionDTO toKnowledgeBaseOption(KnowledgeBaseConfig.Entry entry) {
    return KnowledgeBaseOptionDTO.builder()
        .key(entry.getKey())
        .label(entry.getLabel())
        .knowledgeBaseId(entry.getKnowledgeBaseId())
        .kbType(entry.getKbType() != null ? entry.getKbType().name() : null)
        .defaultOption(entry.isDefault())
        .build();
  }

  private CitationDTO toCitationDTO(RetrievedChunk chunk) {
    return CitationDTO.builder()
        .kbType(chunk.kbType().name())
        .knowledgeBaseId(chunk.knowledgeBaseId())
        .knowledgeBaseLabel(chunk.knowledgeBaseLabel())
        .chunkId(chunk.chunkId())
        .score(chunk.score())
        .s3Uri(chunk.s3Uri())
        .displayName(displayNameFor(chunk))
        .documentId(documentIdFor(chunk))
        .sha256(documentIdFor(chunk))
        .sourceText(chunk.text() != null && chunk.text().length() > 500
            ? chunk.text().substring(0, 500)
            : chunk.text())
        .build();
  }

  private Citation toCitationModel(RetrievedChunk chunk) {
    Citation c = new Citation();
    c.setKbType(chunk.kbType().name());
    c.setKnowledgeBaseId(chunk.knowledgeBaseId());
    c.setKnowledgeBaseLabel(chunk.knowledgeBaseLabel());
    c.setChunkId(chunk.chunkId());
    c.setScore(chunk.score());
    c.setS3Uri(chunk.s3Uri());
    c.setDisplayName(displayNameFor(chunk));
    c.setDocumentId(documentIdFor(chunk));
    c.setSha256(documentIdFor(chunk));
    c.setSourceText(chunk.text() != null && chunk.text().length() > 500
        ? chunk.text().substring(0, 500)
        : chunk.text());
    return c;
  }

  private String displayNameFor(RetrievedChunk chunk) {
    String metadataFilename = metadataValue(chunk, "filename");
    String documentId = documentIdFor(chunk);
    if (hasText(documentId)) {
      return documentRepository.findById(documentId)
          .map(document -> hasText(document.getDisplayName()) ? document.getDisplayName() : document.getFilename())
          .filter(ChatService::hasText)
          .orElse(metadataFilename);
    }
    return metadataFilename;
  }

  private static String documentIdFor(RetrievedChunk chunk) {
    String metadataDocumentId = metadataValue(chunk, "document_id");
    if (hasText(metadataDocumentId)) {
      return metadataDocumentId;
    }
    String fromS3Uri = documentIdFrom(chunk.s3Uri());
    if (hasText(fromS3Uri)) {
      return fromS3Uri;
    }
    return documentIdFrom(chunk.chunkId());
  }

  private static String documentIdFrom(String value) {
    if (!hasText(value)) {
      return null;
    }
    Matcher matcher = DOCUMENT_ID_PATTERN.matcher(value);
    return matcher.find() ? matcher.group(1).toLowerCase() : null;
  }

  private static String metadataValue(RetrievedChunk chunk, String key) {
    if (chunk.metadata() == null) {
      return null;
    }
    String value = chunk.metadata().get(key);
    return value != null && !value.isBlank() ? value : null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private TokenUsage toTokenUsageModel(TokenUsageDTO dto) {
    TokenUsage u = new TokenUsage();
    u.setInputTokens(dto.getInputTokens());
    u.setOutputTokens(dto.getOutputTokens());
    u.setCacheReadTokens(dto.getCacheReadTokens());
    u.setCacheCreationTokens(dto.getCacheCreationTokens());
    return u;
  }
}

package com.bunq.javabackend.service.chat;

import com.bunq.javabackend.config.ChatConfig;
import com.bunq.javabackend.dto.request.ChatRequestDTO;
import com.bunq.javabackend.dto.response.ChatHistoryResponseDTO;
import com.bunq.javabackend.dto.response.ChatMessageResponseDTO;
import com.bunq.javabackend.dto.response.ChatSummaryResponseDTO;
import com.bunq.javabackend.dto.response.CitationDTO;
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
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.ai.bedrock.BedrockStreamingService;
import com.bunq.javabackend.service.ai.kb.KnowledgeBaseService;
import com.bunq.javabackend.service.ai.kb.KnowledgeBaseService.RetrievedChunk;
import com.bunq.javabackend.util.JurisdictionInference;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class ChatService {

    private static final long CHAT_SSE_TIMEOUT_MS = 300_000L;

    private static final String SYSTEM_PROMPT = """
            You are LaunchLens, a compliance Q&A assistant. Answer using only
            the <context> blocks provided. Each context block is tagged with
            its source knowledge base (regulations | policies | controls).
            When citing, use [kbType:chunkId] format — e.g. [regulations:c1].
            If the answer is not in the context, say "I don't have enough
            information to answer that" — do not speculate.
            """;

    private final KnowledgeBaseService knowledgeBaseService;
    private final BedrockStreamingService bedrockStreamingService;
    private final ChatMessageRepository chatMessageRepository;
    private final SessionRepository sessionRepository;
    private final SseEmitterService sseEmitterService;
    private final ChatConfig chatConfig;
    private final Executor pipelineExecutor;

    public ChatService(
            KnowledgeBaseService knowledgeBaseService,
            BedrockStreamingService bedrockStreamingService,
            ChatMessageRepository chatMessageRepository,
            SessionRepository sessionRepository,
            SseEmitterService sseEmitterService,
            ChatConfig chatConfig,
            @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.bedrockStreamingService = bedrockStreamingService;
        this.chatMessageRepository = chatMessageRepository;
        this.sessionRepository = sessionRepository;
        this.sseEmitterService = sseEmitterService;
        this.chatConfig = chatConfig;
        this.pipelineExecutor = pipelineExecutor;
    }

    public List<ChatSummaryResponseDTO> listChats(int limit) {
        Map<String, List<ChatMessage>> byChat = new LinkedHashMap<>();
        for (ChatMessage m : chatMessageRepository.findAll()) {
            if (m.getChatId() == null) continue;
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

        pipelineExecutor.execute(() -> runChat(chatId, req, emitter));

        return emitter;
    }

    private void runChat(String chatId, ChatRequestDTO req, SseEmitter emitter) {
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
            List<RetrievedChunk> chunks = knowledgeBaseService
                    .retrieveAllWithFilter(req.getQuery(), chatConfig.getTopKPerKb(), chatConfig.getTopNMerged(), jurisdictions)
                    .join();

            List<CitationDTO> citations = chunks.stream().map(this::toCitationDTO).toList();

            sseEmitterService.send(chatId, ChatCitationsEvent.builder()
                    .sessionId(chatId)
                    .chatId(chatId)
                    .timestamp(Instant.now())
                    .citations(citations)
                    .build());

            String userContent = buildUserContent(chunks, req.getQuery());

            StringBuilder fullText = new StringBuilder();
            AtomicReference<TokenUsageDTO> usageRef = new AtomicReference<TokenUsageDTO>(null);

            bedrockStreamingService
                    .streamWithCachedSystem(BedrockModel.SONNET.getModelId(), SYSTEM_PROMPT, userContent)
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
                    .blockLast();

            String assistantId = UUID.randomUUID().toString();
            TokenUsageDTO usage = usageRef.get();

            ChatMessage assistantMessage = ChatMessage.builder()
                    .id(assistantId)
                    .chatId(chatId)
                    .sessionId(req.getSessionId())
                    .role("ASSISTANT")
                    .content(fullText.toString())
                    .citations(chunks.stream().map(this::toCitationModel).toList())
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

        } catch (Exception ex) {
            log.warn("Chat {} failed: {}", chatId, ex.getMessage(), ex);
            sseEmitterService.send(chatId, ChatFailedEvent.builder()
                    .sessionId(chatId)
                    .chatId(chatId)
                    .timestamp(Instant.now())
                    .errorCode(ex.getClass().getSimpleName())
                    .message(ex.getMessage())
                    .build());
            sseEmitterService.complete(chatId);
        }
    }

    private String buildUserContent(List<RetrievedChunk> chunks, String query) {
        StringBuilder sb = new StringBuilder("<context>\n");
        for (var chunk : chunks) {
            sb.append("<chunk source=\"").append(chunk.kbType().name().toLowerCase())
              .append("\" id=\"").append(chunk.chunkId())
              .append("\" score=\"").append(chunk.score()).append("\">\n")
              .append(chunk.text()).append("\n</chunk>\n");
        }
        sb.append("</context>\n\nQuestion: ").append(query);
        // TODO(ChatService:buildUserContent): include last N chat history turns as prior messages
        // for multi-turn conversation. See ChatMessageRepository.findByChatId().
        return sb.toString();
    }

    private CitationDTO toCitationDTO(RetrievedChunk chunk) {
        return CitationDTO.builder()
                .kbType(chunk.kbType().name())
                .chunkId(chunk.chunkId())
                .score(chunk.score())
                .s3Uri(chunk.s3Uri())
                .sourceText(chunk.text() != null && chunk.text().length() > 500
                        ? chunk.text().substring(0, 500) : chunk.text())
                .build();
    }

    private Citation toCitationModel(RetrievedChunk chunk) {
        Citation c = new Citation();
        c.setKbType(chunk.kbType().name());
        c.setChunkId(chunk.chunkId());
        c.setScore(chunk.score());
        c.setS3Uri(chunk.s3Uri());
        c.setSourceText(chunk.text() != null && chunk.text().length() > 500
                ? chunk.text().substring(0, 500) : chunk.text());
        return c;
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

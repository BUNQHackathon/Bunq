package com.bunq.javabackend.service.launch;

import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.enums.RunStatus;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.launch.Launch;
import com.bunq.javabackend.model.launch.LaunchKind;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.AutoDocService;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.compliance.EvidenceService;
import com.bunq.javabackend.service.pipeline.PipelineOrchestrator;
import com.bunq.javabackend.service.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LaunchServiceRunJurisdictionTest {

    @Mock private LaunchRepository launchRepository;
    @Mock private JurisdictionRunRepository jurisdictionRunRepository;
    @Mock private GapRepository gapRepository;
    @Mock private ObligationRepository obligationRepository;
    @Mock private ControlRepository controlRepository;
    @Mock private SessionService sessionService;
    @Mock private SessionRepository sessionRepository;
    @Mock private PipelineOrchestrator pipelineOrchestrator;
    @Mock private AutoDocService autoDocService;
    @Mock private BedrockService bedrockService;
    @Mock private EvidenceService evidenceService;

    private LaunchService launchService;

    @BeforeEach
    void setUp() {
        launchService = new LaunchService(
                launchRepository, jurisdictionRunRepository, gapRepository,
                obligationRepository, controlRepository, sessionService, sessionRepository,
                pipelineOrchestrator, autoDocService, bedrockService,
                new ObjectMapper(), evidenceService,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Launch launch(String id) {
        return Launch.builder()
                .id(id)
                .name("Test Launch")
                .brief("brief text")
                .kind(LaunchKind.PRODUCT)
                .status(RunStatus.RUNNING)
                .build();
    }

    private JurisdictionRun run(String launchId, String code) {
        return JurisdictionRun.builder()
                .launchId(launchId)
                .jurisdictionCode(code)
                .status(RunStatus.RUNNING)
                .gapsCount(0)
                .sanctionsHits(0)
                .build();
    }

    private Session session(String id) {
        Session s = new Session();
        s.setId(id);
        return s;
    }

    private Document document(String id, String kind) {
        Document d = new Document();
        d.setId(id);
        d.setKind(kind);
        return d;
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    void overrideDocIds_nonEmpty_usedDirectly_autoDocServiceNotCalled() {
        String launchId = "launch-1";
        String code = "NL";
        List<String> overrideIds = List.of("doc-override-1", "doc-override-2");

        when(launchRepository.findById(launchId)).thenReturn(Optional.of(launch(launchId)));
        when(jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code))
                .thenReturn(Optional.of(run(launchId, code)));
        when(sessionService.createSessionForJurisdiction(launchId, code)).thenReturn(session("sess-1"));
        doNothing().when(pipelineOrchestrator).start(any(), any());

        launchService.runJurisdiction(launchId, code, overrideIds, null);

        // autoDocService.forJurisdiction must NOT be called when overrides are provided
        verify(autoDocService, never()).forJurisdiction(any());

        // The session's documentIds should be exactly the override list
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertEquals(overrideIds, sessionCaptor.getValue().getDocumentIds());
    }

    @Test
    void overrideDocIds_null_autoDocServiceCalled() {
        String launchId = "launch-2";
        String code = "DE";

        when(launchRepository.findById(launchId)).thenReturn(Optional.of(launch(launchId)));
        when(jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code))
                .thenReturn(Optional.of(run(launchId, code)));
        when(sessionService.createSessionForJurisdiction(launchId, code)).thenReturn(session("sess-2"));
        when(autoDocService.forJurisdiction(code)).thenReturn(List.of(document("doc-auto-1", "regulation")));
        doNothing().when(pipelineOrchestrator).start(any(), any());

        launchService.runJurisdiction(launchId, code, null, null);

        verify(autoDocService).forJurisdiction(code);
    }

    @Test
    void overrideDocIds_emptyList_autoDocServiceCalled() {
        String launchId = "launch-3";
        String code = "FR";

        when(launchRepository.findById(launchId)).thenReturn(Optional.of(launch(launchId)));
        when(jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code))
                .thenReturn(Optional.of(run(launchId, code)));
        when(sessionService.createSessionForJurisdiction(launchId, code)).thenReturn(session("sess-3"));
        when(autoDocService.forJurisdiction(code)).thenReturn(List.of());
        doNothing().when(pipelineOrchestrator).start(any(), any());

        launchService.runJurisdiction(launchId, code, List.of(), null);

        verify(autoDocService).forJurisdiction(code);
    }

    @Test
    void runJurisdiction_updatesRunStatusToRunning() {
        String launchId = "launch-4";
        String code = "NL";

        when(launchRepository.findById(launchId)).thenReturn(Optional.of(launch(launchId)));
        JurisdictionRun existingRun = run(launchId, code);
        when(jurisdictionRunRepository.findByLaunchIdAndCode(launchId, code))
                .thenReturn(Optional.of(existingRun));
        when(sessionService.createSessionForJurisdiction(launchId, code)).thenReturn(session("sess-4"));
        when(autoDocService.forJurisdiction(code)).thenReturn(List.of());
        doNothing().when(pipelineOrchestrator).start(any(), any());

        JurisdictionRun result = launchService.runJurisdiction(launchId, code, null, null);

        assertEquals(RunStatus.RUNNING, result.getStatus());
    }
}

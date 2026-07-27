package com.bunq.javabackend.service.pipeline.stage;

import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.EvidenceRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.service.ai.bedrock.ObligationControlMatcher;
import com.bunq.javabackend.service.ai.kb.KnowledgeBaseService;
import com.bunq.javabackend.service.ai.kb.Reranker;
import com.bunq.javabackend.service.infra.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the rerank-all candidate path added to MapObligationsControlsStage:
 * anchor detection (with the short-acronym word-boundary rule), the anchor-rescue
 * merge/cap logic, and query-text construction.
 */
@ExtendWith(MockitoExtension.class)
class MapObligationsControlsAnchorTest {

    @Mock private ObligationControlMatcher matcher;
    @Mock private MappingRepository mappingRepository;
    @Mock private ObligationRepository obligationRepository;
    @Mock private ControlRepository controlRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private EvidenceRepository evidenceRepository;
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private Reranker reranker;

    private final Executor syncExecutor = Runnable::run;
    private MapObligationsControlsStage stage;

    @BeforeEach
    void setUp() {
        stage = new MapObligationsControlsStage(
                matcher, mappingRepository, obligationRepository, controlRepository,
                auditLogService, evidenceRepository, knowledgeBaseService, reranker, syncExecutor);
    }

    private Control control(String id, String description) {
        Control c = new Control();
        c.setId(id);
        c.setDescription(description);
        return c;
    }

    // ── anchor detection ─────────────────────────────────────────────────────

    @Test
    void detectAnchors_findsPepInObligationQuery() {
        Set<String> anchors = MapObligationsControlsStage.detectAnchors(
                "senior management approval before establishing a PEP relationship");

        assertTrue(anchors.contains("pep"));
        assertTrue(anchors.contains("senior management approval"));
    }

    @Test
    void anchorRescue_forceIncludesPepControlAbsentFromRerankedList() {
        Control pepControl = control("ctrl-pep", "Controls covering PEP onboarding checks");
        Control otherControl = control("ctrl-other", "Unrelated payment reconciliation control");
        List<Control> allControls = List.of(pepControl, otherControl);
        // reranked list does NOT contain pepControl
        List<Control> reranked = List.of(otherControl);

        List<Control> result = stage.applyAnchorRescue(
                "senior management approval before establishing a PEP relationship",
                reranked, allControls);

        assertTrue(result.contains(pepControl), "PEP control must be force-included by anchor rescue");
        assertTrue(result.contains(otherControl));
    }

    // ── short-acronym (<=3 char) word-boundary rule ──────────────────────────

    @Test
    void detectAnchors_strDoesNotMatchInsideInstructionsOrStrategy() {
        Set<String> inInstructions = MapObligationsControlsStage.detectAnchors(
                "Follow the instructions carefully.");
        Set<String> inStrategy = MapObligationsControlsStage.detectAnchors(
                "Review the AML strategy annually.");

        assertFalse(inInstructions.contains("str"), "\"str\" must not match inside \"instructions\"");
        assertFalse(inStrategy.contains("str"), "\"str\" must not match inside \"strategy\"");
    }

    @Test
    void detectAnchors_strMatchesAsWholeWord() {
        Set<String> anchors = MapObligationsControlsStage.detectAnchors(
                "File an STR with the FIU within 30 days.");

        assertTrue(anchors.contains("str"));
        assertTrue(anchors.contains("fiu"));
    }

    // ── candidate cap: 20 reranked + 8 anchors -> caps at 25, anchors retained ──

    @Test
    void applyAnchorRescue_dedupsAndCapsAt25WithAnchorsRetained() {
        List<Control> reranked = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reranked.add(control("reranked-" + i, "Generic control description " + i));
        }
        List<Control> anchorOnly = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // each description carries a distinct anchor term so all 8 are force-included
            anchorOnly.add(control("anchor-" + i, "Control covering KYC due diligence item " + i));
        }
        List<Control> allControls = new ArrayList<>();
        allControls.addAll(reranked);
        allControls.addAll(anchorOnly);

        List<Control> result = stage.applyAnchorRescue("perform KYC due diligence checks", reranked, allControls);

        assertEquals(25, result.size(), "final candidate list must be capped at 25");
        for (Control anchor : anchorOnly) {
            assertTrue(result.contains(anchor), "anchor control " + anchor.getId() + " must be retained under the cap");
        }
        // exactly 17 of the 20 reranked controls survive (25 - 8 anchors)
        long survivingReranked = reranked.stream().filter(result::contains).count();
        assertEquals(17, survivingReranked);
    }

    // ── query building ────────────────────────────────────────────────────────

    @Test
    void buildQueryText_skipsNullBlankPartsAndExcludesRiskCategory() {
        Obligation obl = new Obligation();
        obl.setSubject("  ");
        obl.setAction("perform enhanced due diligence");
        obl.setConditions(Arrays.asList("", "  ", "for high-risk customers", null));
        obl.setRiskCategory("UNKNOWN");

        String query = MapObligationsControlsStage.buildQueryText(obl);

        assertEquals("perform enhanced due diligence for high-risk customers", query);
        assertFalse(query.toUpperCase().contains("UNKNOWN"), "riskCategory must never appear in the query text");
    }

    @Test
    void buildQueryText_allNullFieldsProducesBlankQuery() {
        Obligation obl = new Obligation();
        String query = MapObligationsControlsStage.buildQueryText(obl);
        assertTrue(query.isBlank());
    }
}

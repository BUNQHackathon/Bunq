package com.bunq.javabackend.service.launch;

import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.helper.GapNarrative;
import com.bunq.javabackend.model.audit.AuditLogEntry;
import com.bunq.javabackend.model.control.Control;
import com.bunq.javabackend.model.document.Document;
import com.bunq.javabackend.model.evidence.Evidence;
import com.bunq.javabackend.model.gap.Gap;
import com.bunq.javabackend.model.gap.RecommendedAction;
import com.bunq.javabackend.model.gap.SeverityDimensions;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.launch.Launch;
import com.bunq.javabackend.model.mapping.Mapping;
import com.bunq.javabackend.model.obligation.Obligation;
import com.bunq.javabackend.model.sanction.SanctionHit;
import com.bunq.javabackend.repository.AuditLogRepository;
import com.bunq.javabackend.repository.ControlRepository;
import com.bunq.javabackend.repository.DocumentRepository;
import com.bunq.javabackend.repository.EvidenceRepository;
import com.bunq.javabackend.repository.GapRepository;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.LaunchRepository;
import com.bunq.javabackend.repository.MappingRepository;
import com.bunq.javabackend.repository.ObligationRepository;
import com.bunq.javabackend.repository.SanctionHitRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.pipeline.CoverageSummary;
import com.bunq.javabackend.service.pipeline.GapCoverage.CoverageStatus;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProofPackService {

    private final LaunchRepository launchRepository;
    private final JurisdictionRunRepository jurisdictionRunRepository;
    private final SessionRepository sessionRepository;
    private final ObligationRepository obligationRepository;
    private final ControlRepository controlRepository;
    private final MappingRepository mappingRepository;
    private final GapRepository gapRepository;
    private final EvidenceRepository evidenceRepository;
    private final SanctionHitRepository sanctionHitRepository;
    private final DocumentRepository documentRepository;
    private final AuditLogRepository auditLogRepository;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    /** Display order for coverage statuses across the executive and findings PDFs. */
    private static final List<CoverageStatus> COVERAGE_ORDER = List.of(
            CoverageStatus.SATISFIED,
            CoverageStatus.SUBSTANTIALLY_COVERED,
            CoverageStatus.PARTIAL,
            CoverageStatus.JURISDICTION_DELTA,
            CoverageStatus.NEEDS_REVIEW,
            CoverageStatus.CONTROL_MISSING
    );

    private static final Map<String, String> JURISDICTION_NAMES = Map.of(
            "NL", "Netherlands",
            "DE", "Germany",
            "US", "United States",
            "AT", "Austria",
            "FR", "France",
            "IT", "Italy",
            "ES", "Spain"
    );

    public byte[] generate(String launchId, String jurisdictionCode) {
        var launch = launchRepository.findById(launchId)
                .orElseThrow(() -> new NotFoundException("Launch not found: " + launchId));
        var run = jurisdictionRunRepository.findByLaunchIdAndCode(launchId, jurisdictionCode)
                .orElseThrow(() -> new NotFoundException(
                        "JurisdictionRun not found: launch=" + launchId + " code=" + jurisdictionCode));

        String sessionId = run.getCurrentSessionId();
        String jurisdictionName = JURISDICTION_NAMES.getOrDefault(jurisdictionCode, jurisdictionCode);

        var obligations = obligationRepository.findBySessionId(sessionId);
        var controls = controlRepository.findBySessionId(sessionId);
        var mappings = mappingRepository.findBySessionId(sessionId);
        var gaps = gapRepository.findBySessionId(sessionId);
        var sanctionHits = sanctionHitRepository.findBySessionId(sessionId);
        var evidences = evidenceRepository.findBySessionId(sessionId);
        var auditEntries = auditLogRepository.findBySessionId(sessionId).stream()
                .sorted(Comparator.comparing(AuditLogEntry::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<Document> documents = List.of();
        var session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && session.getDocumentIds() != null && !session.getDocumentIds().isEmpty()) {
            documents = documentRepository.findByIds(session.getDocumentIds());
        }

        try {
            var bos = new ByteArrayOutputStream();
            var zos = new ZipOutputStream(bos);

            addZipEntry(zos, "executive_report.pdf",
                    buildCoverPdf(launch, run, jurisdictionCode, jurisdictionName,
                            obligations, controls, mappings, gaps, documents,
                            session != null ? session.getExecutiveSummary() : null));
            addZipEntry(zos, "mappings.xlsx",
                    buildMappingsXlsx(mappings, obligations, controls, evidences));
            addZipEntry(zos, "gaps.pdf",
                    buildGapsPdf(gaps, obligations,
                            session != null ? session.getExecutiveSummary() : null,
                            run.getVerdict(),
                            jurisdictionName));
            addZipEntry(zos, "sanctions.pdf",
                    buildSanctionsPdf(run, jurisdictionName, sanctionHits));
            addEvidenceFiles(zos, evidences);
            addZipEntry(zos, "audit_trail.json",
                    buildAuditTrailJson(auditEntries));

            zos.close();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build proof pack ZIP", e);
        }
    }

    private void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private byte[] buildCoverPdf(Launch launch, JurisdictionRun run,
                                  String code, String jName,
                                  List<Obligation> obligations, List<Control> controls,
                                  List<Mapping> mappings, List<Gap> gaps,
                                  List<Document> documents, String execSummary) {
        var bos = new ByteArrayOutputStream();
        var doc = new com.lowagie.text.Document(PageSize.A4);
        PdfWriter.getInstance(doc, bos);
        doc.open();

        var titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22);
        var subFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        var normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
        var smallFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

        String verdict = run.getVerdict() != null ? run.getVerdict() : "UNKNOWN";

        safePdf(doc, new Paragraph(jName + " — " + launch.getName() + " — AML/AFC Gap Analysis: Executive Report", titleFont));
        safePdf(doc, new Paragraph(
                "Generated: " + Instant.now() + " | Run #1 | Verdict: " + verdictEmoji(verdict) + " " + verdict, subFont));
        safePdf(doc, new Paragraph(" "));

        safePdf(doc, new Paragraph("Launch: " + launch.getName(), normalFont));
        if (launch.getBrief() != null && !launch.getBrief().isBlank()) {
            String brief = launch.getBrief();
            safePdf(doc, new Paragraph(brief.length() > 200 ? brief.substring(0, 200) + "..." : brief, normalFont));
        }
        safePdf(doc, new Paragraph("Jurisdiction: " + code + " — " + jName, normalFont));
        safePdf(doc, new Paragraph("Run timestamp: " + (run.getLastRunAt() != null ? run.getLastRunAt() : Instant.now()), normalFont));
        safePdf(doc, new Paragraph("Pipeline version: v1", normalFont));
        safePdf(doc, new Paragraph(" "));

        safePdf(doc, new Paragraph("Counts:", subFont));
        safePdf(doc, new Paragraph(
                obligations.size() + " obligations  /  " +
                controls.size() + " controls  /  " +
                mappings.size() + " mappings  /  " +
                gaps.size() + " gaps  /  " +
                safe(run.getSanctionsHits()) + " sanctions hits", normalFont));
        safePdf(doc, new Paragraph(" "));

        long escalated = gaps.stream().filter(g -> Boolean.TRUE.equals(g.getEscalationRequired())).count();
        long highResidual = gaps.stream()
                .filter(g -> !Boolean.TRUE.equals(g.getEscalationRequired())
                        && g.getResidualRisk() != null && g.getResidualRisk() >= 0.40)
                .count();
        long other = gaps.size() - escalated - highResidual;
        safePdf(doc, new Paragraph("Severity breakdown:", subFont));
        safePdf(doc, new Paragraph(
                escalated + " escalated  /  " + highResidual + " elevated-high residual  /  " + other + " other",
                normalFont));
        safePdf(doc, new Paragraph(" "));

        if (!documents.isEmpty()) {
            safePdf(doc, new Paragraph("Policy versions used:", subFont));
            for (var d : documents) {
                String lastUsed = d.getLastUsedAt() != null ? d.getLastUsedAt().toString() : "—";
                String hashPart = documentHashLabel(d.getId());
                String line = "• " + safeStr(d.getFilename()) + (hashPart != null ? "  " + hashPart : "") + "  last used:" + lastUsed;
                safePdf(doc, new Paragraph(line, smallFont));
            }
            safePdf(doc, new Paragraph(" "));
        }

        var oblById = new HashMap<String, Obligation>();
        for (var o : obligations) if (o.getId() != null) oblById.put(o.getId(), o);
        var controlById = new HashMap<String, Control>();
        for (var c : controls) if (c.getId() != null) controlById.put(c.getId(), c);

        boolean hasSemantics = CoverageSummary.hasSemantics(gaps);
        var statusCounts = CoverageSummary.countByStatus(gaps, mappings);

        // ---- 1. Coverage summary table ----
        if (hasSemantics) {
            int totalAssessed = obligations.size();
            safePdf(doc, new Paragraph("Coverage summary:", subFont));
            if (totalAssessed > 0) {
                for (var status : COVERAGE_ORDER) {
                    int c = statusCounts.getOrDefault(status, 0);
                    double pct = 100.0 * c / totalAssessed;
                    safePdf(doc, new Paragraph(
                            "• " + humanizeStatus(status) + " — " + c + " ("
                                    + String.format(java.util.Locale.ROOT, "%.1f", pct) + "%)", normalFont));
                }
            }
            safePdf(doc, new Paragraph(" "));
        }

        // ---- 2. Existing controls recognised ----
        var satisfiedMappings = mappings.stream()
                .filter(m -> m.getObligationId() != null && m.getControlId() != null
                        && m.getMappingConfidence() != null && m.getMappingConfidence() >= 75)
                .sorted(Comparator.comparingDouble((Mapping m) -> -m.getMappingConfidence()))
                .toList();
        if (!satisfiedMappings.isEmpty()) {
            var seenObligations = new java.util.HashSet<String>();
            var entries = new java.util.ArrayList<String>();
            for (var m : satisfiedMappings) {
                if (entries.size() >= 15) break;
                if (!seenObligations.add(m.getObligationId())) continue;
                var obl = oblById.get(m.getObligationId());
                var ctrl = controlById.get(m.getControlId());
                if (obl == null || ctrl == null) continue;
                String oblLabel = obl.getSource() != null
                        ? safeStr(obl.getSource().getRegulation()) + " " + safeStr(obl.getSource().getArticle())
                        : safeStr(obl.getId());
                String snippet = obl.getAction() != null && !obl.getAction().isBlank() ? obl.getAction() : obl.getSubject();
                if (snippet != null && !snippet.isBlank()) {
                    oblLabel += " — " + truncate(snippet, 100);
                }
                entries.add("• " + oblLabel + "  —  Evidence: " + truncate(safeStr(ctrl.getDescription()), 140));
            }
            if (!entries.isEmpty()) {
                safePdf(doc, new Paragraph("Existing controls recognised:", subFont));
                for (var line : entries) {
                    safePdf(doc, new Paragraph(line, smallFont));
                }
                safePdf(doc, new Paragraph(" "));
            }
        }

        // ---- 3. Jurisdiction deltas ----
        var deltaGaps = gaps.stream()
                .filter(g -> CoverageSummary.parseStatus(g) == CoverageStatus.JURISDICTION_DELTA)
                .toList();
        if (!deltaGaps.isEmpty()) {
            safePdf(doc, new Paragraph(
                    "Jurisdiction deltas (generic control present — " + jName + "-specific requirement not evidenced):", subFont));
            int topN = Math.min(15, deltaGaps.size());
            for (int i = 0; i < topN; i++) {
                var g = deltaGaps.get(i);
                var obl = g.getObligationId() != null ? oblById.get(g.getObligationId()) : null;
                String title = obl != null && obl.getSource() != null
                        ? safeStr(obl.getSource().getRegulation()) + " " + safeStr(obl.getSource().getArticle())
                        : safeStr(g.getObligationId());
                String conf = g.getMetadata() != null ? g.getMetadata().get("best_mapping_confidence") : null;
                safePdf(doc, new Paragraph("• " + title + "  best_confidence=" + safeStr(conf), smallFont));
            }
            if (deltaGaps.size() > 15) {
                safePdf(doc, new Paragraph("... and " + (deltaGaps.size() - 15) + " more jurisdiction deltas (see gaps.pdf)", smallFont));
            }
            safePdf(doc, new Paragraph(" "));
        }

        // ---- 4. Missing controls (restricted to control_missing; falls back to all gaps for older sessions without gap_semantics) ----
        var missingGaps = hasSemantics
                ? gaps.stream().filter(g -> CoverageSummary.parseStatus(g) == CoverageStatus.CONTROL_MISSING).toList()
                : gaps;
        if (!missingGaps.isEmpty()) {
            safePdf(doc, new Paragraph("Missing controls:", subFont));
            var sortedGaps = missingGaps.stream()
                    .sorted(Comparator
                            .comparing((Gap g) -> Boolean.TRUE.equals(g.getEscalationRequired()) ? 0 : 1)
                            .thenComparingDouble(g -> -(g.getResidualRisk() != null ? g.getResidualRisk() : 0.0))
                            .thenComparingDouble(g -> -(g.getSeverity() != null ? g.getSeverity() : 0.0)))
                    .toList();
            int topN = Math.min(20, sortedGaps.size());
            for (int i = 0; i < topN; i++) {
                var g = sortedGaps.get(i);
                var obl = g.getObligationId() != null ? oblById.get(g.getObligationId()) : null;
                String title = obl != null && obl.getSource() != null
                        ? safeStr(obl.getSource().getRegulation()) + " " + safeStr(obl.getSource().getArticle())
                        : safeStr(g.getObligationId());
                String sev = g.getSeverityDimensions() != null
                        ? "score=" + fmt(g.getSeverityDimensions().getCombinedRiskScore())
                        : "residualRisk=" + fmt(g.getResidualRisk());
                safePdf(doc, new Paragraph("• " + title + "  " + sev, smallFont));
            }
            if (sortedGaps.size() > 20) {
                safePdf(doc, new Paragraph("... and " + (sortedGaps.size() - 20) + " more missing-control gaps (see gaps.pdf)", smallFont));
            }
            safePdf(doc, new Paragraph(" "));
        }

        // ---- 5. Needs review ----
        int needsReviewCount = statusCounts.getOrDefault(CoverageStatus.NEEDS_REVIEW, 0);
        if (needsReviewCount > 0) {
            safePdf(doc, new Paragraph(
                    "Needs review: " + needsReviewCount
                            + " obligation(s) with degraded retrieval or unrecorded confidence — not claimed as covered or missing.",
                    normalFont));
            safePdf(doc, new Paragraph(" "));
        }

        if (execSummary != null && !execSummary.isBlank()) {
            safePdf(doc, new Paragraph("Executive Summary", subFont));
            String cleaned = GapNarrative.clean(execSummary);
            String stripped = cleaned != null ? GapNarrative.stripMarkdown(cleaned) : null;
            if (stripped != null && !stripped.isBlank()) {
                if (stripped.length() > 3000) stripped = stripped.substring(0, 3000) + "…";
                safePdf(doc, new Paragraph(stripped, normalFont));
            }
            safePdf(doc, new Paragraph(" "));
        }

        safePdf(doc, new Paragraph("Owner / Contact: compliance@bunq.com", normalFont));
        doc.close();

        return bos.toByteArray();
    }

    private byte[] buildMappingsXlsx(List<Mapping> mappings, List<Obligation> obligations,
                                      List<Control> controls, List<Evidence> evidences) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Mappings");

            var headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            String[] headers = {"Regulation", "Article/Clause", "Obligation", "Internal Control", "Evidence (filename + SHA-256)", "Status"};
            var headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                var cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            var mappingByObligationId = new HashMap<String, Mapping>();
            for (var m : mappings) {
                if (m.getObligationId() != null) mappingByObligationId.put(m.getObligationId(), m);
            }
            var evidenceByMappingId = new HashMap<String, Evidence>();
            for (var e : evidences) {
                if (e.getRelatedMappingId() != null) evidenceByMappingId.put(e.getRelatedMappingId(), e);
            }
            var controlById = new HashMap<String, Control>();
            for (var c : controls) if (c.getId() != null) controlById.put(c.getId(), c);

            int rowIdx = 1;
            for (var obl : obligations) {
                var mapping = mappingByObligationId.get(obl.getId());
                var row = sheet.createRow(rowIdx++);

                String regulation = obl.getSource() != null ? safeStr(obl.getSource().getRegulation()) : "—";
                String article = obl.getSource() != null ? safeStr(obl.getSource().getArticle()) : "—";
                String oblText = obl.getAction() != null ? obl.getAction()
                        : (obl.getSubject() != null ? obl.getSubject() : "—");

                row.createCell(0).setCellValue(regulation);
                row.createCell(1).setCellValue(article);
                row.createCell(2).setCellValue(oblText);

                if (mapping != null) {
                    var ctrl = mapping.getControlId() != null ? controlById.get(mapping.getControlId()) : null;
                    row.createCell(3).setCellValue(ctrl != null ? safeStr(ctrl.getDescription()) : "—");
                    var ev = evidenceByMappingId.get(mapping.getId());
                    if (ev != null) {
                        String sha = ev.getSha256() != null && ev.getSha256().length() >= 12
                                ? ev.getSha256().substring(0, 12) : safeStr(ev.getSha256());
                        row.createCell(4).setCellValue(safeStr(ev.getDescription()) + " · " + sha);
                        row.createCell(5).setCellValue("Covered");
                    } else {
                        row.createCell(4).setCellValue("—");
                        row.createCell(5).setCellValue("Partial");
                    }
                } else {
                    row.createCell(3).setCellValue("—");
                    row.createCell(4).setCellValue("—");
                    row.createCell(5).setCellValue("Gap");
                }
            }

            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            wb.write(bos);
        }

        return bos.toByteArray();
    }

    private byte[] buildGapsPdf(List<Gap> gaps, List<Obligation> obligations,
                                 String execSummary, String verdict, String jurisdictionName) {
        var bos = new ByteArrayOutputStream();
        var doc = new com.lowagie.text.Document(PageSize.A4);
        PdfWriter.getInstance(doc, bos);
        doc.open();

        var titleFont  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        var subFont    = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        var normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11);

        var oblById = new HashMap<String, Obligation>();
        for (var o : obligations) oblById.put(o.getId(), o);

        // Sort: escalation_required first, then residualRisk descending (nulls last)
        var sorted = new java.util.ArrayList<>(gaps);
        sorted.sort(Comparator
                .<Gap, Boolean>comparing(g -> g.getEscalationRequired() != null && g.getEscalationRequired(),
                        Comparator.reverseOrder())
                .thenComparing(g -> g.getResidualRisk() != null ? g.getResidualRisk() : 0.0,
                        Comparator.reverseOrder()));

        // ---- Summary page ----
        String jName = jurisdictionName != null ? jurisdictionName : "—";
        safePdf(doc, new Paragraph(jName + " — AML/AFC Gap Analysis: Findings", titleFont));
        safePdf(doc, new Paragraph(" "));

        String verdictText = verdict != null ? verdict : "UNKNOWN";
        safePdf(doc, new Paragraph("Verdict: " + verdictEmoji(verdict) + " " + verdictText, subFont));
        safePdf(doc, new Paragraph(" "));

        int total = sorted.size();
        long escalated    = sorted.stream().filter(g -> Boolean.TRUE.equals(g.getEscalationRequired())).count();
        long highResidual = sorted.stream()
                .filter(g -> !Boolean.TRUE.equals(g.getEscalationRequired())
                          && g.getResidualRisk() != null && g.getResidualRisk() >= 0.4)
                .count();
        long other = total - escalated - highResidual;
        safePdf(doc, new Paragraph(
                total + " gaps — " + escalated + " escalated, " +
                highResidual + " elevated/high residual, " + other + " other", normalFont));
        safePdf(doc, new Paragraph(" "));

        if (execSummary != null && !execSummary.isBlank()) {
            safePdf(doc, new Paragraph("Executive Summary", subFont));
            String cleaned = GapNarrative.stripMarkdown(GapNarrative.clean(execSummary));
            if (cleaned != null) {
                if (cleaned.length() > 4000) cleaned = cleaned.substring(0, 4000) + "…";
                safePdf(doc, new Paragraph(cleaned, normalFont));
            }
            safePdf(doc, new Paragraph(" "));
        }

        safePdf(doc, new Paragraph("Priority findings", subFont));
        if (sorted.isEmpty()) {
            safePdf(doc, new Paragraph("No gaps identified.", normalFont));
        } else {
            int limit = Math.min(10, sorted.size());
            for (int i = 0; i < limit; i++) {
                var g = sorted.get(i);
                var obl = g.getObligationId() != null ? oblById.get(g.getObligationId()) : null;
                String reg = obl != null && obl.getSource() != null ? safeStr(obl.getSource().getRegulation()) : "—";
                String art = obl != null && obl.getSource() != null ? safeStr(obl.getSource().getArticle()) : "—";
                String esc = Boolean.TRUE.equals(g.getEscalationRequired()) ? "  [ESCALATION REQUIRED]" : "";
                safePdf(doc, new Paragraph(
                        (i + 1) + ". " + reg + " " + art + " — " + residualLevelLabel(g.getResidualRisk()) + esc,
                        normalFont));
            }
        }

        // ---- One page per gap, grouped by coverage classification ----
        var groups = new java.util.LinkedHashMap<String, List<Gap>>();
        for (var status : COVERAGE_ORDER) {
            if (status == CoverageStatus.SATISFIED) continue; // SATISFIED obligations never produce a Gap
            groups.put(humanizeStatus(status), new java.util.ArrayList<>());
        }
        groups.put("Other / unclassified findings", new java.util.ArrayList<>());
        for (var g : sorted) {
            var status = CoverageSummary.parseStatus(g);
            String key = status != null ? humanizeStatus(status) : "Other / unclassified findings";
            groups.get(key).add(g);
        }

        for (var group : groups.entrySet()) {
            var groupGaps = group.getValue();
            if (groupGaps.isEmpty()) continue;

            doc.newPage();
            safePdf(doc, new Paragraph(group.getKey() + " (" + groupGaps.size() + ")", titleFont));
            safePdf(doc, new Paragraph(" "));

            for (var gap : groupGaps) {
            doc.newPage();

            var obl = gap.getObligationId() != null ? oblById.get(gap.getObligationId()) : null;
            String regulation = obl != null && obl.getSource() != null ? safeStr(obl.getSource().getRegulation()) : "—";
            String article    = obl != null && obl.getSource() != null ? safeStr(obl.getSource().getArticle()) : "—";
            String text = obl != null && obl.getSource() != null && obl.getSource().getSourceText() != null
                    ? obl.getSource().getSourceText()
                    : (obl != null && obl.getAction() != null ? obl.getAction() : "—");
            if (text.length() > 300) text = text.substring(0, 300) + "...";

            boolean isEscalated = Boolean.TRUE.equals(gap.getEscalationRequired());
            String heading = regulation + " " + article
                    + " — Residual: " + residualLevelLabel(gap.getResidualRisk())
                    + (isEscalated ? "  — ESCALATION REQUIRED" : "");
            safePdf(doc, new Paragraph(heading, titleFont));
            safePdf(doc, new Paragraph(" "));

            safePdf(doc, new Paragraph("Obligation: " + text, normalFont));
            safePdf(doc, new Paragraph(" "));

            safePdf(doc, new Paragraph("Residual risk: " + residualLevelLabel(gap.getResidualRisk())
                    + "  (" + fmt(gap.getResidualRisk()) + ")", normalFont));

            SeverityDimensions dims = gap.getSeverityDimensions();
            if (dims != null) {
                safePdf(doc, new Paragraph("Regulatory urgency: " + fmt(dims.getRegulatoryUrgency()), normalFont));
                safePdf(doc, new Paragraph("Penalty severity:   " + fmt(dims.getPenaltySeverity()), normalFont));
                safePdf(doc, new Paragraph("Probability:        " + fmt(dims.getProbability()), normalFont));
                safePdf(doc, new Paragraph("Business impact:    " + fmt(dims.getBusinessImpact()), normalFont));
                safePdf(doc, new Paragraph("Combined score:     " + fmt(dims.getCombinedRiskScore()), normalFont));
            }
            safePdf(doc, new Paragraph(" "));

            safePdf(doc, new Paragraph("Gap type: " + (gap.getGapType() != null ? gap.getGapType().name() : "—"), normalFont));
            String narr = GapNarrative.stripMarkdown(GapNarrative.clean(gap.getNarrative()));
            if (narr != null && !narr.isBlank()) {
                safePdf(doc, new Paragraph("Narrative: " + narr, normalFont));
            }
            safePdf(doc, new Paragraph(" "));

            safePdf(doc, new Paragraph("Remediation", subFont));
            List<RecommendedAction> actions = gap.getRecommendedActions();
            if (actions == null || actions.isEmpty()) {
                actions = GapNarrative.recoverActions(gap.getNarrative());
            }
            if (!actions.isEmpty()) {
                String firstOwner = null;
                for (var a : actions) {
                    String ownerSuffix = (a.getSuggestedOwner() != null && !a.getSuggestedOwner().isBlank())
                            ? "  (owner: " + a.getSuggestedOwner() + ")" : "";
                    safePdf(doc, new Paragraph("• " + safeStr(a.getAction()) + ownerSuffix, normalFont));
                    if (firstOwner == null && a.getSuggestedOwner() != null && !a.getSuggestedOwner().isBlank()) {
                        firstOwner = a.getSuggestedOwner();
                    }
                }
                safePdf(doc, new Paragraph("Owner: " + (firstOwner != null ? firstOwner : "Compliance"), normalFont));
            } else {
                safePdf(doc, new Paragraph("TBD", normalFont));
                safePdf(doc, new Paragraph("Owner: Compliance", normalFont));
            }
            safePdf(doc, new Paragraph(" "));

            safePdf(doc, new Paragraph("Target date: " + targetDate(gap), normalFont));
            safePdf(doc, new Paragraph("Rerun history: Run #1 — first detected — current", normalFont));
            }
        }

        doc.close();
        return bos.toByteArray();
    }

    /** Maps a 0–1 residual risk score to the Banca d'Italia 4-level scale label. */
    private String residualLevelLabel(Double residual) {
        if (residual == null) return "—";
        if (residual >= 0.66) return "Elevato (4)";
        if (residual >= 0.40) return "Medio (3)";
        if (residual >= 0.20) return "Basso (2)";
        return "Non significativo (1)";
    }

    private byte[] buildSanctionsPdf(JurisdictionRun run, String jurisdictionName, List<SanctionHit> hits) {
        var bos = new ByteArrayOutputStream();
        var doc = new com.lowagie.text.Document(PageSize.A4);
        PdfWriter.getInstance(doc, bos);
        doc.open();

        var titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        var subFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        var normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11);

        safePdf(doc, new Paragraph("Sanctions Screening — " + jurisdictionName + " run", titleFont));
        safePdf(doc, new Paragraph("Run timestamp: " + (run.getLastRunAt() != null ? run.getLastRunAt() : Instant.now()), normalFont));
        safePdf(doc, new Paragraph("Lists screened: OFAC SDN, EU Consolidated, UN, UK OFSI", normalFont));
        safePdf(doc, new Paragraph(" "));

        if (hits.isEmpty()) {
            safePdf(doc, new Paragraph("No counterparties screened for this jurisdiction.", normalFont));
        } else {
            safePdf(doc, new Paragraph("Screening results:", subFont));
            for (var h : hits) {
                String name = h.getCounterparty() != null ? safeStr(h.getCounterparty().getName()) : "—";
                String status = h.getMatchStatus() != null ? h.getMatchStatus().name() : "UNKNOWN";
                String decision = "FLAGGED".equals(status) ? "Pending review" : "Cleared";
                safePdf(doc, new Paragraph("• " + name + "  status=" + status + "  decision=" + decision, normalFont));
                if (h.getHits() != null && !h.getHits().isEmpty()) {
                    for (var m : h.getHits()) {
                        safePdf(doc, new Paragraph("    hit: " + safeStr(m.getListSource()) + " score=" + fmt(m.getMatchScore()), normalFont));
                    }
                }
            }
        }

        doc.close();

        return bos.toByteArray();
    }

    private void addEvidenceFiles(ZipOutputStream zos, List<Evidence> evidences) {
        for (var ev : evidences) {
            if (ev.getS3Key() == null) continue;
            try {
                var bytes = s3Client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(uploadsBucket)
                                .key(ev.getS3Key())
                                .build()
                ).asByteArray();
                String sha = ev.getSha256() != null && ev.getSha256().length() >= 12
                        ? ev.getSha256().substring(0, 12) : safeStr(ev.getSha256());
                String desc = ev.getDescription() != null && !ev.getDescription().isBlank()
                        ? ev.getDescription() : "evidence";
                String safeName = desc.replaceAll("[^a-zA-Z0-9._-]", "_");
                zos.putNextEntry(new ZipEntry("evidence/" + sha + "-" + safeName + ".pdf"));
                zos.write(bytes);
                zos.closeEntry();
            } catch (Exception e) {
                log.warn("Skipping evidence s3Key={}: {}", ev.getS3Key(), e.getMessage());
            }
        }
    }

    private byte[] buildAuditTrailJson(List<AuditLogEntry> entries) throws Exception {
        var arr = objectMapper.createArrayNode();
        for (var e : entries) {
            var node = objectMapper.createObjectNode();
            node.put("ts", e.getTimestamp() != null ? e.getTimestamp().toString() : null);
            node.put("event", e.getAction());
            node.put("entry_hash", e.getEntryHash());
            node.put("prev_hash", e.getPrevHash());
            node.put("actor", e.getActor());
            node.put("session_id", e.getSessionId());
            if (e.getMappingId() != null) node.put("mapping_id", e.getMappingId());
            if (e.getPayloadJson() != null && !e.getPayloadJson().isBlank()) {
                try {
                    node.set("payload", objectMapper.readTree(e.getPayloadJson()));
                } catch (Exception ex) {
                    node.put("payload", e.getPayloadJson());
                }
            }
            arr.add(node);
        }

        return objectMapper.writeValueAsBytes(arr);
    }

    private void safePdf(com.lowagie.text.Document doc, Paragraph p) {
        try {
            doc.add(p);
        } catch (Exception e) {
            log.warn("PDF paragraph add failed: {}", e.getMessage());
        }
    }

    private static final java.util.regex.Pattern SHA256_HEX = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    /** Returns a label string for a document ID, or null if the ID is not a recognisable hash. */
    private String documentHashLabel(String id) {
        if (id == null) return null;
        if (SHA256_HEX.matcher(id).matches()) {
            return "SHA-256:" + id;
        }
        return null;
    }

    private String safeStr(String s) {
        return s != null ? s : "—";
    }

    private String safe(Object o) {
        return o != null ? o.toString() : "—";
    }

    private String fmt(Double d) {
        return d != null ? String.format(java.util.Locale.ROOT, "%.2f", d) : "—";
    }

    private String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String humanizeStatus(CoverageStatus status) {
        return switch (status) {
            case SATISFIED -> "Satisfied";
            case SUBSTANTIALLY_COVERED -> "Substantially covered";
            case PARTIAL -> "Partial";
            case JURISDICTION_DELTA -> "Jurisdiction delta";
            case NEEDS_REVIEW -> "Needs review";
            case CONTROL_MISSING -> "Control missing";
        };
    }

    private String verdictEmoji(String verdict) {
        if (verdict == null) return "";
        return switch (verdict.toUpperCase()) {
            case "GREEN" -> "🟢";
            case "RED" -> "🔴";
            case "AMBER" -> "🟡";
            default -> "";
        };
    }

    private String targetDate(Gap gap) {
        int days = 90;
        if (gap.getSeverityDimensions() != null && gap.getSeverityDimensions().getCombinedRiskScore() != null) {
            double score = gap.getSeverityDimensions().getCombinedRiskScore();
            if (score >= 0.66) days = 30;
            else if (score >= 0.40) days = 60;
        } else if (gap.getResidualRisk() != null) {
            if (gap.getResidualRisk() >= 0.66) days = 30;
            else if (gap.getResidualRisk() >= 0.40) days = 60;
        }
        return LocalDate.now().plusDays(days).toString();
    }
}

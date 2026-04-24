package com.bunq.javabackend.service;

import com.bunq.javabackend.exception.NotFoundException;
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

            addZipEntry(zos, "cover.pdf",
                    buildCoverPdf(launch, run, jurisdictionCode, jurisdictionName,
                            obligations, controls, mappings, gaps, documents));
            addZipEntry(zos, "mappings.xlsx",
                    buildMappingsXlsx(mappings, obligations, controls, evidences));
            addZipEntry(zos, "gaps.pdf",
                    buildGapsPdf(gaps, obligations));
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
                                  List<Document> documents) {
        var bos = new ByteArrayOutputStream();
        var doc = new com.lowagie.text.Document(PageSize.A4);
        PdfWriter.getInstance(doc, bos);
        doc.open();

        var titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22);
        var subFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        var normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11);
        var smallFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

        String verdict = run.getVerdict() != null ? run.getVerdict() : "UNKNOWN";

        safePdf(doc, new Paragraph(jName + " — " + launch.getName() + " Compliance Evidence Pack", titleFont));
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

        if (!documents.isEmpty()) {
            safePdf(doc, new Paragraph("Policy versions used:", subFont));
            for (var d : documents) {
                String sha = d.getId() != null && d.getId().length() >= 12 ? d.getId().substring(0, 12) : safeStr(d.getId());
                String lastUsed = d.getLastUsedAt() != null ? d.getLastUsedAt().toString() : "—";
                safePdf(doc, new Paragraph("• " + safeStr(d.getFilename()) + "  SHA-256:" + sha + "  last used:" + lastUsed, smallFont));
            }
            safePdf(doc, new Paragraph(" "));
        }

        if (!gaps.isEmpty()) {
            safePdf(doc, new Paragraph("Unresolved gaps:", subFont));
            for (var g : gaps) {
                var obl = g.getObligationId() != null ? obligationRepository.findById(g.getObligationId()).orElse(null) : null;
                String title = obl != null && obl.getSource() != null
                        ? safeStr(obl.getSource().getRegulation()) + " " + safeStr(obl.getSource().getArticle())
                        : safeStr(g.getObligationId());
                String sev = g.getSeverityDimensions() != null
                        ? "score=" + fmt(g.getSeverityDimensions().getCombinedRiskScore())
                        : "residualRisk=" + fmt(g.getResidualRisk());
                safePdf(doc, new Paragraph("• " + title + "  " + sev, smallFont));
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

    private byte[] buildGapsPdf(List<Gap> gaps, List<Obligation> obligations) {
        var bos = new ByteArrayOutputStream();
        var doc = new com.lowagie.text.Document(PageSize.A4);
        PdfWriter.getInstance(doc, bos);
        doc.open();

        var titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        var subFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
        var normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11);

        if (gaps.isEmpty()) {
            safePdf(doc, new Paragraph("No gaps identified.", normalFont));
        } else {
            var oblById = new HashMap<String, Obligation>();
            for (var o : obligations) oblById.put(o.getId(), o);

            boolean first = true;
            for (var gap : gaps) {
                if (!first) doc.newPage();
                first = false;

                var obl = gap.getObligationId() != null ? oblById.get(gap.getObligationId()) : null;
                String regulation = obl != null && obl.getSource() != null ? safeStr(obl.getSource().getRegulation()) : "—";
                String article = obl != null && obl.getSource() != null ? safeStr(obl.getSource().getArticle()) : "—";
                String text = obl != null && obl.getSource() != null && obl.getSource().getSourceText() != null
                        ? obl.getSource().getSourceText()
                        : (obl != null && obl.getAction() != null ? obl.getAction() : "—");
                if (text.length() > 300) text = text.substring(0, 300) + "...";

                safePdf(doc, new Paragraph("Gap — " + regulation + " " + article, titleFont));
                safePdf(doc, new Paragraph("Obligation: " + text, normalFont));
                safePdf(doc, new Paragraph(" "));

                safePdf(doc, new Paragraph("Severity", subFont));
                SeverityDimensions dims = gap.getSeverityDimensions();
                if (dims != null) {
                    safePdf(doc, new Paragraph("Regulatory urgency: " + fmt(dims.getRegulatoryUrgency()), normalFont));
                    safePdf(doc, new Paragraph("Penalty severity:   " + fmt(dims.getPenaltySeverity()), normalFont));
                    safePdf(doc, new Paragraph("Probability:        " + fmt(dims.getProbability()), normalFont));
                    safePdf(doc, new Paragraph("Business impact:    " + fmt(dims.getBusinessImpact()), normalFont));
                    safePdf(doc, new Paragraph("Combined score:     " + fmt(dims.getCombinedRiskScore()), normalFont));
                } else {
                    safePdf(doc, new Paragraph("Residual risk: " + fmt(gap.getResidualRisk()), normalFont));
                }
                safePdf(doc, new Paragraph(" "));

                safePdf(doc, new Paragraph("Gap type: " + (gap.getGapType() != null ? gap.getGapType().name() : "—"), normalFont));
                if (gap.getNarrative() != null) {
                    safePdf(doc, new Paragraph("Narrative: " + gap.getNarrative(), normalFont));
                }
                safePdf(doc, new Paragraph(" "));

                safePdf(doc, new Paragraph("Remediation", subFont));
                List<RecommendedAction> actions = gap.getRecommendedActions();
                if (actions != null && !actions.isEmpty()) {
                    for (var a : actions) safePdf(doc, new Paragraph("• " + safeStr(a.getAction()), normalFont));
                } else {
                    safePdf(doc, new Paragraph("TBD", normalFont));
                }
                safePdf(doc, new Paragraph(" "));

                safePdf(doc, new Paragraph("Owner: compliance-officer", normalFont));
                safePdf(doc, new Paragraph("Target date: " + targetDate(gap), normalFont));
                safePdf(doc, new Paragraph("Rerun history: Run #1 — first detected — current", normalFont));
            }
        }

        doc.close();
        return bos.toByteArray();
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

    private String safeStr(String s) {
        return s != null ? s : "—";
    }

    private String safe(Object o) {
        return o != null ? o.toString() : "—";
    }

    private String fmt(Double d) {
        return d != null ? String.format("%.2f", d) : "—";
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
            if (score >= 7.0) days = 30;
            else if (score >= 4.0) days = 60;
        } else if (gap.getResidualRisk() != null) {
            if (gap.getResidualRisk() >= 7.0) days = 30;
            else if (gap.getResidualRisk() >= 4.0) days = 60;
        }
        return LocalDate.now().plusDays(days).toString();
    }
}

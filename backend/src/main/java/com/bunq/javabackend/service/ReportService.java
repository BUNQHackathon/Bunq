package com.bunq.javabackend.service;

import com.bunq.javabackend.dto.response.ExecutiveSummaryDTO;
import com.bunq.javabackend.service.pipeline.PipelineContext;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import com.bunq.javabackend.exception.NotFoundException;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.uploads-bucket}")
    private String uploadsBucket;

    public String generate(PipelineContext ctx, ExecutiveSummaryDTO summary) {
        byte[] pdfBytes = buildPdf(ctx, summary);
        String key = "reports/" + ctx.getSessionId() + ".pdf";

        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(uploadsBucket)
                .key(key)
                .contentType("application/pdf")
                .build(),
            RequestBody.fromBytes(pdfBytes));

        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .getObjectRequest(GetObjectRequest.builder()
                .bucket(uploadsBucket).key(key).build())
            .build();
        return s3Presigner.presignGetObject(presignReq).url().toString();
    }

    public String presignExistingReport(String sessionId) {
        String key = "reports/" + sessionId + ".pdf";

        try {
            s3Client.headObject(HeadObjectRequest.builder()
                .bucket(uploadsBucket)
                .key(key)
                .build());
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Report not generated yet for session: " + sessionId);
        }

        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(5))
            .getObjectRequest(GetObjectRequest.builder()
                .bucket(uploadsBucket).key(key).build())
            .build();
        return s3Presigner.presignGetObject(presignReq).url().toString();
    }

    private byte[] buildPdf(PipelineContext ctx, ExecutiveSummaryDTO summary) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        PdfWriter.getInstance(document, bos);
        document.open();

        // ---- Page 1: Cover ----
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 24);
        Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 12);
        Font verdictFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);

        Paragraph title = new Paragraph("LaunchLens Compliance Report", titleFont);
        title.setAlignment(Paragraph.ALIGN_CENTER);
        document.add(title);
        document.add(new Paragraph(" "));

        Paragraph sessionPara = new Paragraph("Session: " + ctx.getSessionId(), normalFont);
        sessionPara.setAlignment(Paragraph.ALIGN_CENTER);
        document.add(sessionPara);

        Paragraph tsPara = new Paragraph("Generated: " + Instant.now().toString(), normalFont);
        tsPara.setAlignment(Paragraph.ALIGN_CENTER);
        document.add(tsPara);
        document.add(new Paragraph(" "));

        String overallStatus = summary.getOverall() != null ? summary.getOverall().toLowerCase() : "unknown";
        Color verdictColor;
        switch (overallStatus) {
            case "green" -> verdictColor = new Color(0, 153, 0);
            case "red" -> verdictColor = Color.RED;
            default -> verdictColor = new Color(255, 140, 0); // amber
        }
        verdictFont.setColor(verdictColor);
        Paragraph verdict = new Paragraph(overallStatus.toUpperCase(), verdictFont);
        verdict.setAlignment(Paragraph.ALIGN_CENTER);
        document.add(verdict);

        // ---- Page 2: Executive Summary ----
        document.newPage();

        Font headingFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Paragraph execHeading = new Paragraph("Executive Summary", headingFont);
        document.add(execHeading);
        document.add(new Paragraph(" "));

        document.add(new Paragraph("• Obligations: " + summary.getObligationCount(), normalFont));
        document.add(new Paragraph("• Controls: " + summary.getControlCount(), normalFont));
        document.add(new Paragraph("• Gaps identified: " + summary.getGapCount(), normalFont));
        document.add(new Paragraph(" "));

        Paragraph risksHeading = new Paragraph("Top Risks", headingFont);
        document.add(risksHeading);
        document.add(new Paragraph(" "));

        List<String> topRisks = summary.getTopRisks();
        if (topRisks == null || topRisks.isEmpty()) {
            document.add(new Paragraph("(none)", normalFont));
        } else {
            for (String risk : topRisks) {
                document.add(new Paragraph("• " + risk, normalFont));
            }
        }

        // ---- Page 3: Narrative ----
        document.newPage();

        Paragraph narrativeHeading = new Paragraph("Narrative", headingFont);
        document.add(narrativeHeading);
        document.add(new Paragraph(" "));

        String narrativeText = summary.getNarrative() != null ? summary.getNarrative() : "";
        document.add(new Paragraph(narrativeText, normalFont));

        document.close();
        return bos.toByteArray();
    }
}

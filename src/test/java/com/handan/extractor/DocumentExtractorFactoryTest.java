package com.handan.extractor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class DocumentExtractorFactoryTest {

    @Test
    void routesToVectorExtractorWhenTextLayerPresent() throws Exception {
        File tempDir = Files.createTempDirectory("pdf-factory-test-").toFile();
        File pdfFile = new File(tempDir, "vector.pdf");

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                cs.showText("INVOICE AND ORDER NUMBER: PO-1234567890");
                cs.endText();
            }
            doc.save(pdfFile);
        }

        DocumentExtractorFactory factory = new DocumentExtractorFactory();
        DocumentExtractor extractor = factory.getExtractor(pdfFile);

        assertNotNull(extractor);
        assertTrue(extractor instanceof VectorDocumentExtractor, "包含文字层的 PDF 必须路由至 VectorDocumentExtractor");
    }

    @Test
    void routesToScannedExtractorWhenNoTextLayer() throws Exception {
        File tempDir = Files.createTempDirectory("pdf-factory-scan-test-").toFile();
        File pdfFile = new File(tempDir, "empty_scanned.pdf");

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            doc.save(pdfFile);
        }

        DocumentExtractorFactory factory = new DocumentExtractorFactory();
        DocumentExtractor extractor = factory.getExtractor(pdfFile);

        assertNotNull(extractor);
        assertTrue(extractor instanceof ScannedDocumentExtractor, "无文字层的图片/扫描件 PDF 必须路由至 ScannedDocumentExtractor");
    }

    @Test
    void routesToImageExtractorWhenPngFile() throws Exception {
        File tempDir = Files.createTempDirectory("img-factory-test-").toFile();
        File pngFile = new File(tempDir, "sample_order.png");
        Files.write(pngFile.toPath(), new byte[]{1, 2, 3});

        DocumentExtractorFactory factory = new DocumentExtractorFactory();
        DocumentExtractor extractor = factory.getExtractor(pngFile);

        assertNotNull(extractor);
        assertTrue(extractor instanceof ImageDocumentExtractor, "PNG 图片单据必须路由至 ImageDocumentExtractor");
    }
}

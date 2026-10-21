package com.handan.extractor;

import com.handan.config.MappingConfig;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * 纯扫描件/图片型 PDF 文档提取器
 * <p>
 * 先将 PDF 单页高精度（300 DPI）渲染为临时 PNG 图片，再委托通用的 {@link ImageDocumentExtractor}
 * 进行本地 RapidOCR 识别与表格结构化抽取（零重复代码）。
 */
public class ScannedDocumentExtractor implements DocumentExtractor {

    private static final float DPI = 300f;
    private final DocumentExtractor imageExtractor;

    public ScannedDocumentExtractor() {
        this(new ImageDocumentExtractor());
    }

    public ScannedDocumentExtractor(DocumentExtractor imageExtractor) {
        this.imageExtractor = imageExtractor != null ? imageExtractor : new ImageDocumentExtractor();
    }

    @Override
    public ExtractedResult extract(File pdfFile, MappingConfig config) throws Exception {
        if (pdfFile == null || !pdfFile.isFile()) {
            throw new IllegalArgumentException("未找到待处理的 PDF 文件: " + (pdfFile == null ? "null" : pdfFile.getAbsolutePath()));
        }

        File tempImage = Files.createTempFile("ocr-render-", ".png").toFile();
        try {
            // 1. 将 PDF 第一页以 300 DPI 渲染为高精度图片
            try (PDDocument doc = PDDocument.load(pdfFile)) {
                PDFRenderer renderer = new PDFRenderer(doc);
                BufferedImage img = renderer.renderImageWithDPI(0, DPI, ImageType.RGB);
                ImageIO.write(img, "png", tempImage);
                img.flush();
            }

            // 2. 委托图像提取策略执行 OCR 推理与明细解析
            return imageExtractor.extract(tempImage, config);
        } finally {
            Files.deleteIfExists(tempImage.toPath());
        }
    }

    public DocumentExtractor getImageExtractor() {
        return imageExtractor;
    }
}

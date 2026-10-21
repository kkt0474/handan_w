package com.handan.extractor;

import java.io.File;
import org.springframework.stereotype.Component;

/**
 * 单据提取器工厂（Strategy Pattern + Factory Pattern）
 * <p>
 * 封装单据提取策略的实例化与路由选择逻辑：
 * 1. PNG / JPG 图像文件 -> 自动路由至 {@link ImageDocumentExtractor}（RapidOCR 本地推理）
 * 2. 包含文字层的矢量 PDF -> 自动路由至 {@link VectorDocumentExtractor}（Tabula 快速抽取）
 * 3. 扫描件/图片型 PDF -> 自动路由至 {@link ScannedDocumentExtractor}（渲染后委托 ImageDocumentExtractor）
 */
@Component
public class DocumentExtractorFactory {

    private final DocumentExtractor vectorExtractor;
    private final DocumentExtractor scannedExtractor;
    private final DocumentExtractor imageExtractor;

    public DocumentExtractorFactory() {
        ImageDocumentExtractor imgExt = new ImageDocumentExtractor();
        this.vectorExtractor = new VectorDocumentExtractor();
        this.imageExtractor = imgExt;
        this.scannedExtractor = new ScannedDocumentExtractor(imgExt);
    }

    public DocumentExtractorFactory(DocumentExtractor vectorExtractor, DocumentExtractor scannedExtractor) {
        this.vectorExtractor = vectorExtractor != null ? vectorExtractor : new VectorDocumentExtractor();
        this.scannedExtractor = scannedExtractor != null ? scannedExtractor : new ScannedDocumentExtractor();
        if (this.scannedExtractor instanceof ScannedDocumentExtractor sde && sde.getImageExtractor() != null) {
            this.imageExtractor = sde.getImageExtractor();
        } else {
            this.imageExtractor = new ImageDocumentExtractor();
        }
    }

    public DocumentExtractorFactory(DocumentExtractor vectorExtractor, DocumentExtractor scannedExtractor, DocumentExtractor imageExtractor) {
        this.vectorExtractor = vectorExtractor != null ? vectorExtractor : new VectorDocumentExtractor();
        this.scannedExtractor = scannedExtractor != null ? scannedExtractor : new ScannedDocumentExtractor();
        this.imageExtractor = imageExtractor != null ? imageExtractor : new ImageDocumentExtractor();
    }

    /**
     * 根据输入文件格式与底层结构自动探测并获取适用的提取策略
     *
     * @param file 目标单据文件（PDF 或 PNG 等图片）
     * @return 适用的 {@link DocumentExtractor} 策略实例
     * @throws Exception 文件解析或探测异常
     */
    public DocumentExtractor getExtractor(File file) throws Exception {
        if (file == null) {
            throw new IllegalArgumentException("待检测的文件对象不可为空");
        }
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return imageExtractor;
        }
        boolean isVector = PdfSniffer.isVector(file);
        return isVector ? vectorExtractor : scannedExtractor;
    }

    public DocumentExtractor getVectorExtractor() {
        return vectorExtractor;
    }

    public DocumentExtractor getScannedExtractor() {
        return scannedExtractor;
    }

    public DocumentExtractor getImageExtractor() {
        return imageExtractor;
    }
}

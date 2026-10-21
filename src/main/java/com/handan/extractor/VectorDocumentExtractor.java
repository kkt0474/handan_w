package com.handan.extractor;

import com.handan.config.MappingConfig;
import com.handan.transform.MetadataParser;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

/**
 * 电子矢量 PDF 文档提取器（基于 PDFBox 纯文本 + Tabula-java 网格抽取）
 */
public class VectorDocumentExtractor implements DocumentExtractor {

    @Override
    public ExtractedResult extract(File pdfFile, MappingConfig config) throws Exception {
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            // 1. 提取全文纯文本并进行元数据正则匹配
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String fullText = stripper.getText(doc);
            var metadata = MetadataParser.parse(fullText, config);

            // 2. 使用 Tabula 抽取页面表格结构
            List<List<String>> rows = new ArrayList<>();
            try (ObjectExtractor oe = new ObjectExtractor(doc)) {
                Page page = oe.extract(1);
                SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
                List<? extends Table> tables = sea.isTabular(page)
                        ? sea.extract(page)
                        : new BasicExtractionAlgorithm().extract(page);

                // 取行数最多的表格作为主数据表
                Table mainTable = tables.stream()
                        .max(Comparator.comparingInt(t -> t.getRows().size()))
                        .orElse(null);

                if (mainTable != null) {
                    for (List<RectangularTextContainer> tableRow : mainTable.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (RectangularTextContainer cell : tableRow) {
                            String text = cell.getText() == null ? "" : cell.getText().replace("\r", " ").replace("\n", " ").trim();
                            cells.add(text);
                        }
                        rows.add(cells);
                    }
                }
            }
            return new ExtractedResult(metadata, rows);
        }
    }
}

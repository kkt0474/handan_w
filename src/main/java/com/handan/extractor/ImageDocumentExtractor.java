package com.handan.extractor;

import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.TextBlock;
import com.handan.config.MappingConfig;
import com.handan.transform.MetadataParser;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 图像单据提取器（支持 PNG、JPG 等图片格式，基于嵌入式 RapidOCR ONNX 本地推理）
 * <p>
 * 核心能力：
 * 1. 本地调用 RapidOCR ONNX 引擎执行高精度离线字符检测与识别；
 * 2. 垂直几何聚类为物理行，并强制按照水平 X 坐标自左向右精确排序，杜绝列顺序颠倒错乱；
 * 3. 智能定位表格表头行（排除页面顶部/底部非表格元数据干扰）；
 * 4. 几何网格对齐投影（Grid Projection），按表头列中心将单元格投递到唯一物理列，防止空单元格导致列偏移。
 */
public class ImageDocumentExtractor implements DocumentExtractor {

    public record Box(String text, double minX, double maxX, double yc, double h) {
        public double xc() {
            return (minX + maxX) / 2.0;
        }
    }

    @Override
    public ExtractedResult extract(File imageFile, MappingConfig config) throws Exception {
        if (imageFile == null || !imageFile.isFile()) {
            throw new IllegalArgumentException("未找到待处理的图片文件: " + (imageFile == null ? "null" : imageFile.getAbsolutePath()));
        }

        // 1. 本地嵌入式 ONNX OCR 推理
        InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
        OcrResult ocrResult = engine.runOcr(imageFile.getAbsolutePath());

        List<Box> boxes = new ArrayList<>();
        if (ocrResult != null && ocrResult.getTextBlocks() != null) {
            for (TextBlock tb : ocrResult.getTextBlocks()) {
                if (tb.getText() == null || tb.getText().isBlank()) {
                    continue;
                }
                var points = tb.getBoxPoint();
                double minX = Double.MAX_VALUE;
                double maxX = 0;
                double minY = Double.MAX_VALUE;
                double maxY = 0;
                for (var p : points) {
                    minX = Math.min(minX, p.getX());
                    maxX = Math.max(maxX, p.getX());
                    minY = Math.min(minY, p.getY());
                    maxY = Math.max(maxY, p.getY());
                }
                boxes.add(new Box(tb.getText().trim(), minX, maxX, (minY + maxY) / 2.0, maxY - minY));
            }
        }

        // 2. 垂直几何聚类为物理行，并在行内严格按 X 自左向右排序
        List<List<Box>> clusteredLines = groupLines(boxes);

        // 3. 拼装全文用于正则提取 METADATA 元数据
        StringBuilder fullText = new StringBuilder();
        for (List<Box> line : clusteredLines) {
            fullText.append(String.join(" ", line.stream().map(Box::text).toList())).append('\n');
        }
        var metadata = MetadataParser.parse(fullText.toString(), config);

        // 4. 定位表格区域与列对齐抽取
        List<List<String>> rows = extractTableRows(clusteredLines, config);

        return new ExtractedResult(metadata, rows);
    }

    /**
     * 垂直几何聚类算法：将散乱的 OCR 文本块归类到各自物理行，且严格按 X 坐标排序
     */
    private static List<List<Box>> groupLines(List<Box> boxes) {
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(Box::yc));

        List<List<Box>> lines = new ArrayList<>();
        List<Double> lineAvgY = new ArrayList<>();
        List<Double> lineAvgH = new ArrayList<>();

        for (Box b : sorted) {
            int matchIdx = -1;
            for (int i = lines.size() - 1; i >= 0; i--) {
                double avgY = lineAvgY.get(i);
                double avgH = lineAvgH.get(i);
                if (Math.abs(b.yc() - avgY) <= Math.max(avgH, b.h()) * 0.55) {
                    matchIdx = i;
                    break;
                }
            }
            if (matchIdx >= 0) {
                List<Box> line = lines.get(matchIdx);
                line.add(b);
                double newAvgY = (lineAvgY.get(matchIdx) * (line.size() - 1) + b.yc()) / line.size();
                double newAvgH = (lineAvgH.get(matchIdx) * (line.size() - 1) + b.h()) / line.size();
                lineAvgY.set(matchIdx, newAvgY);
                lineAvgH.set(matchIdx, newAvgH);
            } else {
                List<Box> newLine = new ArrayList<>();
                newLine.add(b);
                lines.add(newLine);
                lineAvgY.add(b.yc());
                lineAvgH.add(b.h());
            }
        }

        // 核心关键：每一物理行内部的文本块，必须严格按水平 X 坐标升序排列（从左到右）！
        for (List<Box> line : lines) {
            line.sort(Comparator.comparingDouble(Box::minX));
        }

        return lines;
    }

    /**
     * 识别表格表头，过滤页面顶部/底部无关行，并将明细行数据按表头网格投影对齐
     */
    private static List<List<String>> extractTableRows(List<List<Box>> clusteredLines, MappingConfig config) {
        if (clusteredLines.isEmpty()) {
            return Collections.emptyList();
        }

        int headerIdx = findTableHeaderIndex(clusteredLines);
        List<List<String>> rows = new ArrayList<>();

        if (headerIdx >= 0) {
            List<Box> headerLine = clusteredLines.get(headerIdx);
            List<Double> colCenters = headerLine.stream().map(Box::xc).toList();

            // 1. 添加表头行自身（供下游 MetadataParser.isHeaderRow 识别与消费）
            rows.add(headerLine.stream().map(Box::text).toList());

            // 2. 从表头之后开始提取真实数据行
            for (int i = headerIdx + 1; i < clusteredLines.size(); i++) {
                List<Box> line = clusteredLines.get(i);
                if (line.isEmpty()) {
                    continue;
                }

                // 排除页脚小计/合计/签名行
                String firstText = line.get(0).text().replaceAll("\\s+", "").toLowerCase();
                if (firstText.contains("合计") || firstText.contains("总计") || firstText.contains("total")
                        || firstText.contains("签字") || firstText.contains("approved")) {
                    break;
                }

                // 若文本块极少且明显不是明细行，则跳过
                if (line.size() < Math.min(2, colCenters.size())) {
                    continue;
                }

                // 按表头列中心投影到对应的列位置，空单元格补空字符串，保证绝对对齐
                List<String> rowCells = new ArrayList<>(Collections.nCopies(colCenters.size(), ""));
                for (Box b : line) {
                    int col = findNearestColumn(b.xc(), colCenters);
                    String existing = rowCells.get(col);
                    rowCells.set(col, existing.isEmpty() ? b.text() : existing + " " + b.text());
                }
                rows.add(rowCells);
            }
        } else {
            // 兜底方案：若未明确检测出标准表头，保留多列特征行并按从左向右顺序输出
            for (List<Box> line : clusteredLines) {
                if (line.size() < 2) {
                    continue;
                }
                rows.add(line.stream().map(Box::text).toList());
            }
        }

        return rows;
    }

    private static int findTableHeaderIndex(List<List<Box>> lines) {
        int bestIdx = -1;
        int maxScore = 0;
        for (int i = 0; i < lines.size(); i++) {
            List<String> texts = lines.get(i).stream().map(Box::text).toList();
            int score = countHeaderScore(texts);
            if (score > maxScore && score >= 2) {
                maxScore = score;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static int countHeaderScore(List<String> texts) {
        if (texts.size() < 3) {
            // 表格表头至少要有 3 列以上，排除页面顶部的单条或双条键值对元数据行（如“订单编号: xxx 日期: yyy”）
            return 0;
        }
        int score = 0;
        for (String t : texts) {
            String lower = t.toLowerCase().replaceAll("\\s+", "");
            // 核心表格明细列关键词加权
            if (lower.contains("料号") || lower.contains("item") || lower.contains("part") || lower.contains("p/n") || lower.contains("pn")) {
                score += 3;
            } else if (lower.contains("数量") || lower.contains("qty") || lower.contains("quantity") || lower.contains("pcs")) {
                score += 3;
            } else if (lower.contains("单价") || lower.contains("price") || lower.contains("u/p")) {
                score += 3;
            } else if (lower.contains("金额") || lower.contains("amount")) {
                score += 2;
            } else if (lower.contains("交期") || lower.contains("delivery")) {
                score += 2;
            } else if (lower.contains("日期") || lower.contains("date")) {
                score += 1;
            } else if (lower.contains("客户") || lower.contains("customer")) {
                score += 1;
            }
        }
        return score;
    }

    private static int findNearestColumn(double x, List<Double> colCenters) {
        int bestCol = 0;
        double minDiff = Double.MAX_VALUE;
        for (int c = 0; c < colCenters.size(); c++) {
            double diff = Math.abs(x - colCenters.get(c));
            if (diff < minDiff) {
                minDiff = diff;
                bestCol = c;
            }
        }
        return bestCol;
    }
}

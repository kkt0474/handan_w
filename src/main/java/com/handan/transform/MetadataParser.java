package com.handan.transform;

import com.handan.config.ColumnMapping;
import com.handan.config.MappingConfig;
import com.handan.config.SourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本抽取辅助类：正则解析与表头行识别
 */
public final class MetadataParser {

    private MetadataParser() {}

    /**
     * 基于 mapping 配置中的正则从文本中匹配 METADATA 字段
     */
    public static Map<String, String> parse(String text, MappingConfig config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null) {
            text = "";
        }
        for (ColumnMapping c : config.columns()) {
            if (c.sourceType() != SourceType.METADATA) {
                continue;
            }
            String value = c.defaultOrEmpty();
            if (c.pattern() != null && !c.pattern().isBlank()) {
                Matcher m = Pattern.compile(c.pattern()).matcher(text);
                if (m.find()) {
                    value = (m.groupCount() >= 1 ? m.group(1) : m.group()).trim();
                }
            }
            result.put(c.excelHeader(), value);
        }
        return result;
    }

    /**
     * 判断当前表格行是否为重复的表头行（如果是表头行，转换时应跳过）
     */
    public static boolean isHeaderRow(List<String> row, MappingConfig config) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        List<String> expectedHeaders = config.columns().stream()
                .filter(c -> c.sourceType() == SourceType.TABLE)
                .map(ColumnMapping::excelHeader)
                .toList();

        int needHits = Math.min(2, expectedHeaders.size());
        if (needHits == 0) {
            return false;
        }

        int hits = 0;
        for (String cell : row) {
            if (cell == null) continue;
            String cleanCell = cell.replaceAll("\\s+", "").toLowerCase();
            if (cleanCell.length() < 2) continue;

            for (String expected : expectedHeaders) {
                String cleanExpected = expected.replaceAll("\\s+", "").toLowerCase();
                if (cleanCell.contains(cleanExpected) || cleanExpected.contains(cleanCell) || isSynonymMatch(cleanCell, cleanExpected)) {
                    hits++;
                    break;
                }
            }
        }
        return hits >= needHits;
    }

    private static boolean isSynonymMatch(String cell, String expected) {
        if (expected.contains("料号") && (cell.contains("item") || cell.contains("part") || cell.contains("p/n") || cell.contains("pn"))) {
            return true;
        }
        if (expected.contains("单价") && (cell.contains("price") || cell.contains("u/p"))) {
            return true;
        }
        if (expected.contains("数量") && (cell.contains("qty") || cell.contains("quantity") || cell.contains("pcs"))) {
            return true;
        }
        if (expected.contains("金额") && (cell.contains("amount") || cell.contains("total"))) {
            return true;
        }
        if (expected.contains("交期") && (cell.contains("delivery") || cell.contains("date"))) {
            return true;
        }
        return false;
    }
}

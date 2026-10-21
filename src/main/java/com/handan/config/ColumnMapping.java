package com.handan.config;

/**
 * 单列映射定义
 *
 * @param excelHeader 目标 Excel 表头名称
 * @param sourceType 数据来源类型（METADATA 或 TABLE）
 * @param pattern 当 sourceType=METADATA 时的抽取正则表达式
 * @param tableColumnIndex 当 sourceType=TABLE 时在 PDF 表格中对应的列索引（从 0 开始）
 * @param defaultValue 缺失时的默认填充值
 */
public record ColumnMapping(
        String excelHeader,
        SourceType sourceType,
        String pattern,
        Integer tableColumnIndex,
        String defaultValue
) {
    public String defaultOrEmpty() {
        return defaultValue == null ? "" : defaultValue;
    }
}

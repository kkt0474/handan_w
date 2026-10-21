package com.handan.config;

/**
 * 数据源类型
 */
public enum SourceType {
    /** 来源于文本行中的 Key-Value 正则抽取（常量列，行级复制） */
    METADATA,
    /** 来源于 PDF 表格中的指定列 */
    TABLE
}

package com.handan.extractor;

import java.util.List;
import java.util.Map;

/**
 * PDF 提取出的中间结果模型
 *
 * @param metadata 抽取的 Key-Value 元数据（key=excelHeader, value=提取的文本）
 * @param tableData 抽取的二维表格网格（行列表，每行为单元格文本列表）
 */
public record ExtractedResult(
        Map<String, String> metadata,
        List<List<String>> tableData
) {}

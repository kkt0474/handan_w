package com.handan.transform;

import com.handan.config.ColumnMapping;
import com.handan.config.MappingConfig;
import com.handan.config.SourceType;
import com.handan.extractor.ExtractedResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 宽表数据转换与扁平化组装器
 * - 固定列结构：
 *   第 1 列：【日期】（格式为 yyyy/M/d，如 2026/8/26）
 *   第 2 列：【客户】（来自前端必填输入，整单行复制）
 *   第 3 列：【订单号】（来自前端必填输入，整单行复制）
 *   后续列：从 mapping-config.yaml 声明的 PDF 表格/文本中抽取
 */
public final class RowFlattener {

    public static final String DATE_HEADER = "日期";
    public static final String CUSTOMER_HEADER = "客户";
    public static final String ORDER_NO_HEADER = "订单号";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d");

    private RowFlattener() {}

    /**
     * 获取完整的目标表头列表
     */
    public static List<String> getFullHeaders(MappingConfig config) {
        List<String> headers = new ArrayList<>();
        headers.add(DATE_HEADER);
        headers.add(CUSTOMER_HEADER);
        headers.add(ORDER_NO_HEADER);
        for (ColumnMapping col : config.columns()) {
            headers.add(col.excelHeader());
        }
        return headers;
    }

    /**
     * 将从 PDF 抽取的数据与前端输入的客户、订单号合并为宽表行数据
     */
    public static List<List<Object>> flatten(ExtractedResult result, MappingConfig config, String customer, String orderNo) {
        return flatten(result, config, customer, orderNo, LocalDate.now());
    }

    public static List<List<Object>> flatten(ExtractedResult result, MappingConfig config, String customer, String orderNo, LocalDate executionDate) {
        String formattedDate = executionDate.format(DATE_FORMATTER);
        List<List<Object>> outputRows = new ArrayList<>();

        if (result == null || result.tableData() == null) {
            return outputRows;
        }

        String safeCustomer = customer != null ? customer.trim() : "";
        String safeOrderNo = orderNo != null ? orderNo.trim() : "";

        for (List<String> rawRow : result.tableData()) {
            // 过滤可能包含的表头行
            if (MetadataParser.isHeaderRow(rawRow, config)) {
                continue;
            }

            // 过滤全空白行
            boolean isAllBlank = rawRow.stream().allMatch(c -> c == null || c.isBlank());
            if (isAllBlank) {
                continue;
            }

            List<Object> combinedRow = new ArrayList<>();

            // 1. 第 1 列：执行日期
            combinedRow.add(formattedDate);
            // 2. 第 2 列：前端输入的客户
            combinedRow.add(safeCustomer);
            // 3. 第 3 列：前端输入的订单号
            combinedRow.add(safeOrderNo);

            // 4. 后续列：根据 mapping 配置从 PDF 表格或元数据中提取
            for (ColumnMapping col : config.columns()) {
                if (col.sourceType() == SourceType.METADATA) {
                    String val = result.metadata() != null
                            ? result.metadata().getOrDefault(col.excelHeader(), col.defaultOrEmpty())
                            : col.defaultOrEmpty();
                    combinedRow.add(val);
                } else {
                    int idx = col.tableColumnIndex() != null ? col.tableColumnIndex() : -1;
                    String val = "";
                    if (idx >= 0 && idx < rawRow.size() && rawRow.get(idx) != null) {
                        val = rawRow.get(idx);
                    }
                    combinedRow.add(val);
                }
            }

            outputRows.add(combinedRow);
        }

        return outputRows;
    }
}

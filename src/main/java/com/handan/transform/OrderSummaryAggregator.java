package com.handan.transform;

import com.handan.config.ColumnMapping;
import com.handan.config.MappingConfig;
import com.handan.extractor.ExtractedResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单汇总聚合器
 * - 针对从 PDF 提取的数据，以【料号】为维度进行聚合（Group By 料号）
 * - 对【数量(PCS)】字段进行求和累加，以整数格式沉淀
 * - 自动纠正 OCR 误将千分位逗号识别为句号的场景（如 40.000 -> 40000）
 * - 生成固定 6 列结构：
 *   1. 客户 (前台输入)
 *   2. 订单号 (前台输入)
 *   3. 料号 (PDF提取)
 *   4. 总数量 (求和)
 *   5. 出货数量 (固定默认值 "-")
 *   6. 未出货数量 (值与总数量一致)
 */
public final class OrderSummaryAggregator {

    public static final String SUMMARY_SHEET_NAME = "客户订单汇总";

    public static final List<String> SUMMARY_HEADERS = List.of(
            "客户",
            "订单号",
            "料号",
            "总数量",
            "出货数量",
            "未出货数量"
    );

    public static final String DEFAULT_SHIPPED_QTY = "-";

    private OrderSummaryAggregator() {}

    /**
     * 将从 PDF 抽取的数据按料号聚合汇总
     *
     * @param result PDF 抽取结果
     * @param config 字段映射配置（用于定位“料号”和“数量(PCS)”在表格中的列索引）
     * @param customer 前台输入的客户
     * @param orderNo 前台输入的订单号
     * @return 6 列汇总行数据列表
     */
    public static List<List<Object>> aggregate(ExtractedResult result, MappingConfig config, String customer, String orderNo) {
        List<List<Object>> summaryRows = new ArrayList<>();
        if (result == null || result.tableData() == null || result.tableData().isEmpty()) {
            return summaryRows;
        }

        // 1. 在 mapping-config.yaml 中查找“料号”和“数量(PCS)”对应的表格列索引
        int partNoColIndex = -1;
        int qtyColIndex = -1;

        for (ColumnMapping col : config.columns()) {
            String header = col.excelHeader();
            if (header == null) continue;
            if (header.contains("料号")) {
                partNoColIndex = col.tableColumnIndex() != null ? col.tableColumnIndex() : -1;
            } else if (header.contains("数量")) {
                qtyColIndex = col.tableColumnIndex() != null ? col.tableColumnIndex() : -1;
            }
        }

        // 如果未找到对应列，则无法进行料号维度的聚合
        if (partNoColIndex < 0 || qtyColIndex < 0) {
            return summaryRows;
        }

        String safeCustomer = customer != null ? customer.trim() : "";
        String safeOrderNo = orderNo != null ? orderNo.trim() : "";

        // 2. 以料号为维度分组，保持原始出现顺序（LinkedHashMap）
        Map<String, BigDecimal> partNoQtyMap = new LinkedHashMap<>();

        for (List<String> rawRow : result.tableData()) {
            // 过滤重复表头与空行
            if (MetadataParser.isHeaderRow(rawRow, config)) {
                continue;
            }
            boolean isAllBlank = rawRow.stream().allMatch(c -> c == null || c.isBlank());
            if (isAllBlank) {
                continue;
            }

            // 获取料号
            String partNo = "";
            if (partNoColIndex < rawRow.size() && rawRow.get(partNoColIndex) != null) {
                partNo = rawRow.get(partNoColIndex).trim();
            }

            // 料号为空则跳过
            if (partNo.isEmpty()) {
                continue;
            }

            // 获取数量并清洗为数值
            String qtyStr = "";
            if (qtyColIndex < rawRow.size() && rawRow.get(qtyColIndex) != null) {
                qtyStr = rawRow.get(qtyColIndex).trim();
            }
            BigDecimal qty = parseQuantity(qtyStr);

            partNoQtyMap.merge(partNo, qty, BigDecimal::add);
        }

        // 3. 构造 6 列输出结构
        for (Map.Entry<String, BigDecimal> entry : partNoQtyMap.entrySet()) {
            String partNo = entry.getKey();
            // 数量格式化为整数格式（如 40000）
            long totalQty = entry.getValue().setScale(0, java.math.RoundingMode.HALF_UP).longValue();

            List<Object> row = new ArrayList<>();
            row.add(safeCustomer);                       // 1. 客户
            row.add(safeOrderNo);                        // 2. 订单号
            row.add(partNo);                             // 3. 料号
            row.add(totalQty);                           // 4. 总数量 (整数)
            row.add(DEFAULT_SHIPPED_QTY);                // 5. 出货数量 (固定 "-")
            row.add(totalQty);                           // 6. 未出货数量 (和总数量一致)

            summaryRows.add(row);
        }

        return summaryRows;
    }

    /**
     * 将数量文本转换为 BigDecimal，支持千分位逗号（如 "1,200" -> 1200）
     * 以及自动纠正 OCR 误把逗号识别为点号的场景（如 "40.000" -> 40000）
     */
    private static BigDecimal parseQuantity(String text) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }
        // 去除千分位逗号、空格以及常见的货币/单位字符
        String clean = text.replaceAll("[,，\\s]", "");

        // 如果是 OCR 误把千分位识别为句点（点号后面恰好是 3 位数字，如 40.000）
        if (clean.matches("^-?\\d+(\\.\\d{3})+$")) {
            clean = clean.replace(".", "");
        }

        try {
            return new BigDecimal(clean);
        } catch (Exception e) {
            // 若包含非数字文本，尝试提取其中的数字部分
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?").matcher(clean);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group());
                } catch (Exception ignored) {}
            }
            return BigDecimal.ZERO;
        }
    }
}

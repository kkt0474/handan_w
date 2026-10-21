package com.handan.purchase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * 采购源文件读取器
 * 支持读取 source.xlsx 与 source.xls 文件
 * 解析表头并仅抽取 No 列为阿拉伯数字的明细条目
 */
public class PurchaseReader {

    private static final DataFormatter DATA_FORMATTER = new DataFormatter();
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd");

    public static final String COL_NO = "no";
    public static final String COL_PN = "pn";
    public static final String COL_BRAND = "brand";
    public static final String COL_QTY = "qty";
    public static final String COL_UNIT = "unit";
    public static final String COL_UP = "up";
    public static final String COL_AMOUNT = "amount";
    public static final String COL_DELIVERY = "delivery";
    public static final String COL_NOTE = "note";

    /**
     * 读取指定采购源 Excel 文件（source.xls 或 source.xlsx）
     *
     * @param sourceFile source.xls 或 source.xlsx 文件
     * @return 抽取的有效物料行数据列表
     * @throws IOException IO 或格式解析异常
     */
    public List<PurchaseItem> readSourceFile(File sourceFile) throws IOException {
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            throw new IllegalArgumentException("未找到采购源文件: " + sourceFile.getAbsolutePath());
        }

        try (FileInputStream fis = new FileInputStream(sourceFile);
             Workbook workbook = WorkbookFactory.create(fis)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalStateException("采购源文件工作表为空: " + sourceFile.getName());
            }

            // 获取第 1 个 sheet 页
            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            // 1. 扫描定位表头所在行
            int headerRowIndex = -1;
            Map<String, Integer> colIndexMap = new HashMap<>();

            for (int r = 0; r <= Math.min(15, sheet.getLastRowNum()); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Integer> matched = tryMatchHeader(row);
                if (matched.containsKey(COL_NO) && matched.containsKey(COL_PN)) {
                    headerRowIndex = r;
                    colIndexMap = matched;
                    break;
                }
            }

            if (headerRowIndex < 0) {
                throw new IllegalStateException("【采购源文件格式错误 ❌】在文件【" + sourceFile.getName()
                        + "】中未找到包含 No 与 P/N 的标准表头行。请检查是否误选了目标母表或其他非采购单据。");
            }

            // 2. 逐行提取 No 列为阿拉伯数字的条目
            List<PurchaseItem> items = new ArrayList<>();
            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Integer noCol = colIndexMap.get(COL_NO);
                if (noCol == null || noCol >= row.getLastCellNum()) continue;

                Cell noCell = row.getCell(noCol);
                Integer parsedNo = parseArabicNumber(noCell);
                if (parsedNo == null) {
                    // 非阿拉伯数字（如说明文字、小计、空行），跳过
                    continue;
                }

                String pn = getCellString(row, colIndexMap.get(COL_PN));
                String brand = getCellString(row, colIndexMap.get(COL_BRAND));

                String rawQty = getCellString(row, colIndexMap.get(COL_QTY));
                BigDecimal qty = parseNumericValue(rawQty, true);

                String unit = getCellString(row, colIndexMap.get(COL_UNIT));

                String rawPrice = getCellString(row, colIndexMap.get(COL_UP));
                BigDecimal price = parseNumericValue(rawPrice, false);

                Cell amountCell = colIndexMap.get(COL_AMOUNT) != null ? row.getCell(colIndexMap.get(COL_AMOUNT)) : null;
                BigDecimal amount = parseAmountFromCell(evaluator, amountCell);
                String rawAmount = amount != null ? amount.toPlainString() : getCellString(row, colIndexMap.get(COL_AMOUNT));

                String deliveryDate = getCellDateOrString(row, colIndexMap.get(COL_DELIVERY));
                String note = getCellString(row, colIndexMap.get(COL_NOTE));

                items.add(new PurchaseItem(
                        parsedNo,
                        pn,
                        brand,
                        qty,
                        rawQty,
                        unit,
                        price,
                        rawPrice,
                        amount,
                        rawAmount,
                        deliveryDate,
                        note
                ));
            }

            return items;
        }
    }

    /**
     * 智能匹配表头列名
     */
    private Map<String, Integer> tryMatchHeader(Row row) {
        Map<String, Integer> map = new HashMap<>();
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null) continue;
            String text = DATA_FORMATTER.formatCellValue(cell).trim();
            if (text.isEmpty()) continue;
            String lower = text.toLowerCase();

            if (lower.equals("no") || lower.equals("no.") || lower.equals("序号")) {
                map.put(COL_NO, c);
            } else if (lower.equals("p/n") || lower.equals("pn") || lower.contains("料号") || lower.contains("part no")) {
                map.put(COL_PN, c);
            } else if (lower.contains("brand") || lower.contains("品牌")) {
                map.put(COL_BRAND, c);
            } else if (lower.contains("q'ty") || lower.contains("qty") || lower.contains("数量")) {
                map.put(COL_QTY, c);
            } else if (lower.equals("unit") || lower.contains("单位")) {
                map.put(COL_UNIT, c);
            } else if (lower.contains("u/p") || lower.contains("unit price") || lower.contains("单价")) {
                map.put(COL_UP, c);
            } else if (lower.contains("amount") || lower.contains("金额")) {
                map.put(COL_AMOUNT, c);
            } else if (lower.contains("delivery") || lower.contains("交期")) {
                map.put(COL_DELIVERY, c);
            } else if (lower.contains("note") || lower.contains("备注")) {
                map.put(COL_NOTE, c);
            }
        }
        return map;
    }

    /**
     * 解析单元格为阿拉伯正整数，若不是则返回 null
     */
    private Integer parseArabicNumber(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double val = cell.getNumericCellValue();
                if (val > 0 && Math.floor(val) == val) {
                    return (int) val;
                }
            } else {
                String str = DATA_FORMATTER.formatCellValue(cell).trim();
                if (str.matches("^\\d+$")) {
                    int val = Integer.parseInt(str);
                    if (val > 0) return val;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getCellString(Row row, Integer colIndex) {
        if (colIndex == null || colIndex < 0 || colIndex >= row.getLastCellNum()) {
            return "";
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) return "";
        return DATA_FORMATTER.formatCellValue(cell).trim();
    }

    /**
     * 读取日期类型或文本单元格
     */
    private String getCellDateOrString(Row row, Integer colIndex) {
        if (colIndex == null || colIndex < 0 || colIndex >= row.getLastCellNum()) {
            return "";
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) return "";

        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                Date date = cell.getDateCellValue();
                if (date != null) {
                    return DATE_FORMAT.format(date);
                }
            }
        } catch (Exception ignored) {}

        return DATA_FORMATTER.formatCellValue(cell).trim();
    }

    /**
     * 数值安全转换（兼容逗号千分位、货币符号与点号千分位误纠偏）
     */
    private BigDecimal parseNumericValue(String text, boolean isQuantity) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }
        String clean = text.replaceAll("[,，\\s$￥USDusd]", "");
        if (isQuantity) {
            clean = clean.replaceAll("(?i)(pcs|pc|个|件|箱|套|支|只)$", "").trim();
            // 针对数量的点号千分位纠偏（如 40.000 -> 40000）
            if (clean.matches("^-?\\d+(\\.\\d{3})+$")) {
                clean = clean.replace(".", "");
            }
        }
        try {
            return new BigDecimal(clean);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 解析金额单元格，支持数字、公式计算求值及字符串类型
     */
    private BigDecimal parseAmountFromCell(FormulaEvaluator evaluator, Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            }
            if (cell.getCellType() == CellType.FORMULA) {
                if (evaluator != null) {
                    try {
                        CellValue cv = evaluator.evaluate(cell);
                        if (cv != null && cv.getCellType() == CellType.NUMERIC) {
                            return BigDecimal.valueOf(cv.getNumberValue()).setScale(2, RoundingMode.HALF_UP);
                        } else if (cv != null && cv.getCellType() == CellType.STRING) {
                            String s = cv.getStringValue();
                            if (s != null && !s.isBlank()) {
                                return parseNumericValue(s, false).setScale(2, RoundingMode.HALF_UP);
                            }
                        }
                    } catch (Exception ignored) {
                        // 公式计算失败时回退至读取缓存值
                    }
                }
                try {
                    return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
                } catch (Exception ignored) {}
            }
            if (cell.getCellType() == CellType.STRING) {
                String str = cell.getStringCellValue();
                if (str != null && !str.isBlank()) {
                    return parseNumericValue(str, false).setScale(2, RoundingMode.HALF_UP);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}

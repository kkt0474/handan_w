package com.handan.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handan.excel.ExcelAppendService;
import com.handan.progress.ProgressListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class PurchaseModuleTest {

    @Test
    void testPurchaseReaderAndAggregatorAndValidation() throws Exception {
        File tempDir = Files.createTempDirectory("purchase-test-").toFile();
        File sourceFile = new File(tempDir, "source.xlsx");

        // 1. 创建模拟 source.xlsx 文件
        // 表头：["No","P/N","Brand","Q'TY","Unit","U/P(USD)","Amount(USD)","Delivery Date","NOTE备注"]
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(sourceFile)) {
            Sheet sheet = wb.createSheet("Sheet1");

            // 标题行（无关内容，用于验证跳过）
            Row rTitle = sheet.createRow(0);
            rTitle.createCell(0).setCellValue("PURCHASE ORDER SOURCE DATA");

            // 表头行（第 1 行）
            Row hRow = sheet.createRow(1);
            hRow.createCell(0).setCellValue("No");
            hRow.createCell(1).setCellValue("P/N");
            hRow.createCell(2).setCellValue("Brand");
            hRow.createCell(3).setCellValue("Q'TY");
            hRow.createCell(4).setCellValue("Unit");
            hRow.createCell(5).setCellValue("U/P(USD)");
            hRow.createCell(6).setCellValue("Amount(USD)");
            hRow.createCell(7).setCellValue("Delivery Date");
            hRow.createCell(8).setCellValue("NOTE备注");

            // 数据行 1: No=1, P/N=ITEM-A, Q'TY=1000, U/P=2.5000, Amount=2500.00
            Row d1 = sheet.createRow(2);
            d1.createCell(0).setCellValue(1);
            d1.createCell(1).setCellValue("ITEM-A");
            d1.createCell(2).setCellValue("TI");
            d1.createCell(3).setCellValue("1,000");
            d1.createCell(4).setCellValue("PCS");
            d1.createCell(5).setCellValue("2.5000");
            d1.createCell(6).setCellValue("2500.00");
            d1.createCell(7).setCellValue("2026/11/01");
            d1.createCell(8).setCellValue("原装正品");

            // 数据行 2: No=2, P/N=ITEM-B, Q'TY=40000 (OCR点号测试 40.000), U/P=0.4400, Amount=17600.00
            Row d2 = sheet.createRow(3);
            d2.createCell(0).setCellValue("2");
            d2.createCell(1).setCellValue("ITEM-B");
            d2.createCell(2).setCellValue("ST");
            d2.createCell(3).setCellValue("40.000");
            d2.createCell(4).setCellValue("PCS");
            d2.createCell(5).setCellValue("0.4400");
            d2.createCell(6).setCellValue("17600.00");
            d2.createCell(7).setCellValue("2026/11/05");
            d2.createCell(8).setCellValue("需卷带");

            // 数据行 3: No=3, P/N=ITEM-A (同一个料号，测试汇总聚合), Q'TY=2000, U/P=2.5000, Amount=5000.00
            Row d3 = sheet.createRow(4);
            d3.createCell(0).setCellValue(3);
            d3.createCell(1).setCellValue("ITEM-A");
            d3.createCell(2).setCellValue("TI");
            d3.createCell(3).setCellValue(2000);
            d3.createCell(4).setCellValue("PCS");
            d3.createCell(5).setCellValue(2.50);
            d3.createCell(6).setCellValue(5000);
            d3.createCell(7).setCellValue("2026/11/10");
            d3.createCell(8).setCellValue("加急");

            // 无关行 4: 合计行，No="Total"（应被自动忽略）
            Row d4 = sheet.createRow(5);
            d4.createCell(0).setCellValue("Total");
            d4.createCell(3).setCellValue("43000");
            d4.createCell(6).setCellValue("25100.00");

            wb.write(fos);
        }

        // 2. 测试 PurchaseReader 解析
        PurchaseReader reader = new PurchaseReader();
        List<PurchaseItem> items = reader.readSourceFile(sourceFile);
        assertEquals(3, items.size(), "应提取 3 行 No 为纯数字的条目（过滤 Total 行）");

        assertEquals(1, items.get(0).no());
        assertEquals("ITEM-A", items.get(0).pn());
        assertEquals(0, new BigDecimal("1000").compareTo(items.get(0).quantity()));
        assertEquals(0, new BigDecimal("2.5000").compareTo(items.get(0).unitPrice()));
        assertEquals("原装正品", items.get(0).note());

        // 验证点号千分位纠偏 40.000 -> 40000
        assertEquals(0, new BigDecimal("40000").compareTo(items.get(1).quantity()));

        // 3. 测试 PurchaseSummaryAggregator
        List<List<Object>> summaryRows = PurchaseSummaryAggregator.aggregate(items, "供应商XYZ", "PO-2026001");
        assertEquals(2, summaryRows.size(), "ITEM-A 与 ITEM-B 共 2 个料号");

        // ITEM-A: 1000 + 2000 = 3000
        var rowA = summaryRows.get(0);
        assertEquals("供应商XYZ", rowA.get(0));
        assertEquals("PO-2026001", rowA.get(1));
        assertEquals("ITEM-A", rowA.get(2));
        assertEquals(3000L, rowA.get(3));
        assertEquals("-", rowA.get(4));
        assertEquals(3000L, rowA.get(5));

        // ITEM-B: 40000
        var rowB = summaryRows.get(1);
        assertEquals("ITEM-B", rowB.get(2));
        assertEquals(40000L, rowB.get(3));
        assertEquals("-", rowB.get(4));
        assertEquals(40000L, rowB.get(5));

        // 4. 测试 PurchaseValidator 校验：
        // 总数量预期：1000 + 40000 + 2000 = 43,000
        // 总金额预期：1000*2.5 + 40000*0.44 + 2000*2.5 = 2500 + 17600 + 5000 = 25,100.00
        var vPass = PurchaseValidator.validate(items, summaryRows, "43000", "25100.00", ProgressListener.console());
        assertTrue(vPass.passed(), "数量与金额均一致，校验应通过");
        assertTrue(vPass.triggered());

        // 测试校验不通过场景：输入数量为 40000，相差 3000
        var vFail = PurchaseValidator.validate(items, summaryRows, "40000", "25100.00", ProgressListener.console());
        assertFalse(vFail.passed(), "数量不一致，校验应不通过");
        assertTrue(vFail.summaryNotice().contains("未通过"));

        // 5. 测试整体 PurchaseProcessor 与 Excel 备份及写入
        File resultFile = new File(tempDir, "result.xlsx");
        try (Workbook rWb = new XSSFWorkbook();
             FileOutputStream rFos = new FileOutputStream(resultFile)) {
            Sheet rS = rWb.createSheet("现有表");
            rS.createRow(0).createCell(0).setCellValue("历史数据");
            rWb.write(rFos);
        }

        ExcelAppendService excelAppendService = new ExcelAppendService();
        PurchaseProcessor processor = new PurchaseProcessor(excelAppendService);

        File finalModified = processor.process(
                tempDir,
                "2026/10/14",
                "供应商XYZ",
                "PO-2026001",
                "43000",
                "25100.00",
                ProgressListener.console()
        );

        assertNotNull(finalModified);
        assertEquals(resultFile.getAbsolutePath(), finalModified.getAbsolutePath());

        // 检查备份文件已生成
        File[] backups = tempDir.listFiles((dir, name) -> name.matches("result_\\d{14}\\.xlsx"));
        assertTrue(backups != null && backups.length >= 1, "必须生成 result 时间戳备份文件");

        // 检查采购单及采购单汇总 sheet 数据及美式格式化
        DataFormatter df = new DataFormatter();
        try (FileInputStream fis = new FileInputStream(resultFile);
             Workbook wb = new XSSFWorkbook(fis)) {
            Sheet pSheet = wb.getSheet("采购单");
            assertNotNull(pSheet, "必须包含【采购单】Sheet");
            assertEquals(3, pSheet.getLastRowNum(), "表头(0) + 3 行数据 = 3");

            // 检查表头
            Row pHeader = pSheet.getRow(0);
            assertEquals("日期", pHeader.getCell(0).getStringCellValue());
            assertEquals("供应商", pHeader.getCell(1).getStringCellValue());
            assertEquals("采购单号", pHeader.getCell(2).getStringCellValue());
            assertEquals("料号", pHeader.getCell(3).getStringCellValue());
            assertEquals("单价(USD)", pHeader.getCell(4).getStringCellValue());
            assertEquals("数量(PCS)", pHeader.getCell(5).getStringCellValue());
            assertEquals("金额", pHeader.getCell(6).getStringCellValue());
            assertEquals("交期", pHeader.getCell(7).getStringCellValue());
            assertEquals("备注", pHeader.getCell(8).getStringCellValue());

            // 检查第 2 条数据（40,000 PCS，单价 0.4400）的美式千分位格式
            Row pRow2 = pSheet.getRow(2);
            assertEquals("2026/10/14", pRow2.getCell(0).getStringCellValue());
            assertEquals("供应商XYZ", pRow2.getCell(1).getStringCellValue());
            assertEquals("PO-2026001", pRow2.getCell(2).getStringCellValue());
            assertEquals("ITEM-B", pRow2.getCell(3).getStringCellValue());
            assertEquals("0.4400", df.formatCellValue(pRow2.getCell(4)));
            assertEquals("40,000", df.formatCellValue(pRow2.getCell(5)));
            assertEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, pRow2.getCell(6).getCellType());
            assertEquals("E3*F3", pRow2.getCell(6).getCellFormula());
            var eval = wb.getCreationHelper().createFormulaEvaluator();
            assertEquals("17,600.00", df.formatCellValue(pRow2.getCell(6), eval));
            assertEquals("2026/11/05", pRow2.getCell(7).getStringCellValue());
            assertEquals("需卷带", pRow2.getCell(8).getStringCellValue());

            Sheet sSheet = wb.getSheet("采购单汇总");
            assertNotNull(sSheet, "必须包含【采购单汇总】Sheet");
            assertEquals(2, sSheet.getLastRowNum(), "表头(0) + 2 行汇总 = 2");

            Row sRow1 = sSheet.getRow(1);
            assertEquals("ITEM-A", sRow1.getCell(2).getStringCellValue());
            assertEquals("3,000", df.formatCellValue(sRow1.getCell(3)));
            assertEquals("-", sRow1.getCell(4).getStringCellValue());
            assertEquals("3,000", df.formatCellValue(sRow1.getCell(5)));
        }
    }

    @Test
    void testAmountMismatchAndFormulaEvaluationFromSource() throws Exception {
        File tempDir = Files.createTempDirectory("purchase-mismatch-test-").toFile();
        File sourceFile = new File(tempDir, "source.xlsx");
        File resultFile = new File(tempDir, "result.xlsx");

        // 1. 模拟模板 result.xlsx
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(resultFile)) {
            Sheet sp = wb.createSheet("采购单");
            var h = sp.createRow(0);
            List<String> headers = List.of("日期", "供应商", "采购单号", "料号", "单价(USD)", "数量(PCS)", "金额", "交期", "备注");
            for (int i = 0; i < headers.size(); i++) {
                h.createCell(i).setCellValue(headers.get(i));
            }

            Sheet ss = wb.createSheet("采购单汇总");
            var sh = ss.createRow(0);
            List<String> sHeaders = PurchaseSummaryAggregator.SUMMARY_HEADERS;
            for (int i = 0; i < sHeaders.size(); i++) {
                sh.createCell(i).setCellValue(sHeaders.get(i));
            }
            wb.write(fos);
        }

        // 2. 模拟 source.xlsx
        // 行 1: QTY=1000, UP=2.5, Amount 由公式 =D3*F3 得到 -> 计算求值为 2500.00，与计算金额 2500.00 一致（正常，不标红）
        // 行 2: QTY=500, UP=10.0, 但 source 中填写的 Amount 为 4000.00 -> 与计算金额 5000.00 不一致（标红）
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(sourceFile)) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row hRow = sheet.createRow(1);
            hRow.createCell(0).setCellValue("No");
            hRow.createCell(1).setCellValue("P/N");
            hRow.createCell(2).setCellValue("Brand");
            hRow.createCell(3).setCellValue("Q'TY");
            hRow.createCell(4).setCellValue("Unit");
            hRow.createCell(5).setCellValue("U/P(USD)");
            hRow.createCell(6).setCellValue("Amount(USD)");
            hRow.createCell(7).setCellValue("Delivery Date");
            hRow.createCell(8).setCellValue("NOTE备注");

            Row d1 = sheet.createRow(2);
            d1.createCell(0).setCellValue(1);
            d1.createCell(1).setCellValue("PN-OK");
            d1.createCell(2).setCellValue("TI");
            d1.createCell(3).setCellValue(1000);
            d1.createCell(4).setCellValue("PCS");
            d1.createCell(5).setCellValue(2.50);
            d1.createCell(6).setCellFormula("D3*F3");
            d1.createCell(7).setCellValue("2026/11/01");
            d1.createCell(8).setCellValue("正常");

            Row d2 = sheet.createRow(3);
            d2.createCell(0).setCellValue(2);
            d2.createCell(1).setCellValue("PN-DIFF");
            d2.createCell(2).setCellValue("ADI");
            d2.createCell(3).setCellValue(500);
            d2.createCell(4).setCellValue("PCS");
            d2.createCell(5).setCellValue(10.00);
            d2.createCell(6).setCellValue(4000.00); // 实际计算值应为 5000.00
            d2.createCell(7).setCellValue("2026/11/02");
            d2.createCell(8).setCellValue("有差异");

            wb.write(fos);
        }

        PurchaseProcessor processor = new PurchaseProcessor();
        processor.process(
                tempDir,
                "2026/10/15",
                "供应商A",
                "PO-999",
                null,
                null,
                ProgressListener.console()
        );

        DataFormatter df = new DataFormatter();
        try (FileInputStream fis = new FileInputStream(resultFile); Workbook wb = new XSSFWorkbook(fis)) {
            Sheet pSheet = wb.getSheet("采购单");
            var eval = wb.getCreationHelper().createFormulaEvaluator();
            // 验证行 1: 金额为公式 E2*F2，值为 2,500.00，底色正常（非红色）
            Row r1 = pSheet.getRow(1);
            assertEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, r1.getCell(6).getCellType());
            assertEquals("E2*F2", r1.getCell(6).getCellFormula());
            assertEquals("2,500.00", df.formatCellValue(r1.getCell(6), eval));
            assertFalse(r1.getCell(6).getCellStyle().getFillForegroundColor() == org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());

            // 验证行 2: 金额为公式 E3*F3，值为 5,000.00，因与 source (4000.00) 不符，底色被设置为红色！
            Row r2 = pSheet.getRow(2);
            assertEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, r2.getCell(6).getCellType());
            assertEquals("E3*F3", r2.getCell(6).getCellFormula());
            assertEquals("5,000.00", df.formatCellValue(r2.getCell(6), eval));
            assertEquals(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex(), r2.getCell(6).getCellStyle().getFillForegroundColor());
            assertEquals(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND, r2.getCell(6).getCellStyle().getFillPattern());
        }
    }

    @Test
    void testExplicitSourceAndTargetSelectionSuccess() throws Exception {
        File tempDir = Files.createTempDirectory("purchase-explicit-test-").toFile();
        File sourceFile = new File(tempDir, "vendor_po_custom.xlsx");
        File targetFile = new File(tempDir, "company_master_custom.xlsx");

        // 1. 创建源单据
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(sourceFile)) {
            Sheet s = wb.createSheet("PO");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("No");
            h.createCell(1).setCellValue("P/N");
            h.createCell(2).setCellValue("Brand");
            h.createCell(3).setCellValue("Qty");
            h.createCell(4).setCellValue("Unit");
            h.createCell(5).setCellValue("U/P");
            h.createCell(6).setCellValue("Amount");

            Row d1 = s.createRow(1);
            d1.createCell(0).setCellValue(1);
            d1.createCell(1).setCellValue("IC-100");
            d1.createCell(2).setCellValue("TI");
            d1.createCell(3).setCellValue(500);
            d1.createCell(4).setCellValue("PCS");
            d1.createCell(5).setCellValue(3.0);
            d1.createCell(6).setCellValue(1500.0);
            wb.write(fos);
        }

        // 2. 创建目标母表
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(targetFile)) {
            Sheet pSheet = wb.createSheet("采购单");
            Row r0 = pSheet.createRow(0);
            for (int i = 0; i < ExcelAppendService.PURCHASE_DETAIL_HEADERS.size(); i++) {
                r0.createCell(i).setCellValue(ExcelAppendService.PURCHASE_DETAIL_HEADERS.get(i));
            }
            wb.createSheet("采购单汇总");
            wb.write(fos);
        }

        PurchaseProcessor processor = new PurchaseProcessor();
        File modified = processor.process(
                tempDir,
                "2026/10/21",
                "供应商B",
                "PO-888",
                "500",
                "1500.00",
                sourceFile.getName(),
                targetFile.getName(),
                ProgressListener.console()
        );

        assertEquals(targetFile.getAbsolutePath(), modified.getAbsolutePath());
        assertTrue(modified.exists());
    }

    @Test
    void testReversedSelectionThrowsExceptionWithClearMessage() throws Exception {
        File tempDir = Files.createTempDirectory("purchase-reverse-test-").toFile();
        File sourceFile = new File(tempDir, "vendor_po.xlsx");
        File targetFile = new File(tempDir, "master_book.xlsx");

        // 构造源单据
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(sourceFile)) {
            Sheet s = wb.createSheet("PO");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("No");
            h.createCell(1).setCellValue("P/N");
            wb.write(fos);
        }

        // 构造目标母表
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(targetFile)) {
            Sheet pSheet = wb.createSheet("采购单");
            Row r0 = pSheet.createRow(0);
            for (int i = 0; i < ExcelAppendService.PURCHASE_DETAIL_HEADERS.size(); i++) {
                r0.createCell(i).setCellValue(ExcelAppendService.PURCHASE_DETAIL_HEADERS.get(i));
            }
            wb.write(fos);
        }

        PurchaseProcessor processor = new PurchaseProcessor();

        // 用户将母表反选为源，将源反选为母表
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> processor.process(
                        tempDir,
                        "2026/10/21",
                        "供应商B",
                        "PO-888",
                        null,
                        null,
                        targetFile.getName(), // 错误：把母表选成了源
                        sourceFile.getName(), // 错误：把源选成了母表
                        ProgressListener.console()
                )
        );

        assertTrue(
                ex.getMessage().contains("目标母表格式错误") || ex.getMessage().contains("采购源文件格式错误"),
                "应明确拦截格式不匹配并提示错误原因，实际为: " + ex.getMessage()
        );
    }

    @Test
    void testSameFileSelectionThrowsConflictException() throws Exception {
        File tempDir = Files.createTempDirectory("purchase-same-test-").toFile();
        File anyFile = new File(tempDir, "some_file.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(anyFile)) {
            wb.createSheet("Sheet1");
            wb.write(fos);
        }

        PurchaseProcessor processor = new PurchaseProcessor();
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> processor.process(
                        tempDir,
                        "2026/10/21",
                        "供应商B",
                        "PO-888",
                        null,
                        null,
                        anyFile.getName(),
                        anyFile.getName(),
                        ProgressListener.console()
                )
        );

        assertTrue(ex.getMessage().contains("文件选择冲突"), "应提示文件选择冲突");
    }

    @Test
    void testTargetMissingRequiredSheetThrowsException() throws Exception {
        File tempDir = Files.createTempDirectory("purchase-missing-sheet-test-").toFile();
        File sourceFile = new File(tempDir, "valid_source.xlsx");
        File invalidTarget = new File(tempDir, "not_master.xlsx");

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(sourceFile)) {
            Sheet s = wb.createSheet("PO");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("No");
            h.createCell(1).setCellValue("P/N");
            wb.write(fos);
        }

        // 没有【采购单】Sheet
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(invalidTarget)) {
            wb.createSheet("随便其他表");
            wb.write(fos);
        }

        PurchaseProcessor processor = new PurchaseProcessor();
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> processor.process(
                        tempDir,
                        "2026/10/21",
                        "供应商B",
                        "PO-888",
                        null,
                        null,
                        sourceFile.getName(),
                        invalidTarget.getName(),
                        ProgressListener.console()
                )
        );

        assertTrue(ex.getMessage().contains("目标母表格式错误"), "应提示目标母表格式错误");
        assertTrue(ex.getMessage().contains("采购单"), "应指出缺少【采购单】工作表");
    }
}

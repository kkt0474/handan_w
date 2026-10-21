package com.handan.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handan.config.ConfigLoader;
import com.handan.config.MappingConfig;
import com.handan.progress.ProgressListener;
import com.handan.purchase.PurchaseItem;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelAppendServiceTest {

    @Test
    void appendsToBothDetailAndSummarySheetsWithDynamicUsFormat() throws Exception {
        File tempDir = Files.createTempDirectory("excel-test-").toFile();
        File original = new File(tempDir, "result.xlsx");

        // 1. 创建包含多个 Sheet 的 mock result.xlsx
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(original)) {
            // Sheet 1: 其他无关 sheet
            Sheet s1 = wb.createSheet("统计汇总");
            s1.createRow(0).createCell(0).setCellValue("总计");
            s1.createRow(1).createCell(0).setCellValue(100);

            // Sheet 2: 客户订单（带表头与已有数据）
            Sheet s2 = wb.createSheet("客户订单");
            var r0 = s2.createRow(0);
            r0.createCell(0).setCellValue("日期");
            r0.createCell(1).setCellValue("客户");
            r0.createCell(2).setCellValue("订单号");
            r0.createCell(3).setCellValue("料号");
            r0.createCell(4).setCellValue("单价(USD)");
            r0.createCell(5).setCellValue("数量(PCS)");
            r0.createCell(6).setCellValue("金额");
            r0.createCell(7).setCellValue("交期");

            var r1 = s2.createRow(1);
            r1.createCell(0).setCellValue("2026/8/20");
            r1.createCell(1).setCellValue("旧客户A");
            r1.createCell(2).setCellValue("OLD-001");
            r1.createCell(3).setCellValue("ITEM-OLD");
            r1.createCell(4).setCellValue(0.4400);
            r1.createCell(5).setCellValue(30000);
            r1.createCell(6).setCellValue(13200.00);
            r1.createCell(7).setCellValue("2026-11-01");

            // Sheet 3: 客户订单汇总（带已有历史数据）
            Sheet s3 = wb.createSheet("客户订单汇总");
            var s3r0 = s3.createRow(0);
            s3r0.createCell(0).setCellValue("客户");
            s3r0.createCell(1).setCellValue("订单号");
            s3r0.createCell(2).setCellValue("料号");
            s3r0.createCell(3).setCellValue("总数量");
            s3r0.createCell(4).setCellValue("出货数量");
            s3r0.createCell(5).setCellValue("未出货数量");

            var s3r1 = s3.createRow(1);
            s3r1.createCell(0).setCellValue("旧客户A");
            s3r1.createCell(1).setCellValue("OLD-001");
            s3r1.createCell(2).setCellValue("ITEM-OLD");
            s3r1.createCell(3).setCellValue(80);
            s3r1.createCell(4).setCellValue("-");
            s3r1.createCell(5).setCellValue(80);

            wb.write(fos);
        }

        // 2. 模拟追加新数据：
        // 关键测试点：数量由于 OCR 误识别为 "40.000"（点号千分位）
        // 单价为 4 位小数 "0.4400"，金额为 2 位小数 "13200.00"
        List<List<Object>> newDetailRows = List.of(
                List.of("2026/8/26", "新客户B", "NEW-002", "ITEM-1", "0.4400", "40.000", "13200.00", "2026-11-02")
        );

        List<List<Object>> newSummaryRows = List.of(
                List.of("新客户B", "NEW-002", "ITEM-1", 40000L, "-", 40000L)
        );

        MappingConfig cfg = ConfigLoader.load(new File("mapping-config.yaml"));
        ExcelAppendService service = new ExcelAppendService();

        File resultFile = service.backupAndAppendToOriginal(
                tempDir,
                newDetailRows,
                newSummaryRows,
                cfg,
                ProgressListener.console()
        );

        // 3. 校验返回的是原文件 result.xlsx 且原文件已被直接追加
        assertEquals(original.getAbsolutePath(), resultFile.getAbsolutePath());
        assertTrue(resultFile.exists());

        // 4. 校验备份文件已生成：命名字段为 result_yyyyMMddHHmmss.xlsx，内容保持备份前状态
        File[] backupFiles = tempDir.listFiles((dir, name) -> name.matches("result_\\d{14}\\.xlsx"));
        assertTrue(backupFiles != null && backupFiles.length == 1, "必须生成且仅生成一个符合精确到秒命名规则的备份文件");
        File backupFile = backupFiles[0];
        assertTrue(backupFile.exists() && backupFile.length() > 0, "备份文件必须存在且不为空");

        // 校验备份文件保留的是写入前的数据（只有 1 行历史数据）
        try (FileInputStream bFis = new FileInputStream(backupFile);
             Workbook bWb = new XSSFWorkbook(bFis)) {
            Sheet bDetailSheet = bWb.getSheet("客户订单");
            assertEquals(1, bDetailSheet.getLastRowNum(), "备份文件明细表应只有原本的 1 行历史数据");
            Sheet bSummarySheet = bWb.getSheet("客户订单汇总");
            assertEquals(1, bSummarySheet.getLastRowNum(), "备份文件汇总表应只有原本的 1 行历史数据");
        }

        DataFormatter df = new DataFormatter();

        // 5. 校验原文件已成功追加新数据及格式化渲染
        try (FileInputStream fis = new FileInputStream(resultFile);
             Workbook wb = new XSSFWorkbook(fis)) {

            // 校验【客户订单】格式化渲染：
            Sheet s2 = wb.getSheet("客户订单");
            assertEquals(2, s2.getLastRowNum(), "原文件明细表应追加到了第 2 行");
            var appendedDetailRow = s2.getRow(2);

            // 单价：按 4 位美式格式渲染 -> 0.4400
            assertEquals(0.44, appendedDetailRow.getCell(4).getNumericCellValue(), 0.00001);
            assertEquals("0.4400", df.formatCellValue(appendedDetailRow.getCell(4)));

            // 数量：原本误识别为 "40.000"，应纠偏还原为 40000 并按美式千分位渲染为 -> 40,000
            assertEquals(40000.0, appendedDetailRow.getCell(5).getNumericCellValue(), 0.001);
            assertEquals("40,000", df.formatCellValue(appendedDetailRow.getCell(5)));

            // 金额：按 2 位美式格式渲染 -> 13,200.00
            assertEquals(13200.0, appendedDetailRow.getCell(6).getNumericCellValue(), 0.001);
            assertEquals("13,200.00", df.formatCellValue(appendedDetailRow.getCell(6)));

            // 校验【客户订单汇总】格式化渲染：
            Sheet s3 = wb.getSheet("客户订单汇总");
            assertEquals(2, s3.getLastRowNum(), "原文件汇总表应追加到了第 2 行");
            var appendedSummaryRow = s3.getRow(2);

            // 总数量渲染为 40,000
            assertEquals(40000.0, appendedSummaryRow.getCell(3).getNumericCellValue(), 0.001);
            assertEquals("40,000", df.formatCellValue(appendedSummaryRow.getCell(3)));

            // 出货数量保持 "-"
            assertEquals("-", appendedSummaryRow.getCell(4).getStringCellValue());

            // 未出货数量渲染为 40,000
            assertEquals(40000.0, appendedSummaryRow.getCell(5).getNumericCellValue(), 0.001);
            assertEquals("40,000", df.formatCellValue(appendedSummaryRow.getCell(5)));
        }
    }

    @Test
    void throwsIllegalArgumentExceptionWhenOriginalFileNotFound() {
        File emptyDir = new File(System.getProperty("java.io.tmpdir"), "empty-test-" + System.currentTimeMillis());
        emptyDir.mkdirs();
        try {
            ExcelAppendService service = new ExcelAppendService();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> {
                service.backupAndAppendToOriginal(
                        emptyDir,
                        List.of(),
                        List.of(),
                        new MappingConfig("客户订单", List.of()),
                        ProgressListener.console()
                );
            });
        } finally {
            emptyDir.delete();
        }
    }

    @Test
    void abortsWhenOfficeLockFileExists() throws Exception {
        File tempDir = Files.createTempDirectory("excel-lock-test-").toFile();
        File original = new File(tempDir, "result.xlsx");
        original.createNewFile();

        // 模拟 Excel / WPS 打开时生成的临时隐藏锁定文件 ~$result.xlsx
        File lockFile = new File(tempDir, "~$result.xlsx");
        lockFile.createNewFile();

        ExcelAppendService service = new ExcelAppendService();

        java.io.IOException ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> service.backupAndAppendPurchaseToOriginal(
                        tempDir,
                        List.of(),
                        List.of(),
                        ProgressListener.console()
                )
        );

        assertTrue(ex.getMessage().contains("Microsoft Excel / WPS 打开占用"));

        // 验证由于检测到占用被中止，严禁生成任何备份文件
        File[] backups = tempDir.listFiles((dir, name) -> name.matches("result_\\d{14}\\.xlsx"));
        assertTrue(backups == null || backups.length == 0, "检测到文件被占用时严禁执行备份");
    }

    @Test
    void correctlyAppendsAfterRealDataIgnoringGhostFormattedEmptyRowsAndPreservesStyle() throws Exception {
        File tempDir = Files.createTempDirectory("excel-ghost-test-").toFile();
        File original = new File(tempDir, "result.xlsx");

        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(original)) {

            CellStyle yellowStyle = wb.createCellStyle();
            yellowStyle.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle blueStyle = wb.createCellStyle();
            blueStyle.setFillForegroundColor(IndexedColors.LIGHT_TURQUOISE.getIndex());
            blueStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // 1. 创建【采购单】Sheet：
            // Row 0: 表头
            // Row 1: 已有的 1 行真实历史数据
            // Row 2 ~ 500: 空白单元格，但被设置了斑马底色（黄色/蓝色）
            Sheet sPurchase = wb.createSheet("采购单");
            var headerRow = sPurchase.createRow(0);
            List<String> headers = List.of("日期", "供应商", "采购单号", "料号", "单价(USD)", "数量(PCS)", "金额", "交期", "备注");
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }

            var histRow = sPurchase.createRow(1);
            histRow.createCell(0).setCellValue("2026/10/01");
            histRow.createCell(1).setCellValue("历史供应商");
            histRow.createCell(2).setCellValue("PO-HIST");
            histRow.createCell(3).setCellValue("PN-001");
            histRow.createCell(4).setCellValue(1.5);
            histRow.createCell(5).setCellValue(1000);
            histRow.createCell(6).setCellValue(1500.0);
            histRow.createCell(7).setCellValue("2026-10-20");
            histRow.createCell(8).setCellValue("旧单");

            // 模拟 500 行设置了背景色且部分列预置了公式/占位符的幽灵空行（此时 sPurchase.getLastRowNum() == 500）
            for (int r = 2; r <= 500; r++) {
                var ghostRow = sPurchase.createRow(r);
                CellStyle rowStyle = (r % 2 == 0) ? yellowStyle : blueStyle;
                for (int c = 0; c < 9; c++) {
                    var cell = ghostRow.createCell(c);
                    cell.setCellStyle(rowStyle);
                }
                // 模拟模板中第 6 列（金额）预先下拉了公式 =E*F，且第 0 列为空
                ghostRow.getCell(6).setCellFormula("E" + (r + 1) + "*F" + (r + 1));
            }

            // 2. 创建【采购单汇总】Sheet：仅有 Row 0 表头，Row 1 ~ 500 全部为预设样式的幽灵空行
            Sheet sSummary = wb.createSheet("采购单汇总");
            var sumHeader = sSummary.createRow(0);
            sumHeader.createCell(0).setCellValue("供应商");
            sumHeader.createCell(1).setCellValue("采购单号");
            sumHeader.createCell(2).setCellValue("料号");
            sumHeader.createCell(3).setCellValue("总数量");
            sumHeader.createCell(4).setCellValue("入库数量");
            sumHeader.createCell(5).setCellValue("未交数量");

            for (int r = 1; r <= 500; r++) {
                var ghostRow = sSummary.createRow(r);
                for (int c = 0; c < 6; c++) {
                    var cell = ghostRow.createCell(c);
                    cell.setCellStyle(yellowStyle);
                }
                // 模拟汇总表中第 4 列预置了 "-" 占位符，且第 0 列为空
                ghostRow.getCell(4).setCellValue("-");
            }

            wb.write(fos);
        }

        // 3. 执行采购单追加：追加 1 条明细和 1 条汇总
        ExcelAppendService service = new ExcelAppendService();
        List<List<Object>> detailRows = List.of(
                List.of("2026/10/14", "新供应商", "PO-2026", "PN-NEW", "2.5000", "50.000", "125000.00", "2026-11-01", "新采购")
        );
        List<List<Object>> summaryRows = List.of(
                List.of("新供应商", "PO-2026", "PN-NEW", 50000L, "-", 50000L)
        );

        File resultFile = service.backupAndAppendPurchaseToOriginal(
                tempDir,
                detailRows,
                summaryRows,
                ProgressListener.console()
        );

        // 4. 读取校验
        DataFormatter df = new DataFormatter();
        try (FileInputStream fis = new FileInputStream(resultFile);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sPurchase = wb.getSheet("采购单");
            // 关键断言 1：数据必须被写入在 Row 2（紧跟 Row 1 真实数据），绝对不能跳到 501 行！
            var targetRow = sPurchase.getRow(2);
            assertEquals("新供应商", targetRow.getCell(1).getStringCellValue());
            assertEquals("PN-NEW", targetRow.getCell(3).getStringCellValue());
            assertEquals("50,000", df.formatCellValue(targetRow.getCell(5)));
            assertEquals("2.5000", df.formatCellValue(targetRow.getCell(4)));
            // 关键断言：金额必须是动态计算公式（E3*F3），并预存数值 125,000.00
            assertEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, targetRow.getCell(6).getCellType());
            assertEquals("E3*F3", targetRow.getCell(6).getCellFormula());
            assertEquals(125000.00, targetRow.getCell(6).getNumericCellValue(), 0.001);
            var evaluator = wb.getCreationHelper().createFormulaEvaluator();
            assertEquals("125,000.00", df.formatCellValue(targetRow.getCell(6), evaluator));

            // 关键断言 2：文本单元格（供应商、订单号、料号等）预设的黄色背景颜色完好保留
            assertEquals(IndexedColors.YELLOW.getIndex(), targetRow.getCell(1).getCellStyle().getFillForegroundColor());

            // Row 3 依然是未填写的幽灵空行（保持蓝色底色）
            var nextGhostRow = sPurchase.getRow(3);
            assertEquals("", df.formatCellValue(nextGhostRow.getCell(0)));
            assertEquals(IndexedColors.LIGHT_TURQUOISE.getIndex(), nextGhostRow.getCell(0).getCellStyle().getFillForegroundColor());

            // 关键断言 3：采购单汇总表必须紧跟 Row 0 表头，写入在 Row 1，而不是 501 行！
            Sheet sSummary = wb.getSheet("采购单汇总");
            var sumTargetRow = sSummary.getRow(1);
            assertEquals("新供应商", sumTargetRow.getCell(0).getStringCellValue());
            assertEquals("PN-NEW", sumTargetRow.getCell(2).getStringCellValue());
            assertEquals("50,000", df.formatCellValue(sumTargetRow.getCell(3)));
            assertEquals(IndexedColors.YELLOW.getIndex(), sumTargetRow.getCell(0).getCellStyle().getFillForegroundColor());
        }
    }

    @Test
    void writesByHeaderNameAndPreservesTemplateStyleAndOverwritesFormula() throws Exception {
        File tempDir = Files.createTempDirectory("excel-header-test-").toFile();
        File original = new File(tempDir, "result.xlsx");

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(original)) {
            CellStyle yellow = wb.createCellStyle();
            yellow.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            yellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            yellow.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            yellow.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            yellow.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            yellow.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            CellStyle bordered = wb.createCellStyle();
            bordered.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            bordered.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            bordered.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            bordered.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);

            // 模板列顺序与代码内置顺序不同：中间多了一列「规格」
            List<String> headers = List.of("日期", "供应商", "采购单号", "料号", "单价(USD)", "规格", "数量(PCS)", "金额", "交期", "备注");
            Sheet sp = wb.createSheet("采购单");
            var h = sp.createRow(0);
            for (int i = 0; i < headers.size(); i++) h.createCell(i).setCellValue(headers.get(i));
            var hist = sp.createRow(1);
            hist.createCell(0).setCellValue("2026/10/01");
            for (int r = 2; r <= 50; r++) {
                var row = sp.createRow(r);
                for (int c = 0; c < headers.size(); c++) {
                    row.createCell(c).setCellStyle(c == 2 ? yellow : bordered);
                }
                row.getCell(7).setCellFormula("E" + (r + 1) + "*G" + (r + 1));
            }

            Sheet ss = wb.createSheet("采购单汇总");
            var sh = ss.createRow(0);
            List<String> sHeaders = List.of("供应商", "采购单号", "料号", "总数量", "入库数量", "未交数量");
            for (int i = 0; i < sHeaders.size(); i++) sh.createCell(i).setCellValue(sHeaders.get(i));
            wb.write(fos);
        }

        List<List<Object>> detail = List.of(
                List.of("2026/10/14", "新供应商", "4500123", "PN-NEW", "2.5000", "50.000", "125000.00", "2026-11-01", "新采购"));
        List<List<Object>> summary = List.of(List.of("新供应商", "4500123", "PN-NEW", 50000L, "-", 50000L));

        File result = new ExcelAppendService().backupAndAppendPurchaseToOriginal(
                tempDir, detail, summary, ProgressListener.console());

        DataFormatter df = new DataFormatter();
        try (FileInputStream fis = new FileInputStream(result); Workbook wb = new XSSFWorkbook(fis)) {
            var row = wb.getSheet("采购单").getRow(2);
            // 按表头名称写入到正确的列（规格列 F 保持为空）
            assertEquals("新供应商", row.getCell(1).getStringCellValue());
            assertEquals("4500123", row.getCell(2).getStringCellValue());
            assertEquals("PN-NEW", row.getCell(3).getStringCellValue());
            assertEquals("2.5000", df.formatCellValue(row.getCell(4)));
            assertEquals("", df.formatCellValue(row.getCell(5)));
            assertEquals("50,000", df.formatCellValue(row.getCell(6)));
            // 金额：公式自适应设置（E3*G3，因为中间隔了规格列），预设数值 125,000.00
            assertEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, row.getCell(7).getCellType());
            assertEquals("E3*G3", row.getCell(7).getCellFormula());
            assertEquals(125000.00, row.getCell(7).getNumericCellValue(), 0.001);
            var eval = wb.getCreationHelper().createFormulaEvaluator();
            assertEquals("125,000.00", df.formatCellValue(row.getCell(7), eval));
            // 采购单号列黄色背景保留
            assertEquals(IndexedColors.YELLOW.getIndex(), row.getCell(2).getCellStyle().getFillForegroundColor());
            // 数字单元格边框保留
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.THIN, row.getCell(4).getCellStyle().getBorderBottom());
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.THIN, row.getCell(7).getCellStyle().getBorderLeft());
        }

        // calcChain 不应残留，防止 Excel 报“文件损坏”
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(result)) {
            assertTrue(zf.getEntry("xl/calcChain.xml") == null, "不应残留 calcChain.xml");
        }
    }

    @Test
    void locatesHeaderRowBelowTitleRow() throws Exception {
        File tempDir = Files.createTempDirectory("excel-title-row-").toFile();
        File original = new File(tempDir, "result.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(original)) {
            Sheet sp = wb.createSheet("采购单");
            sp.createRow(0).createCell(0).setCellValue("采购单统计");
            var h = sp.createRow(1);
            List<String> headers = List.of("日期", "供应商", "采购单号", "料号", "单价(USD)", "数量(PCS)", "金额", "交期", "备注");
            for (int i = 0; i < headers.size(); i++) h.createCell(i).setCellValue(headers.get(i));
            sp.createRow(2).createCell(0).setCellValue("2026/7/14");
            Sheet ss = wb.createSheet("采购单汇总");
            var sh = ss.createRow(0);
            List<String> sHeaders = List.of("供应商", "采购单号", "料号", "总数量", "入库数量", "未交数量");
            for (int i = 0; i < sHeaders.size(); i++) sh.createCell(i).setCellValue(sHeaders.get(i));
            wb.write(fos);
        }
        File result = new ExcelAppendService().backupAndAppendPurchaseToOriginal(
                tempDir,
                List.of(List.of("2026/10/14", "S", "PO1", "PN", "1.5", "10", "15.00", "d", "n")),
                List.of(List.of("S", "PO1", "PN", 10L, "-", 10L)),
                ProgressListener.console());
        try (FileInputStream fis = new FileInputStream(result); Workbook wb = new XSSFWorkbook(fis)) {
            var row = wb.getSheet("采购单").getRow(3);
            assertEquals("2026/10/14", row.getCell(0).getStringCellValue());
            assertEquals("S", row.getCell(1).getStringCellValue());
        }
    }

    @Test
    void abortsWhenHeaderMissingInTemplate() throws Exception {
        File tempDir = Files.createTempDirectory("excel-missing-header-").toFile();
        File original = new File(tempDir, "result.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(original)) {
            Sheet sp = wb.createSheet("采购单");
            var h = sp.createRow(0);
            h.createCell(0).setCellValue("日期");
            h.createCell(1).setCellValue("供应商");
            wb.createSheet("采购单汇总").createRow(0).createCell(0).setCellValue("供应商");
            wb.write(fos);
        }
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new ExcelAppendService().backupAndAppendPurchaseToOriginal(
                        tempDir,
                        List.of(List.of("2026/10/14", "S", "PO", "PN", "1", "1", "1", "d", "n")),
                        List.of(),
                        ProgressListener.console()));
        assertTrue(ex.getMessage().contains("找不到字段"));
    }

    @Test
    void highlightsAmountMismatchInRedAndKeepsMatchingNormal() throws Exception {
        File tempDir = Files.createTempDirectory("excel-mismatch-test-").toFile();
        File original = new File(tempDir, "result.xlsx");

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(original)) {
            Sheet sp = wb.createSheet("采购单");
            var h = sp.createRow(0);
            List<String> headers = List.of("日期", "供应商", "采购单号", "料号", "单价(USD)", "数量(PCS)", "金额", "交期", "备注");
            for (int i = 0; i < headers.size(); i++) {
                h.createCell(i).setCellValue(headers.get(i));
            }

            // 预设黄色样式的模板空行
            CellStyle yellowStyle = wb.createCellStyle();
            yellowStyle.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            yellowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int r = 1; r <= 10; r++) {
                var row = sp.createRow(r);
                for (int c = 0; c < 9; c++) {
                    var cell = row.createCell(c);
                    cell.setCellStyle(yellowStyle);
                }
            }

            Sheet ss = wb.createSheet("采购单汇总");
            var sh = ss.createRow(0);
            List<String> sHeaders = com.handan.purchase.PurchaseSummaryAggregator.SUMMARY_HEADERS;
            for (int i = 0; i < sHeaders.size(); i++) {
                sh.createCell(i).setCellValue(sHeaders.get(i));
            }

            wb.write(fos);
        }

        // 条目 1: 单价 2.50, 数量 1000 -> 计算金额 2500.00，source 金额 2500.00 (吻合，保留黄色背景)
        PurchaseItem item1 = new PurchaseItem(
                1, "PN-1", "TI", new BigDecimal("1000"), "1,000", "PCS",
                new BigDecimal("2.50"), "2.50", new BigDecimal("2500.00"), "2500.00", "2026/11/01", "正常"
        );
        // 条目 2: 单价 0.50, 数量 40000 -> 计算金额 20000.00，source 金额 18000.00 (不吻合，标记红色背景)
        PurchaseItem item2 = new PurchaseItem(
                2, "PN-2", "ST", new BigDecimal("40000"), "40,000", "PCS",
                new BigDecimal("0.50"), "0.50", new BigDecimal("18000.00"), "18000.00", "2026/11/02", "异常"
        );

        List<List<Object>> detailRows = List.of(
                List.of("2026/10/15", "供应商A", "PO-100", "PN-1", "2.50", "1,000", item1.calculateAmount(), "2026/11/01", "正常"),
                List.of("2026/10/15", "供应商A", "PO-100", "PN-2", "0.50", "40,000", item2.calculateAmount(), "2026/11/02", "异常")
        );

        List<List<Object>> summaryRows = List.of(
                List.of("供应商A", "PO-100", "PN-1", 1000L, "-", 1000L),
                List.of("供应商A", "PO-100", "PN-2", 40000L, "-", 40000L)
        );

        ExcelAppendService service = new ExcelAppendService();
        service.backupAndAppendPurchaseToOriginal(tempDir, detailRows, summaryRows, List.of(item1, item2), ProgressListener.console());

        DataFormatter df = new DataFormatter();
        try (FileInputStream fis = new FileInputStream(original); Workbook wb = new XSSFWorkbook(fis)) {
            Sheet sheet = wb.getSheet("采购单");
            var evaluator = wb.getCreationHelper().createFormulaEvaluator();
            // Row 1 (条目 1)
            var cell1 = sheet.getRow(1).getCell(6);
            assertEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, cell1.getCellType());
            assertEquals("E2*F2", cell1.getCellFormula());
            assertEquals("2,500.00", df.formatCellValue(cell1, evaluator));
            assertEquals(IndexedColors.YELLOW.getIndex(), cell1.getCellStyle().getFillForegroundColor(), "吻合行应保留模板原本底色（黄色）");

            // Row 2 (条目 2)
            var cell2 = sheet.getRow(2).getCell(6);
            assertEquals(org.apache.poi.ss.usermodel.CellType.FORMULA, cell2.getCellType());
            assertEquals("E3*F3", cell2.getCellFormula());
            assertEquals("20,000.00", df.formatCellValue(cell2, evaluator));
            assertEquals(IndexedColors.RED.getIndex(), cell2.getCellStyle().getFillForegroundColor(), "差异行应被标为红色背景");
            assertEquals(FillPatternType.SOLID_FOREGROUND, cell2.getCellStyle().getFillPattern());
        }
    }

    @Test
    void resolvesCustomNamedExcelFileSuccessfully() throws Exception {
        File tempDir = Files.createTempDirectory("custom-excel-test-").toFile();
        File customFile = new File(tempDir, "custom_orders.xlsx");

        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(customFile)) {
            Sheet s = wb.createSheet("客户订单");
            var r0 = s.createRow(0);
            r0.createCell(0).setCellValue("日期");
            r0.createCell(1).setCellValue("客户");
            r0.createCell(2).setCellValue("订单号");
            r0.createCell(3).setCellValue("料号");
            r0.createCell(4).setCellValue("单价(USD)");
            r0.createCell(5).setCellValue("数量(PCS)");
            r0.createCell(6).setCellValue("金额");
            r0.createCell(7).setCellValue("交期");
            wb.createSheet("客户订单汇总");
            wb.write(fos);
        }

        List<List<Object>> detailRows = List.of(
                List.of("2026/8/26", "客户X", "ORD-1", "ITEM-1", "0.5000", "1000", "500.00", "2026-11-02")
        );
        List<List<Object>> summaryRows = List.of(
                List.of("客户X", "ORD-1", "ITEM-1", 1000L, "-", 1000L)
        );

        MappingConfig cfg = ConfigLoader.load(new File("mapping-config.yaml"));
        ExcelAppendService service = new ExcelAppendService();

        File resultFile = service.backupAndAppendToOriginal(
                tempDir,
                detailRows,
                summaryRows,
                cfg,
                ProgressListener.console()
        );

        assertEquals(customFile.getAbsolutePath(), resultFile.getAbsolutePath());
        assertTrue(resultFile.exists());

        // 验证生成的备份文件动态基于 custom_orders 前缀
        File[] backupFiles = tempDir.listFiles((dir, name) -> name.matches("custom_orders_\\d{14}\\.xlsx"));
        assertTrue(backupFiles != null && backupFiles.length == 1, "备份文件应命名为 custom_orders_yyyyMMddHHmmss.xlsx");
    }

    @Test
    void throwsExceptionWhenMultipleExcelFilesExistInDirectory() throws Exception {
        File tempDir = Files.createTempDirectory("multi-excel-test-").toFile();
        File fileA = new File(tempDir, "orders_a.xlsx");
        File fileB = new File(tempDir, "orders_b.xlsx");

        try (Workbook wb1 = new XSSFWorkbook(); FileOutputStream fos1 = new FileOutputStream(fileA)) {
            wb1.createSheet("Sheet1");
            wb1.write(fos1);
        }
        try (Workbook wb2 = new XSSFWorkbook(); FileOutputStream fos2 = new FileOutputStream(fileB)) {
            wb2.createSheet("Sheet1");
            wb2.write(fos2);
        }

        ExcelAppendService service = new ExcelAppendService();
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveTargetExcelFile(tempDir, ProgressListener.console())
        );

        assertTrue(ex.getMessage().contains("检测到多个 Excel 文件"), "应提示检测到多个 Excel 文件");
        assertTrue(ex.getMessage().contains("orders_a.xlsx"), "应包含 orders_a.xlsx");
        assertTrue(ex.getMessage().contains("orders_b.xlsx"), "应包含 orders_b.xlsx");
        assertTrue(ex.getMessage().contains("请仅保留一个目标 Excel 母表文件"), "应提示仅保留一个母表文件");
    }

    @Test
    void ignoresBackupAndLockFilesWhenResolvingTarget() throws Exception {
        File tempDir = Files.createTempDirectory("ignore-backups-test-").toFile();
        File targetFile = new File(tempDir, "main_sheet.xlsx");
        File backupFile1 = new File(tempDir, "main_sheet_20261021114500.xlsx");
        File backupFile2 = new File(tempDir, "history_bak.xlsx");
        File tempFile = new File(tempDir, "main_sheet_temp_123456789.xlsx");

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(targetFile)) {
            wb.createSheet("Sheet1");
            wb.write(fos);
        }
        Files.copy(targetFile.toPath(), backupFile1.toPath());
        Files.copy(targetFile.toPath(), backupFile2.toPath());
        Files.copy(targetFile.toPath(), tempFile.toPath());

        ExcelAppendService service = new ExcelAppendService();
        File resolved = service.resolveTargetExcelFile(tempDir, ProgressListener.console());

        assertEquals(targetFile.getAbsolutePath(), resolved.getAbsolutePath(), "应精准排除备份和临时文件并识别唯一的母表");
    }

    @Test
    void excludesSourceExcelFileWhenResolvingPurchaseTarget() throws Exception {
        File tempDir = Files.createTempDirectory("purchase-exclude-test-").toFile();
        File sourceFile = new File(tempDir, "source.xlsx");
        File targetFile = new File(tempDir, "target_report.xlsx");

        try (Workbook wb1 = new XSSFWorkbook(); FileOutputStream fos1 = new FileOutputStream(sourceFile)) {
            wb1.createSheet("Source");
            wb1.write(fos1);
        }
        try (Workbook wb2 = new XSSFWorkbook(); FileOutputStream fos2 = new FileOutputStream(targetFile)) {
            wb2.createSheet("Target");
            wb2.write(fos2);
        }

        ExcelAppendService service = new ExcelAppendService();
        File resolved = service.resolveTargetExcelFile(tempDir, sourceFile, ProgressListener.console());

        assertEquals(targetFile.getAbsolutePath(), resolved.getAbsolutePath(), "采购单处理应排除 source.xlsx 并唯一定位 target_report.xlsx");
    }

    @Test
    void throwsExceptionWhenNoExcelFileFoundInDirectory() throws Exception {
        File tempDir = Files.createTempDirectory("empty-excel-test-").toFile();
        ExcelAppendService service = new ExcelAppendService();

        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveTargetExcelFile(tempDir, ProgressListener.console())
        );

        assertTrue(ex.getMessage().contains("未在工作目录"), "应提示未在工作目录中检测到任何目标 Excel 母表文件");
    }
}

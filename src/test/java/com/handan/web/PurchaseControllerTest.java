package com.handan.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handan.excel.ExcelAppendService;
import com.handan.purchase.PurchaseProcessor;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

class PurchaseControllerTest {

    @Test
    void listPurchaseFilesDetectsFilesAndRecommendations() throws Exception {
        File tempDir = Files.createTempDirectory("ctrl-files-test-").toFile();
        File sourceFile = new File(tempDir, "po_sample.xlsx");
        File targetFile = new File(tempDir, "master_sheet.xlsx");

        // 创建 source: 含 No, P/N
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(sourceFile)) {
            Sheet s = wb.createSheet("Sheet1");
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("No");
            h.createCell(1).setCellValue("P/N");
            wb.write(fos);
        }

        // 创建 target: 含 采购单 sheet
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(targetFile)) {
            wb.createSheet("采购单");
            wb.write(fos);
        }

        PurchaseController controller = new PurchaseController(new PurchaseProcessor());
        ReflectionTestUtils.setField(controller, "defaultDataDir", tempDir.getAbsolutePath());

        ResponseEntity<PurchaseFilesResponse> resp = controller.listPurchaseFiles(tempDir.getAbsolutePath());
        assertNotNull(resp.getBody());
        assertEquals(2, resp.getBody().files().size());
        assertEquals(sourceFile.getName(), resp.getBody().recommendedSource());
        assertEquals(targetFile.getName(), resp.getBody().recommendedTarget());
    }

    @Test
    void listPurchaseFilesFiltersTimestampBackupsAndBakAndTempFilesBasedOnConfig() throws Exception {
        File tempDir = Files.createTempDirectory("ctrl-filter-test-").toFile();
        File validSource = new File(tempDir, "po_valid.xlsx");
        File validTarget = new File(tempDir, "master_valid.xlsx");
        File timestampBackup = new File(tempDir, "master_valid_20261021123000.xlsx");
        File bakFile = new File(tempDir, "history_bak.xlsx");
        File tempFile = new File(tempDir, "master_temp_12345.xlsx");
        File lockFile = new File(tempDir, "~$master_valid.xlsx");

        for (File f : List.of(validSource, validTarget, timestampBackup, bakFile, tempFile, lockFile)) {
            try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(f)) {
                wb.createSheet("S");
                wb.write(fos);
            }
        }

        PurchaseController controller = new PurchaseController(new PurchaseProcessor());
        ReflectionTestUtils.setField(controller, "defaultDataDir", tempDir.getAbsolutePath());

        ResponseEntity<PurchaseFilesResponse> resp = controller.listPurchaseFiles(tempDir.getAbsolutePath());
        assertNotNull(resp.getBody());
        List<String> files = resp.getBody().files();

        // 仅保留 2 个常规工作文件，其余备份、临时、锁文件全部被配置规则过滤
        assertEquals(2, files.size(), "应仅保留 2 个常规 Excel，排除时间戳备份、_bak、_temp_ 与锁文件");
        assertTrue(files.contains("po_valid.xlsx"));
        assertTrue(files.contains("master_valid.xlsx"));
        assertFalse(files.contains(timestampBackup.getName()));
        assertFalse(files.contains(bakFile.getName()));
        assertFalse(files.contains(tempFile.getName()));
        assertFalse(files.contains(lockFile.getName()));
    }

    @Test
    void executePurchaseReturnsFailureWhenFormatMismatch() throws Exception {
        File tempDir = Files.createTempDirectory("ctrl-mismatch-test-").toFile();
        File fileA = new File(tempDir, "order.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(fileA)) {
            wb.createSheet("Sheet1");
            wb.write(fos);
        }

        PurchaseController controller = new PurchaseController(new PurchaseProcessor());
        ReflectionTestUtils.setField(controller, "defaultDataDir", tempDir.getAbsolutePath());

        PurchaseExecuteRequest req = new PurchaseExecuteRequest(
                "2026/10/21",
                "供应商A",
                "PO-123",
                null,
                null,
                tempDir.getAbsolutePath(),
                fileA.getName(),
                fileA.getName() // 冲突：同名
        );

        ResponseEntity<OrderExecuteResponse> resp = controller.executePurchase(req);
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().success());
        assertTrue(resp.getBody().message().contains("冲突") || resp.getBody().message().contains("同一个文件"));
    }
}

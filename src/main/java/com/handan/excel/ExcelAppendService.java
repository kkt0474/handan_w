package com.handan.excel;

import com.handan.config.ExcelFilterProperties;
import com.handan.config.MappingConfig;
import com.handan.progress.ExecutionProgress;
import com.handan.progress.ProgressListener;
import com.handan.purchase.PurchaseItem;
import com.handan.purchase.PurchaseSummaryAggregator;
import com.handan.transform.OrderSummaryAggregator;
import com.handan.transform.RowFlattener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.AccessControlException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * Excel 追加与备份服务
 * - 规则：
 *   1. 必须在操作前先对原 result.xlsx 创建一份精确到秒的时间戳备份文件：result_yyyyMMddHHmmss.xlsx
 *   2. 如果备份阶段发生任何错误或异常，必须立即中断整个流程，绝对不继续操作原 result.xlsx
 *   3. 备份成功后，直接向原 result.xlsx 中追加新数据（【客户订单】明细表与【客户订单汇总】汇总表）
 */
@Service
public class ExcelAppendService {

    public static final String DEFAULT_SOURCE_NAME = "result.xlsx";
    public static final String DETAIL_SHEET_NAME = "客户订单";
    public static final String SUMMARY_SHEET_NAME = "客户订单汇总";

    public static final String PURCHASE_DETAIL_SHEET_NAME = "采购单";
    public static final String PURCHASE_SUMMARY_SHEET_NAME = "采购单汇总";
    public static final List<String> PURCHASE_DETAIL_HEADERS = List.of(
            "日期", "供应商", "采购单号", "料号", "单价(USD)", "数量(PCS)", "金额", "交期", "备注"
    );

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    private final ExcelFilterProperties filterProperties;

    public ExcelAppendService() {
        this(new ExcelFilterProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ExcelAppendService(ExcelFilterProperties filterProperties) {
        this.filterProperties = filterProperties != null ? filterProperties : new ExcelFilterProperties();
    }

    /**
     * 执行操作：先备份原文件，备份成功后再向原 Excel 母表直接追加数据（客户订单）
     */
    public File backupAndAppendToOriginal(
            File directory,
            List<List<Object>> detailRows,
            List<List<Object>> summaryRows,
            MappingConfig config,
            ProgressListener listener
    ) throws IOException {
        return executeSafeWorkbookUpdate(directory, null, "新", listener, (workbook, styleCache) -> {
            String detailRange = appendRowsToSheet(
                    workbook,
                    DETAIL_SHEET_NAME,
                    detailRows,
                    RowFlattener.getFullHeaders(config),
                    styleCache
            );
            String summaryRange = appendRowsToSheet(
                    workbook,
                    SUMMARY_SHEET_NAME,
                    summaryRows,
                    OrderSummaryAggregator.SUMMARY_HEADERS,
                    styleCache
            );
            return "【" + DETAIL_SHEET_NAME + "】+" + detailRows.size() + "行 " + detailRange
                    + "，【" + SUMMARY_SHEET_NAME + "】+" + summaryRows.size() + "行 " + summaryRange;
        });
    }

    /**
     * 执行操作：先备份原文件，备份成功后再向原 Excel 母表直接追加采购单数据（【采购单】明细表与【采购单汇总】汇总表）
     *
     * @param directory source.xlsx 与目标母表所在文件夹
     * @param detailRows 待追加的采购单明细数据
     * @param summaryRows 待追加的采购单汇总数据
     * @param listener 进度监听器
     * @return 原 Excel 母表文件对象（已追加新数据）
     * @throws IOException 异常中断
     */
    public File backupAndAppendPurchaseToOriginal(
            File directory,
            List<List<Object>> detailRows,
            List<List<Object>> summaryRows,
            ProgressListener listener
    ) throws IOException {
        return backupAndAppendPurchaseToOriginal(directory, null, detailRows, summaryRows, Collections.emptyList(), listener);
    }

    /**
     * 执行操作：先备份原文件，备份成功后再向原 Excel 母表直接追加采购单数据（【采购单】明细表与【采购单汇总】汇总表）
     *
     * @param directory source.xlsx 与目标母表所在文件夹
     * @param detailRows 待追加的采购单明细数据
     * @param summaryRows 待追加的采购单汇总数据
     * @param purchaseItems 采购单条目（包含计算金额与与源数据比对状态）
     * @param listener 进度监听器
     * @return 原 Excel 母表文件对象（已追加新数据）
     * @throws IOException 异常中断
     */
    public File backupAndAppendPurchaseToOriginal(
            File directory,
            List<List<Object>> detailRows,
            List<List<Object>> summaryRows,
            List<PurchaseItem> purchaseItems,
            ProgressListener listener
    ) throws IOException {
        return backupAndAppendPurchaseToOriginal(directory, null, detailRows, summaryRows, purchaseItems, listener);
    }

    /**
     * 执行操作：先备份原文件，备份成功后再向原 Excel 母表直接追加采购单数据（【采购单】明细表与【采购单汇总】汇总表）
     * 支持传入 excludeFile（如 source.xlsx），防止采购单源文件与目标母表冲突
     *
     * @param directory 目标母表所在文件夹
     * @param excludeFile 排除的 Excel 文件（例如采购源文件 source.xlsx）
     * @param detailRows 待追加的采购单明细数据
     * @param summaryRows 待追加的采购单汇总数据
     * @param purchaseItems 采购单条目（包含计算金额与与源数据比对状态）
     * @param listener 进度监听器
     * @return 原 Excel 母表文件对象（已追加新数据）
     * @throws IOException 异常中断
     */
    public File backupAndAppendPurchaseToOriginal(
            File directory,
            File excludeFile,
            List<List<Object>> detailRows,
            List<List<Object>> summaryRows,
            List<PurchaseItem> purchaseItems,
            ProgressListener listener
    ) throws IOException {
        return executeSafeWorkbookUpdate(directory, excludeFile, "采购单", listener, (workbook, styleCache) -> {
            String detailRange = appendPurchaseDetailRows(
                    workbook,
                    PURCHASE_DETAIL_SHEET_NAME,
                    detailRows,
                    PURCHASE_DETAIL_HEADERS,
                    purchaseItems,
                    styleCache
            );
            String summaryRange = appendRowsToSheet(
                    workbook,
                    PURCHASE_SUMMARY_SHEET_NAME,
                    summaryRows,
                    PurchaseSummaryAggregator.SUMMARY_HEADERS,
                    styleCache
            );
            return "【" + PURCHASE_DETAIL_SHEET_NAME + "】+" + detailRows.size() + "行 " + detailRange
                    + "，【" + PURCHASE_SUMMARY_SHEET_NAME + "】+" + summaryRows.size() + "行 " + summaryRange;
        });
    }

    /**
     * 向指定的目标母表文件直接执行安全备份与采购单数据追加（用户前端显式指定目标文件）
     * 写入前严格校验母表工作表与表头结构，防止误选源单据或其他无关文件
     *
     * @param targetFile 目标母表文件
     * @param detailRows 待追加的采购单明细数据
     * @param summaryRows 待追加的采购单汇总数据
     * @param purchaseItems 采购单条目（包含计算金额与比对状态）
     * @param listener 进度监听器
     * @return 目标母表文件对象（已追加新数据）
     * @throws IOException 异常中断
     */
    public File backupAndAppendPurchaseToSpecificFile(
            File targetFile,
            List<List<Object>> detailRows,
            List<List<Object>> summaryRows,
            List<PurchaseItem> purchaseItems,
            ProgressListener listener
    ) throws IOException {
        validatePurchaseTargetWorkbook(targetFile);

        return executeSafeWorkbookFileUpdate(targetFile, "采购单", listener, (workbook, styleCache) -> {
            String detailRange = appendPurchaseDetailRows(
                    workbook,
                    PURCHASE_DETAIL_SHEET_NAME,
                    detailRows,
                    PURCHASE_DETAIL_HEADERS,
                    purchaseItems,
                    styleCache
            );
            String summaryRange = appendRowsToSheet(
                    workbook,
                    PURCHASE_SUMMARY_SHEET_NAME,
                    summaryRows,
                    PurchaseSummaryAggregator.SUMMARY_HEADERS,
                    styleCache
            );
            return "【" + PURCHASE_DETAIL_SHEET_NAME + "】+" + detailRows.size() + "行 " + detailRange
                    + "，【" + PURCHASE_SUMMARY_SHEET_NAME + "】+" + summaryRows.size() + "行 " + summaryRange;
        });
    }

    /**
     * 严格校验目标母表的结构合法性：必须包含【采购单】工作表和有效表头
     * 若不匹配则直接抛出明确的异常阻断写入，防止因误选文件而损坏非母表文件
     */
    public void validatePurchaseTargetWorkbook(File targetFile) throws IOException {
        if (targetFile == null || !targetFile.isFile()) {
            throw new IllegalArgumentException("指定的目标母表文件不存在: "
                    + (targetFile != null ? targetFile.getAbsolutePath() : "null"));
        }

        try (FileInputStream fis = new FileInputStream(targetFile);
             Workbook workbook = WorkbookFactory.create(fis)) {
            Sheet detailSheet = workbook.getSheet(PURCHASE_DETAIL_SHEET_NAME);
            if (detailSheet == null) {
                throw new IllegalArgumentException("【目标母表格式错误 ❌】文件【" + targetFile.getName()
                        + "】中未找到【" + PURCHASE_DETAIL_SHEET_NAME + "】工作表，不是有效的目标母表！请检查是否误选了采购源单据。");
            }
            Row headerRow = findHeaderRow(detailSheet, PURCHASE_DETAIL_HEADERS);
            if (headerRow == null) {
                throw new IllegalArgumentException("【目标母表格式错误 ❌】在文件【" + targetFile.getName()
                        + "】的【" + PURCHASE_DETAIL_SHEET_NAME + "】工作表中未找到有效的采购单表头列。请检查是否误选了其他文件。");
            }
        }
    }

    /**
     * 在指定工作目录中动态解析目标 Excel 母表文件（.xlsx 或 .xls）
     * - 规则：
     *   1. 仅从文件后缀判断（.xlsx 或 .xls，忽略大小写），不再依赖固定文件名
     *   2. 严格排除 Office/WPS 打开时生成的隐藏锁定文件（~$*）
     *   3. 严格排除运行写回时生成的独立临时文件（包含 _temp_ 或以 .tmp 结尾）
     *   4. 严格排除系统自动生成的历史时间戳备份文件（符合 _yyyyMMddHHmmss.xlsx/xls 或包含 _bak）
     *   5. 严格排除传入的 excludeFile（如采购单中的源文件 source.xlsx）
     *   6. 若未检测到目标文件，报错并提示用户
     *   7. 若存在多个目标 Excel 文件导致无法唯一定位读写源，抛出异常并在前端清晰报错
     */
    public File resolveTargetExcelFile(File directory, File excludeFile, ProgressListener listener) {
        if (directory == null || !directory.isDirectory()) {
            String err = "指定的工作目录无效或不存在: " + (directory != null ? directory.getAbsolutePath() : "null");
            if (listener != null) {
                listener.onProgress(ExecutionProgress.failed("DIR_NOT_FOUND", "Excel目录检查", err));
            }
            throw new IllegalArgumentException(err);
        }

        File[] files = directory.listFiles((dir, name) -> {
            if (name == null) return false;
            String lower = name.trim().toLowerCase();
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                return false;
            }
            if (filterProperties.shouldExclude(name)) {
                return false;
            }
            return true;
        });

        List<File> candidates = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                if (excludeFile != null) {
                    try {
                        if (f.getCanonicalPath().equalsIgnoreCase(excludeFile.getCanonicalPath())) {
                            continue;
                        }
                    } catch (IOException e) {
                        if (f.getAbsolutePath().equalsIgnoreCase(excludeFile.getAbsolutePath())) {
                            continue;
                        }
                    }
                }
                candidates.add(f);
            }
        }

        if (candidates.isEmpty()) {
            String err = "未在工作目录【" + directory.getAbsolutePath() + "】中检测到任何目标 Excel 母表文件（.xlsx 或 .xls），请先放入目标 Excel 文件。";
            if (listener != null) {
                listener.onProgress(ExecutionProgress.failed("EXCEL_NOT_FOUND", "Excel文件检查", err));
            }
            throw new IllegalArgumentException(err);
        }

        if (candidates.size() > 1) {
            List<String> fileNames = candidates.stream().map(File::getName).sorted().toList();
            String err = "在工作目录【" + directory.getAbsolutePath() + "】下检测到多个 Excel 文件（" + String.join("，", fileNames)
                    + "），无法确定读写目标源，请仅保留一个目标 Excel 母表文件。";
            if (listener != null) {
                listener.onProgress(ExecutionProgress.failed("MULTIPLE_EXCEL_FILES", "Excel文件冲突", err));
            }
            throw new IllegalArgumentException(err);
        }

        return candidates.get(0);
    }

    public File resolveTargetExcelFile(File directory, ProgressListener listener) {
        return resolveTargetExcelFile(directory, null, listener);
    }

    /**
     * 事务性安全写入工作簿核心模板方法（Template Callback 模式 / Execute Around Idiom）
     * 统一管控：
     * 1. 目标文件物理存在性与唯一性校验
     * 2. 操作系统级与 Office 级独占锁探测
     * 3. 精确到秒的时间戳自动备份（故障强熔断）
     * 4. 独立临时文件持久化与非空校验
     * 5. 跨平台原子性安全替换（Files.move REPLACE_EXISTING）
     * 6. 异常兜底与临时资源物理销毁
     */
    private File executeSafeWorkbookUpdate(
            File directory,
            File excludeFile,
            String moduleName,
            ProgressListener listener,
            WorkbookWriteAction action
    ) throws IOException {
        File originalFile = resolveTargetExcelFile(directory, excludeFile, listener);
        return executeSafeWorkbookFileUpdate(originalFile, moduleName, listener, action);
    }

    /**
     * 事务性安全写入工作簿核心模板方法（针对确定目标文件进行原子更新）
     * 统一管控：
     * 1. 目标文件物理存在性与文件锁探测
     * 2. 操作系统级与 Office 级独占锁探测
     * 3. 精确到秒的时间戳自动备份（故障强熔断）
     * 4. 独立临时文件持久化与非空校验
     * 5. 跨平台原子性安全替换（Files.move REPLACE_EXISTING）
     * 6. 异常兜底与临时资源物理销毁
     */
    public File executeSafeWorkbookFileUpdate(
            File originalFile,
            String moduleName,
            ProgressListener listener,
            WorkbookWriteAction action
    ) throws IOException {
        if (!originalFile.isFile()) {
            throw new IllegalArgumentException("指定的工作簿文件不存在或不是普通文件: " + originalFile.getAbsolutePath());
        }

        File directory = originalFile.getParentFile();

        if ("采购单".equals(moduleName)) {
            listener.onProgress(ExecutionProgress.running("EXCEL_APPEND", "双表数据追加",
                    "正在打开主文件 " + originalFile.getName() + "，准备向【" + PURCHASE_DETAIL_SHEET_NAME
                            + "】与【" + PURCHASE_SUMMARY_SHEET_NAME + "】追加数据..."));
        }

        // 0. 前置检查：主文件是否正被 Excel / WPS 打开占用，若被占用立即中断并报错
        checkFileOccupancy(originalFile, directory, listener);

        // 1. 备份阶段：严格防御，出现任何异常直接中断流程
        String backupFileName = performSafeBackup(originalFile, directory, listener);

        // 2. 写入阶段：使用安全的独立临时文件写入后原子替换，防止流竞争或文件截断损坏
        String originalName = originalFile.getName();
        int dotIdx = originalName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
        String ext = dotIdx > 0 ? originalName.substring(dotIdx) : ".xlsx";
        File tempFile = new File(directory, baseName + "_temp_" + System.currentTimeMillis() + ext);
        String summaryDesc;

        try {
            try (FileInputStream fis = new FileInputStream(originalFile);
                 Workbook workbook = WorkbookFactory.create(fis)) {

                Map<String, CellStyle> styleCache = new HashMap<>();

                try {
                    summaryDesc = action.execute(workbook, styleCache);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("Excel 业务数据装配写入失败: " + e.getMessage(), e);
                }

                // 强制让 Excel 在打开时自动重新计算所有公式
                workbook.setForceFormulaRecalculation(true);

                // 安全移除 calculationChain.xml，防止公式计算链与新行不一致触发 Excel 修复提示
                removeCalculationChainSafely(workbook);

                // 写入独立的临时文件
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    workbook.write(fos);
                    fos.flush();
                }
            }

            // 校验临时文件
            if (!tempFile.exists() || tempFile.length() == 0) {
                throw new IOException("临时写回文件生成失败或大小为0: " + tempFile.getName());
            }

            // 安全原子替换原文件
            Files.move(tempFile.toPath(), originalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }

        listener.onProgress(ExecutionProgress.success("EXCEL_FINISH", "写入完成",
                (moduleName != null && !moduleName.isEmpty() ? (moduleName + "数据") : "数据")
                        + "已成功直接追加写入主文件: " + originalFile.getName() + "（" + summaryDesc
                        + "，备份副本为: " + backupFileName + "）。"));

        return originalFile;
    }

    /**
     * 前置严格检查目标文件是否被外部办公软件（如 Microsoft Excel、WPS 等）打开占用
     * 若被占用则立即记录失败并抛出异常阻断整个流程，严禁执行备份和写操作
     */
    private void checkFileOccupancy(File originalFile, File directory, ProgressListener listener) throws IOException {
        String fileName = originalFile.getName();

        // 1. 检查是否存在 Office/WPS 生成的锁定隐藏文件（~$fileName）
        File officeLockFile = new File(directory, "~$" + fileName);
        if (officeLockFile.exists()) {
            String err = "【写入中止 ❌】检测到主文件 " + fileName + " 当前正被 Microsoft Excel / WPS 打开占用（检测到锁定文件: "
                    + officeLockFile.getName() + "）。为防止写入冲突及数据被 Excel 覆盖，流程已拦截中止！请在电脑上先完全保存并关闭 "
                    + fileName + "，然后重新点击执行。";
            listener.onProgress(ExecutionProgress.failed("FILE_LOCKED", "文件占用检查", err));
            throw new IOException(err);
        }

        // 2. 检查操作系统级别的文件读写独占锁
        try (RandomAccessFile raf = new RandomAccessFile(originalFile, "rw");
             FileChannel channel = raf.getChannel()) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                String err = "【写入中止 ❌】主文件 " + fileName + " 当前正被系统其他进程独占打开，无法安全写入！请先关闭占用该文件的程序后再试。";
                listener.onProgress(ExecutionProgress.failed("FILE_LOCKED", "文件占用检查", err));
                throw new IOException(err);
            }
            lock.release();
        } catch (OverlappingFileLockException e) {
            String err = "【写入中止 ❌】主文件 " + fileName + " 当前正处于读写锁定中，无法安全写入！请先关闭相关程序后再试。";
            listener.onProgress(ExecutionProgress.failed("FILE_LOCKED", "文件占用检查", err));
            throw new IOException(err, e);
        } catch (FileNotFoundException | AccessControlException e) {
            String err = "【写入中止 ❌】主文件 " + fileName + " 当前无法以读写方式打开（可能已被 Excel 独占锁定或权限不足）。请先关闭 Excel 后重试。";
            listener.onProgress(ExecutionProgress.failed("FILE_LOCKED", "文件占用检查", err));
            throw new IOException(err, e);
        } catch (IOException e) {
            String err = "【写入中止 ❌】检测到主文件 " + fileName + " 处于锁定或占用状态（" + e.getMessage() + "），请先关闭正在使用它的程序后再试。";
            listener.onProgress(ExecutionProgress.failed("FILE_LOCKED", "文件占用检查", err));
            throw new IOException(err, e);
        }
    }

    /**
     * 执行物理备份并进行完整性检查，任何异常直接抛出阻断整个流程
     */
    private String performSafeBackup(File originalFile, File directory, ProgressListener listener) throws IOException {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String originalName = originalFile.getName();
        int dotIdx = originalName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? originalName.substring(0, dotIdx) : originalName;
        String ext = dotIdx > 0 ? originalName.substring(dotIdx) : ".xlsx";
        String backupFileName = baseName + "_" + timestamp + ext;
        File backupFile = new File(directory, backupFileName);

        listener.onProgress(ExecutionProgress.running("EXCEL_BACKUP", "创建安全备份",
                "正在为 " + originalName + " 创建精确到秒的备份文件: " + backupFileName + "..."));

        try {
            Files.copy(originalFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            if (!backupFile.exists() || backupFile.length() == 0) {
                throw new IOException("备份文件创建后校验失败（文件不存在或大小为0字节）: " + backupFileName);
            }

            listener.onProgress(ExecutionProgress.success("EXCEL_BACKUP_SUCCESS", "安全备份成功",
                    "原文件已成功备份至: " + backupFileName + "，即将开始向主文件追加数据。"));
            return backupFileName;
        } catch (Exception e) {
            String err = "【严重错误】在备份 " + originalName + " 时出现异常，流程已强制紧急中断，绝不修改原文件！异常原因: " + e.getMessage();
            listener.onProgress(ExecutionProgress.failed("BACKUP_ABORT", "备份异常中断", err));
            throw new IOException(err, e);
        }
    }

    /**
     * 向指定工作表追加行数据：智能寻找真实末尾非空数据行，跳过幽灵空行
     *
     * @return 实际写入的 Excel 行号范围（如 "第2-7行"）
     */
    private String appendRowsToSheet(
            Workbook workbook,
            String sheetName,
            List<List<Object>> rows,
            List<String> defaultHeaders,
            Map<String, CellStyle> styleCache
    ) {
        Sheet sheet = getOrCreateSheet(workbook, sheetName);

        if (sheet.getPhysicalNumberOfRows() == 0) {
            // Sheet 完全为空，先创建第 0 行表头
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < defaultHeaders.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(defaultHeaders.get(c));
            }
        }

        // 智能定位 Sheet 中的真实表头行（扫描前 5 行找到包含核心关键字的行）
        Row actualHeaderRow = findHeaderRow(sheet, defaultHeaders);

        // 按表头名称匹配每个待写入字段对应的物理列（找不到则明确报错中止，避免数据错位）
        int[] colIndexes = resolveColumnIndexes(sheetName, actualHeaderRow, defaultHeaders);

        // 以第一个字段所在列（日期/客户/供应商）是否有内容判断真实数据末尾
        int startRowIndex = findNextAvailableRow(sheet, colIndexes.length > 0 ? colIndexes[0] : 0);
        if (startRowIndex == 0) {
            startRowIndex = (actualHeaderRow != null ? actualHeaderRow.getRowNum() : 0) + 1;
        }

        // 写入所有追加行
        for (int r = 0; r < rows.size(); r++) {
            List<Object> rowData = rows.get(r);
            int targetRowIndex = startRowIndex + r;
            Row row = sheet.getRow(targetRowIndex);
            if (row == null) {
                row = sheet.createRow(targetRowIndex);
            }
            for (int c = 0; c < rowData.size() && c < colIndexes.length; c++) {
                int physicalCol = colIndexes[c];
                Cell cell = row.getCell(physicalCol);
                if (cell == null) {
                    cell = row.createCell(physicalCol);
                    if (targetRowIndex > 0 && sheet.getRow(targetRowIndex - 1) != null) {
                        Cell prevCell = sheet.getRow(targetRowIndex - 1).getCell(physicalCol);
                        if (prevCell != null && prevCell.getCellStyle() != null) {
                            cell.setCellStyle(prevCell.getCellStyle());
                        }
                    }
                }
                Object val = rowData.get(c);

                String defaultHeader = defaultHeaders.get(c);
                String sheetHeader = (actualHeaderRow != null && actualHeaderRow.getCell(physicalCol) != null)
                        ? getCellTextSafely(actualHeaderRow.getCell(physicalCol)) : "";

                writeCellValue(workbook, cell, val, defaultHeader, sheetHeader, styleCache);
            }
        }

        int excelStartRow = startRowIndex + 1; // 转换为 Excel 1-based 行号
        int excelEndRow = startRowIndex + rows.size();
        return "第" + excelStartRow + (rows.size() > 1 ? ("-" + excelEndRow) : "") + "行";
    }

    /**
     * 向【采购单】明细表追加行数据：
     * 1. 智能定位真实末尾行
     * 2. 采购单号、料号等文本正常写入，并继承上一行单元格样式与边框
     * 3. 单价、数量按数值千分位格式写入
     * 4. 金额列：
     *    - 写入自适应动态公式（如 =E16*F16，跟随单价与数量列物理位置自适应）
     *    - 写入计算数值与美式金额格式（#,##0.00）
     *    - 比对计算值与 source 中的 Amount(USD)，有差异则单元格背景色设置为红色（IndexedColors.RED）
     */
    private String appendPurchaseDetailRows(
            Workbook workbook,
            String sheetName,
            List<List<Object>> rows,
            List<String> defaultHeaders,
            List<PurchaseItem> purchaseItems,
            Map<String, CellStyle> styleCache
    ) {
        Sheet sheet = getOrCreateSheet(workbook, sheetName);

        if (sheet.getPhysicalNumberOfRows() == 0) {
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < defaultHeaders.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(defaultHeaders.get(c));
            }
        }

        Row actualHeaderRow = findHeaderRow(sheet, defaultHeaders);
        int[] colIndexes = resolveColumnIndexes(sheetName, actualHeaderRow, defaultHeaders);

        int startRowIndex = findNextAvailableRow(sheet, colIndexes.length > 0 ? colIndexes[0] : 0);
        if (startRowIndex == 0) {
            startRowIndex = (actualHeaderRow != null ? actualHeaderRow.getRowNum() : 0) + 1;
        }

        // 定位单价、数量、金额对应的物理列索引
        int priceColIndex = -1;
        int qtyColIndex = -1;
        int amountColIndex = -1;
        for (int i = 0; i < defaultHeaders.size() && i < colIndexes.length; i++) {
            String h = defaultHeaders.get(i);
            if (h.contains("单价")) {
                priceColIndex = colIndexes[i];
            } else if (h.contains("数量")) {
                qtyColIndex = colIndexes[i];
            } else if (h.contains("金额")) {
                amountColIndex = colIndexes[i];
            }
        }

        String priceColLetter = priceColIndex >= 0 ? CellReference.convertNumToColString(priceColIndex) : "";
        String qtyColLetter = qtyColIndex >= 0 ? CellReference.convertNumToColString(qtyColIndex) : "";
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

        for (int r = 0; r < rows.size(); r++) {
            List<Object> rowData = rows.get(r);
            int targetRowIndex = startRowIndex + r;
            int excelRowNum = targetRowIndex + 1; // 1-based Excel row number
            Row row = sheet.getRow(targetRowIndex);
            if (row == null) {
                row = sheet.createRow(targetRowIndex);
            }

            PurchaseItem purchaseItem = (purchaseItems != null && r < purchaseItems.size()) ? purchaseItems.get(r) : null;

            // 先写入非金额列（确保单价和数量已被正确写入）
            for (int c = 0; c < rowData.size() && c < colIndexes.length; c++) {
                int physicalCol = colIndexes[c];
                if (physicalCol == amountColIndex) {
                    continue;
                }
                Cell cell = row.getCell(physicalCol);
                if (cell == null) {
                    cell = row.createCell(physicalCol);
                    if (targetRowIndex > 0 && sheet.getRow(targetRowIndex - 1) != null) {
                        Cell prevCell = sheet.getRow(targetRowIndex - 1).getCell(physicalCol);
                        if (prevCell != null && prevCell.getCellStyle() != null) {
                            cell.setCellStyle(prevCell.getCellStyle());
                        }
                    }
                }
                Object val = rowData.get(c);
                String defaultHeader = defaultHeaders.get(c);
                String sheetHeader = (actualHeaderRow != null && actualHeaderRow.getCell(physicalCol) != null)
                        ? getCellTextSafely(actualHeaderRow.getCell(physicalCol)) : "";
                writeCellValue(workbook, cell, val, defaultHeader, sheetHeader, styleCache);
            }

            // 处理金额列
            if (amountColIndex >= 0) {
                Cell cell = row.getCell(amountColIndex);
                if (cell == null) {
                    cell = row.createCell(amountColIndex);
                    if (targetRowIndex > 0 && sheet.getRow(targetRowIndex - 1) != null) {
                        Cell prevCell = sheet.getRow(targetRowIndex - 1).getCell(amountColIndex);
                        if (prevCell != null && prevCell.getCellStyle() != null) {
                            cell.setCellStyle(prevCell.getCellStyle());
                        }
                    }
                }

                BigDecimal calcAmount = (purchaseItem != null) ? purchaseItem.calculateAmount() : null;
                if (calcAmount == null) {
                    Object val = (colIndexes.length > 6 && rowData.size() > 6) ? rowData.get(6) : null;
                    if (val instanceof BigDecimal bd) {
                        calcAmount = bd;
                    } else if (val instanceof Number num) {
                        calcAmount = BigDecimal.valueOf(num.doubleValue()).setScale(2, RoundingMode.HALF_UP);
                    } else if (val != null) {
                        try {
                            calcAmount = new BigDecimal(String.valueOf(val).replaceAll("[,，\\s$￥USDusd]", "")).setScale(2, RoundingMode.HALF_UP);
                        } catch (Exception ignored) {}
                    }
                }

                if (!priceColLetter.isEmpty() && !qtyColLetter.isEmpty()) {
                    String formula = priceColLetter + excelRowNum + "*" + qtyColLetter + excelRowNum;
                    cell.setCellFormula(formula);
                }

                if (calcAmount != null) {
                    cell.setCellValue(calcAmount.doubleValue());
                } else {
                    cell.setCellValue(0.00);
                }

                boolean isMismatch = (purchaseItem != null && !purchaseItem.isAmountMatchingSource());
                applyAmountCellStyle(workbook, cell, isMismatch, styleCache);
            }
        }

        int excelStartRow = startRowIndex + 1;
        int excelEndRow = startRowIndex + rows.size();
        return "第" + excelStartRow + (rows.size() > 1 ? ("-" + excelEndRow) : "") + "行";
    }

    /**
     * 为金额单元格设置格式与样式：
     * - 固定使用美式货币格式 `#,##0.00`
     * - 若计算金额与 source.xlsx 的 Amount(USD) 不一致，将单元格背景色设置为红色（IndexedColors.RED）
     * - 若一致，保留原有背景填充色与斑马纹格式
     * - 继承原单元格的边框、字体、对齐等全部样式属性
     */
    private void applyAmountCellStyle(
            Workbook workbook,
            Cell cell,
            boolean isMismatch,
            Map<String, CellStyle> styleCache
    ) {
        CellStyle original = cell.getCellStyle();
        short originalIndex = original != null ? original.getIndex() : 0;
        String key = originalIndex + "|" + (isMismatch ? "AMOUNT_RED" : "AMOUNT_NORMAL");

        CellStyle style = styleCache.computeIfAbsent(key, k -> {
            CellStyle cs = workbook.createCellStyle();
            if (original != null) {
                cs.cloneStyleFrom(original);
            }
            DataFormat df = workbook.createDataFormat();
            cs.setDataFormat(df.getFormat("#,##0.00"));
            if (isMismatch) {
                cs.setFillForegroundColor(IndexedColors.RED.getIndex());
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            return cs;
        });
        cell.setCellStyle(style);
    }

    /**
     * 智能定位下一个可用的追加写入行：
     * 针对所有 4 个 Sheet 表格，严格以第 1 列（列索引 0）是否有实质内容（日期、客户、供应商等字符串或有效日期）为准：
     * - 【客户订单】第 1 列为“日期”
     * - 【客户订单汇总】第 1 列为“客户”
     * - 【采购单】第 1 列为“日期”
     * - 【采购单汇总】第 1 列为“供应商”
     * 从后向前遍历，找到最后一个在第 1 列包含真正业务数据的行，返回其下一行（r + 1）。
     * 彻底避免其他列预设公式（如金额计算=0、"-"占位符等）或预设斑马底色造成的行号误判。
     */
    private int findNextAvailableRow(Sheet sheet, int keyCol) {
        if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
            return 0;
        }
        for (int r = sheet.getLastRowNum(); r >= 0; r--) {
            Row row = sheet.getRow(r);
            if (row != null && isKeyColumnWithRealData(row, keyCol)) {
                return r + 1;
            }
        }
        return 0;
    }

    /**
     * 按表头名称把待写入字段映射到物理列索引。
     * 匹配规则：先做规范化精确匹配（去空白、全角括号转半角、忽略大小写），再退而求其次做唯一包含匹配；
     * 任一字段找不到对应表头则抛出明确异常，避免数据写错列。
     */
    private int[] resolveColumnIndexes(String sheetName, Row headerRow, List<String> headers) {
        int[] result = new int[headers.size()];
        if (headerRow == null) {
            throw new IllegalStateException("【" + sheetName + "】中未找到表头行，无法确定写入列，已中止写入。");
        }
        short lastCell = headerRow.getLastCellNum();
        for (int i = 0; i < headers.size(); i++) {
            String target = normalizeHeader(headers.get(i));
            int exact = -1;
            int fuzzy = -1;
            int fuzzyCount = 0;
            for (int c = 0; c < lastCell; c++) {
                String h = normalizeHeader(getCellTextSafely(headerRow.getCell(c)));
                if (h.isEmpty()) continue;
                if (h.equals(target)) {
                    exact = c;
                    break;
                }
                if (h.contains(target) || target.contains(h)) {
                    fuzzy = c;
                    fuzzyCount++;
                }
            }
            if (exact >= 0) {
                result[i] = exact;
            } else if (fuzzyCount == 1) {
                result[i] = fuzzy;
            } else {
                throw new IllegalStateException("【" + sheetName + "】表头中找不到字段「" + headers.get(i)
                        + "」对应的列，已中止写入，请检查目标 Excel 母表的表头名称。");
            }
        }
        return result;
    }

    private String normalizeHeader(String s) {
        if (s == null) return "";
        return s.replace('（', '(').replace('）', ')').replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 判断指定行在关键列（日期/客户/供应商所在列）是否具有真实业务数据（字符串或日期）：
     * 严格排除仅有背景颜色、斑马纹、边框、空样式或空字符串的空白行
     */
    private boolean isKeyColumnWithRealData(Row row, int keyCol) {
        if (row == null) {
            return false;
        }
        Cell cell = row.getCell(keyCol);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return false;
        }

        // 文本类型：去除首尾空格后必须非空
        if (cell.getCellType() == CellType.STRING) {
            String s = cell.getStringCellValue();
            return s != null && !s.trim().isEmpty();
        }

        // Excel 中日期通常以 NUMERIC 类型存储并带有 DateFormat
        if (cell.getCellType() == CellType.NUMERIC) {
            try {
                if (DateUtil.isCellDateFormatted(cell)) {
                    return true;
                }
            } catch (Exception ignored) {}
            // 如果单元格是数值，排除 0 / 0.00 等默认未输入值
            String formatted = DATA_FORMATTER.formatCellValue(cell).trim();
            return !formatted.isEmpty() && !"0".equals(formatted) && !"0.00".equals(formatted);
        }

        // 公式类型：若第 1 列存在公式，检查求值后的文本是否非空且排除 0
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                String text = DATA_FORMATTER.formatCellValue(cell).trim();
                return !text.isEmpty() && !"0".equals(text) && !"0.00".equals(text);
            } catch (Exception e) {
                return false;
            }
        }

        // 兜底校验
        try {
            String text = DATA_FORMATTER.formatCellValue(cell).trim();
            return !text.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 智能获取或创建工作表（支持前后空格兼容与大小写宽松匹配）
     */
    private Sheet getOrCreateSheet(Workbook workbook, String sheetName) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet != null) {
            return sheet;
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (workbook.getSheetName(i).trim().equalsIgnoreCase(sheetName.trim())) {
                return workbook.getSheetAt(i);
            }
        }
        return workbook.createSheet(sheetName);
    }

    /**
     * 在前 20 行中选出与待写入字段名称匹配数最多的一行作为表头行
     * （避免把标题行、说明行误判为表头）；无任何匹配时回退到关键字扫描
     */
    private Row findHeaderRow(Sheet sheet, List<String> headers) {
        if (sheet == null) return null;
        Row best = null;
        int bestScore = 0;
        int limit = Math.min(20, sheet.getLastRowNum());
        for (int i = 0; i <= limit; i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            int score = 0;
            for (String h : headers) {
                String target = normalizeHeader(h);
                for (Cell cell : r) {
                    if (normalizeHeader(getCellTextSafely(cell)).equals(target)) {
                        score++;
                        break;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best != null ? best : findHeaderRow(sheet);
    }

    /**
     * 智能定位表头行：在 Sheet 的前 5 行中寻找包含“客户/订单/料号/数量/供应商/采购”等特征关键词的行
     */
    private Row findHeaderRow(Sheet sheet) {
        if (sheet == null) return null;
        for (int i = 0; i <= Math.min(5, sheet.getLastRowNum()); i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            for (Cell cell : r) {
                String text = getCellTextSafely(cell);
                if (text.contains("客户") || text.contains("订单") || text.contains("料号")
                        || text.contains("数量") || text.contains("供应商") || text.contains("采购")) {
                    return r;
                }
            }
        }
        return sheet.getRow(0);
    }

    /**
     * 安全获取单元格文本值（兼容 STRING, NUMERIC, BOOLEAN, FORMULA 等各种单元格类型）
     * 杜绝 "Cannot get a STRING value from a NUMERIC formula cell" 异常
     */
    private String getCellTextSafely(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            return DATA_FORMATTER.formatCellValue(cell);
        } catch (Exception e) {
            try {
                if (cell.getCellType() == CellType.STRING) {
                    return cell.getStringCellValue();
                } else if (cell.getCellType() == CellType.NUMERIC) {
                    return String.valueOf(cell.getNumericCellValue());
                } else if (cell.getCellType() == CellType.BOOLEAN) {
                    return String.valueOf(cell.getBooleanCellValue());
                }
            } catch (Exception ignored) {}
            return "";
        }
    }

    /**
     * 根据列类型与内容智能写入单元格并设置美式千分位格式：
     * - 数量字段：按美式整数千分位 `#,##0` 显示（如 40,000，精准消除 OCR 把千分位识别为 40.000 的问题）
     * - 单价与金额字段：根据 PDF 原始输入字符串探测实际保留的小数位数，动态构造 `#,##0.00` 或 `#,##0.0000` 样式
     * - 文本字段：正常文本写入
     */
    private void writeCellValue(
            Workbook workbook,
            Cell cell,
            Object val,
            String defaultHeader,
            String sheetHeader,
            Map<String, CellStyle> styleCache
    ) {
        if (cell.getCellType() == CellType.FORMULA) {
            cell.setBlank();
        }

        if (val == null) {
            cell.setCellValue("");
            return;
        }

        // 双向判断是否为数量列（只要配置表头或实际 Sheet 表头任一包含数量特征即生效）
        boolean isQtyCol = isQuantityHeader(defaultHeader) || isQuantityHeader(sheetHeader);
        boolean isNumericCol = isQtyCol || isNumericHeader(defaultHeader) || isNumericHeader(sheetHeader);

        // 如果传入的是 Java Number 对象（例如汇总表的总数量、未出货数量）
        if (val instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            if (isQtyCol) {
                applyNumberFormat(workbook, cell, "#,##0", styleCache);
            }
            return;
        }

        String rawStr = String.valueOf(val).trim();
        if (rawStr.isEmpty()) {
            cell.setCellValue("");
            return;
        }

        // 固定占位符（如 "-"），保持文本
        if ("-".equals(rawStr)) {
            cell.setCellValue(rawStr);
            return;
        }

        // 探测原始字符串中的小数位数（数量列强制为 0）
        int decimalPlaces = detectDecimalPlaces(rawStr, isQtyCol);

        // 标准化数字字符串（纠正 OCR 误把数量千分位识别为句号的问题，如 40.000 -> 40000）
        String cleanStr = normalizeNumberString(rawStr, isQtyCol);

        if (isNumericCol) {
            try {
                BigDecimal bd = new BigDecimal(cleanStr);
                cell.setCellValue(bd.doubleValue());

                // 动态构建美式千分位格式
                String formatPattern = buildNumberFormatPattern(decimalPlaces, isQtyCol);
                applyNumberFormat(workbook, cell, formatPattern, styleCache);
                return;
            } catch (Exception ignored) {
                // 如果解析失败则回退至普通文本写入
            }
        }

        // 默认按普通文本写入
        cell.setCellValue(rawStr);
    }

    /**
     * 探测数字字符串中的小数位数：
     * - 如果是数量列，业务上必然是整数，小数位数为 0；
     * - 对于单价、金额等列，查找小数点 '.' 后的位数（如 "0.4400" -> 4, "13200.00" -> 2）
     */
    private int detectDecimalPlaces(String text, boolean isQuantityColumn) {
        if (isQuantityColumn || text == null) {
            return 0;
        }
        // 去除空格和千分位逗号
        String clean = text.replaceAll("[,，\\s]", "");
        int dotIndex = clean.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < clean.length() - 1) {
            String fraction = clean.substring(dotIndex + 1);
            // 确保小数点后全为数字
            if (fraction.matches("^\\d+$")) {
                return fraction.length();
            }
        }
        return 0;
    }

    /**
     * 动态构建美式千分位 Format Pattern：
     * - 数量列或 0 位小数：`#,##0`（显示如 40,000）
     * - N 位小数：`#,##0.` + N 个 `0`（如 2 位为 `#,##0.00`，4 位为 `#,##0.0000`）
     */
    private String buildNumberFormatPattern(int decimalPlaces, boolean isQuantityColumn) {
        if (isQuantityColumn || decimalPlaces <= 0) {
            return "#,##0";
        }
        return "#,##0." + "0".repeat(decimalPlaces);
    }

    /**
     * 为单元格安全应用 DataFormat 样式：
     * 以单元格模板原有样式为基础（同一工作簿内克隆，保留底色、边框、字体、对齐），仅替换数字格式；
     * 以「原样式索引 + 数字格式」为键缓存，避免样式数量膨胀
     */
    private void applyNumberFormat(Workbook workbook, Cell cell, String pattern, Map<String, CellStyle> styleCache) {
        if (pattern == null || pattern.isEmpty()) {
            return;
        }
        CellStyle original = cell.getCellStyle();
        String key = original.getIndex() + "|" + pattern;
        CellStyle style = styleCache.computeIfAbsent(key, k -> {
            CellStyle cs = workbook.createCellStyle();
            cs.cloneStyleFrom(original);
            DataFormat df = workbook.createDataFormat();
            cs.setDataFormat(df.getFormat(pattern));
            return cs;
        });
        cell.setCellStyle(style);
    }

    /**
     * 智能清洗并纠正数字文本：
     * 1. 清除千分位逗号、空格，去除常见的离散单位后缀（如 PCS, 个, 件等）
     * 2. 识别并纠偏 OCR 常见的“点号千分位误识别”：
     *    - 对于【数量】列，如果包含形如 40.000 或 12.345.678（点号后面恰好是 3 位数字），将点号还原为真实千分位整数（40000）
     */
    private String normalizeNumberString(String text, boolean isQuantityColumn) {
        if (text == null) return "";
        String s = text.replaceAll("[,，\\s]", "");

        if (isQuantityColumn) {
            // 清除常见的数量单位后缀
            s = s.replaceAll("(?i)(pcs|pc|个|件|箱|套|支|只)$", "").trim();

            // 针对数量列的点号千分位纠偏（形如 40.000 或 12.345.678）
            if (s.matches("^-?\\d+(\\.\\d{3})+$")) {
                s = s.replace(".", "");
            }
        }

        return s;
    }

    /**
     * 判断表头是否为数量类字段（支持中英文多样化表头：数量、qty、quantity、pcs、总数量、未出货数量等）
     */
    private boolean isQuantityHeader(String header) {
        if (header == null) return false;
        String h = header.toLowerCase();
        return h.contains("数量") || h.contains("qty") || h.contains("quantity") || h.contains("pcs")
                || h.contains("箱") || h.contains("件");
    }

    /**
     * 判断表头是否为数值类字段（单价、数量、金额等）
     */
    private boolean isNumericHeader(String header) {
        if (header == null) return false;
        String h = header.toLowerCase();
        return h.contains("单价") || h.contains("数量") || h.contains("金额")
                || h.contains("price") || h.contains("qty") || h.contains("amount");
    }

    /**
     * 判断字符串是否为标准纯数字（整数或浮点数）
     */
    private boolean isPureNumber(String str) {
        if (str == null || str.isEmpty()) return false;
        return str.matches("^-?\\d+(\\.\\d+)?$");
    }

    /**
     * 安全移除 CalculationChain（calcChain.xml），防止 Excel 打开时报“发现部分内容有问题”并弹出修复提示
     * Excel 会在打开时自动且无感地基于当前工作表重建计算链
     */
    private void removeCalculationChainSafely(Workbook workbook) {
        if (workbook instanceof XSSFWorkbook xssfWorkbook) {
            try {
                org.apache.poi.xssf.model.CalculationChain calcChain = xssfWorkbook.getCalculationChain();
                if (calcChain != null) {
                    java.lang.reflect.Method removeRelation = org.apache.poi.ooxml.POIXMLDocumentPart.class.getDeclaredMethod("removeRelation", org.apache.poi.ooxml.POIXMLDocumentPart.class);
                    removeRelation.setAccessible(true);
                    removeRelation.invoke(xssfWorkbook, calcChain);
                }
            } catch (Exception ignored) {
            }
        }
    }
}

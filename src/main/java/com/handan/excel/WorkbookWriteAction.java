package com.handan.excel;

import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Excel 工作簿写入操作回调接口（Template Callback 模式 / Execute Around Idiom）
 * <p>
 * 允许业务方专注于具体的 Sheet 数据装配、单元格格式化与样式定制，
 * 由底层的 {@link ExcelAppendService} 统一管理文件独占锁检查、时间戳物理备份、临时文件安全写入、
 * 原子化文件替换、公式重算标记以及异常时的清理与事务回滚。
 */
@FunctionalInterface
public interface WorkbookWriteAction {

    /**
     * 在打开的工作簿中执行具体的写入与格式化操作
     *
     * @param workbook   已打开的 Excel 工作簿对象
     * @param styleCache 单元格样式复用缓存池，防止样式数量爆炸
     * @return 业务层执行摘要描述（如写入的行号范围等）
     * @throws Exception 业务写入或 I/O 处理异常
     */
    String execute(Workbook workbook, Map<String, CellStyle> styleCache) throws Exception;
}

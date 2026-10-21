package com.handan.extractor;

import com.handan.config.MappingConfig;
import java.io.File;

/**
 * 单据提取器顶层统一契约接口（Strategy Pattern）
 */
public interface DocumentExtractor {

    /**
     * 从指定单据文件（PDF 电子件/扫描件、PNG 图片等）中提取元数据与二维表格数据
     *
     * @param sourceFile 本地单据文件（PDF 或 PNG 图片）
     * @param config 字段映射配置
     * @return 提取出的元数据与表格行数据
     * @throws Exception 提取或渲染异常
     */
    ExtractedResult extract(File sourceFile, MappingConfig config) throws Exception;
}

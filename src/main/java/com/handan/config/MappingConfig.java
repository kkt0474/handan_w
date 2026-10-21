package com.handan.config;

import java.util.List;

/**
 * 映射整体配置模型
 *
 * @param sheetName 目标工作表名称（默认为“明细清单”或“客户订单”）
 * @param columns 各列字段映射定义列表
 */
public record MappingConfig(
        String sheetName,
        List<ColumnMapping> columns
) {}

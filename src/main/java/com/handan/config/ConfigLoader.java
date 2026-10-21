package com.handan.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 配置文件加载与校验器
 */
public final class ConfigLoader {

    private record ConfigFile(String version, MappingConfig excelTable) {}

    private ConfigLoader() {}

    /**
     * 读取并严密校验 mapping-config.yaml
     *
     * @param file 配置文件路径
     * @return 经过校验的 MappingConfig 对象
     * @throws IOException 文件无法读取
     * @throws IllegalArgumentException 语法或字段约束错误
     */
    public static MappingConfig load(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("未找到映射配置文件: " + (file == null ? "null" : file.getAbsolutePath()));
        }

        ObjectMapper om = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ConfigFile cf = om.readValue(file, ConfigFile.class);
        MappingConfig cfg = cf.excelTable();
        if (cfg == null || cfg.columns() == null || cfg.columns().isEmpty()) {
            throw new IllegalArgumentException("配置文件内容不完整，缺少 excelTable.columns 定义: " + file.getName());
        }

        for (ColumnMapping c : cfg.columns()) {
            if (c.excelHeader() == null || c.excelHeader().isBlank()) {
                throw new IllegalArgumentException("列配置缺少有效的 excelHeader 表头名称");
            }
            if (c.sourceType() == null) {
                throw new IllegalArgumentException("列 [" + c.excelHeader() + "] 缺少有效的 sourceType (METADATA 或 TABLE)");
            }
            if (c.sourceType() == SourceType.METADATA) {
                if (c.pattern() == null || c.pattern().isBlank()) {
                    throw new IllegalArgumentException("METADATA 列 [" + c.excelHeader() + "] 必须配置 pattern 正则表达式");
                }
                // 预编译校验正则表达式合法性
                Pattern.compile(c.pattern());
            } else if (c.tableColumnIndex() == null || c.tableColumnIndex() < 0) {
                throw new IllegalArgumentException("TABLE 列 [" + c.excelHeader() + "] 必须配置有效的 tableColumnIndex (>= 0)");
            }
        }

        String sheet = cfg.sheetName() == null || cfg.sheetName().isBlank() ? "客户订单" : cfg.sheetName();
        return new MappingConfig(sheet, cfg.columns());
    }
}

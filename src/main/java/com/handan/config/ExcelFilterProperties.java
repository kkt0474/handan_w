package com.handan.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Excel 文件过滤规则配置属性
 * 支持通过 application.yml 进行灵活配置与热调整，避免将过滤规则硬编码在程序中
 */
@Component
@ConfigurationProperties(prefix = "handan.excel.filter")
public class ExcelFilterProperties {

    /**
     * 排除的文件名前缀（如 Office/WPS 锁文件 ~$、系统隐藏文件 .）
     */
    private List<String> excludePrefixes = new ArrayList<>(List.of("~$", "."));

    /**
     * 排除的文件名包含特征（如临时文件 _temp_、历史备份 _bak）
     */
    private List<String> excludeContains = new ArrayList<>(List.of("_temp_", "_bak"));

    /**
     * 排除的后缀扩展名（如 .tmp）
     */
    private List<String> excludeSuffixes = new ArrayList<>(List.of(".tmp"));

    /**
     * 排除的正则表达式（如精确到秒的14位数字时间戳备份文件）
     */
    private List<String> excludeRegexPatterns = new ArrayList<>(List.of(".*_\\d{14}\\.(xlsx|xls)$"));

    public ExcelFilterProperties() {
    }

    public List<String> getExcludePrefixes() {
        return excludePrefixes;
    }

    public void setExcludePrefixes(List<String> excludePrefixes) {
        this.excludePrefixes = excludePrefixes != null ? new ArrayList<>(excludePrefixes) : new ArrayList<>();
    }

    public List<String> getExcludeContains() {
        return excludeContains;
    }

    public void setExcludeContains(List<String> excludeContains) {
        this.excludeContains = excludeContains != null ? new ArrayList<>(excludeContains) : new ArrayList<>();
    }

    public List<String> getExcludeSuffixes() {
        return excludeSuffixes;
    }

    public void setExcludeSuffixes(List<String> excludeSuffixes) {
        this.excludeSuffixes = excludeSuffixes != null ? new ArrayList<>(excludeSuffixes) : new ArrayList<>();
    }

    public List<String> getExcludeRegexPatterns() {
        return excludeRegexPatterns;
    }

    public void setExcludeRegexPatterns(List<String> excludeRegexPatterns) {
        this.excludeRegexPatterns = excludeRegexPatterns != null ? new ArrayList<>(excludeRegexPatterns) : new ArrayList<>();
    }

    /**
     * 判断指定文件名是否应当被排除过滤
     *
     * @param fileName 待检测的文件名
     * @return true 表示需要过滤排除，false 表示通过
     */
    public boolean shouldExclude(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return true;
        }
        String trimmed = fileName.trim();
        String lower = trimmed.toLowerCase();

        // 1. 前缀匹配（如 ~$、.）
        if (excludePrefixes != null) {
            for (String prefix : excludePrefixes) {
                if (prefix != null && !prefix.isEmpty() && trimmed.startsWith(prefix)) {
                    return true;
                }
            }
        }

        // 2. 包含关键字匹配（不区分大小写，如 _temp_、_bak）
        if (excludeContains != null) {
            for (String keyword : excludeContains) {
                if (keyword != null && !keyword.isEmpty() && lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }

        // 3. 后缀匹配（不区分大小写，如 .tmp）
        if (excludeSuffixes != null) {
            for (String suffix : excludeSuffixes) {
                if (suffix != null && !suffix.isEmpty() && lower.endsWith(suffix.toLowerCase())) {
                    return true;
                }
            }
        }

        // 4. 正则表达式匹配（如精确到秒的时间戳备份）
        if (excludeRegexPatterns != null) {
            for (String regex : excludeRegexPatterns) {
                if (regex != null && !regex.isEmpty()) {
                    try {
                        if (trimmed.matches(regex) || lower.matches(regex)) {
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return false;
    }
}

package com.tinyclaw.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实现了支持多级模糊匹配的 Edit 工具。
 * <p>
 * 将容错做在底层工具里，吸收大模型的"缩进幻觉"误差。
 * 4 级降级：精确匹配 → 换行符归一化 → Trim 匹配 → 逐行去缩进匹配。
 */
public class EditFileTool implements Tool {

    private final String workDir;

    public EditFileTool(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "edit_file",
                "对现有文件进行局部的字符串替换。这比重写整个文件更安全、更快速。请提供足够的 old_text 上下文以确保匹配的唯一性。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "要修改的文件路径"
                                ),
                                "old_text", Map.of(
                                        "type", "string",
                                        "description", "文件中原有的文本。必须包含足够的上下文（建议上下各多包含几行），以确保在文件中的唯一性。"
                                ),
                                "new_text", Map.of(
                                        "type", "string",
                                        "description", "要替换成的新文本"
                                )
                        ),
                        "required", List.of("path", "old_text", "new_text")
                )
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String execute(JsonNode args) throws IOException {
        String relativePath = args.path("path").asText();
        String oldText = args.path("old_text").asText();
        String newText = args.path("new_text").asText();

        if (relativePath == null || relativePath.isEmpty()) {
            throw new IllegalArgumentException("参数 'path' 不能为空");
        }

        // 1. 路径拼接 + 穿越防护
        Path resolved = Paths.get(workDir).resolve(relativePath).normalize();
        if (!resolved.startsWith(Paths.get(workDir).normalize())) {
            throw new SecurityException("路径穿越检测：不允许修改工作区外的路径 " + relativePath);
        }

        // 2. 读取原文件内容
        String originalContent = Files.readString(resolved);

        // 3. 调用多级模糊替换算法
        String newContent = fuzzyReplace(originalContent, oldText, newText);

        // 4. 将新内容安全地写回磁盘
        Files.writeString(resolved, newContent);

        return "✅ 成功修改文件: " + relativePath;
    }

    /**
     * 四级容错降级替换算法。
     * <p>
     * L1: 精确匹配 → L2: 换行符归一化 → L3: Trim 匹配 → L4: 逐行去缩进匹配。
     *
     * @throws IllegalArgumentException old_text 匹配到多处或在所有级别均未找到
     */
    private String fuzzyReplace(String originalContent, String oldText, String newText) {

        // L1: 精确匹配
        int count = countOccurrences(originalContent, oldText);
        if (count == 1) {
            return replaceFirst(originalContent, oldText, newText);
        }
        if (count > 1) {
            throw new IllegalArgumentException("old_text 匹配到了 " + count + " 处，请提供更多的上下文代码以确保唯一性");
        }

        // L2: 换行符归一化（统一将 \r\n 转换为 \n）
        String normalizedContent = originalContent.replace("\r\n", "\n");
        String normalizedOld = oldText.replace("\r\n", "\n");

        count = countOccurrences(normalizedContent, normalizedOld);
        if (count == 1) {
            return replaceFirst(normalizedContent, normalizedOld, newText);
        }

        // L3: Trim Space 匹配（忽略首尾的空行和空格）
        String trimmedOld = normalizedOld.trim();
        if (!trimmedOld.isEmpty()) {
            count = countOccurrences(normalizedContent, trimmedOld);
            if (count == 1) {
                return replaceFirst(normalizedContent, trimmedOld, newText);
            }
        }

        // L4: 逐行去缩进匹配（消除大模型遗漏缩进的幻觉）
        return lineByLineReplace(normalizedContent, normalizedOld, newText);
    }

    /**
     * 将文本按行切割，去除每行首尾空白后进行滑动窗口匹配。
     * <p>
     * 唯一性校验：匹配到 0 处 → 报错让模型读文件确认；>1 处 → 报错要求提供更多上下文。
     */
    private String lineByLineReplace(String content, String oldText, String newText) {
        List<String> contentLines = List.of(content.split("\n", -1));
        String[] oldLinesRaw = oldText.trim().split("\n", -1);

        if (oldLinesRaw.length == 0 || contentLines.size() < oldLinesRaw.length) {
            throw new IllegalArgumentException("找不到该代码片段");
        }

        // 清理 oldLines 的每行首尾空白
        List<String> oldLines = new ArrayList<>();
        for (String line : oldLinesRaw) {
            oldLines.add(line.strip());
        }

        int matchCount = 0;
        int matchStartIndex = -1;
        int matchEndIndex = -1;

        // 滑动窗口在原始文件中寻找匹配块
        for (int i = 0; i <= contentLines.size() - oldLines.size(); i++) {
            boolean isMatch = true;
            for (int j = 0; j < oldLines.size(); j++) {
                if (!contentLines.get(i + j).strip().equals(oldLines.get(j))) {
                    isMatch = false;
                    break;
                }
            }
            if (isMatch) {
                matchCount++;
                matchStartIndex = i;
                matchEndIndex = i + oldLines.size();
            }
        }

        if (matchCount == 0) {
            throw new IllegalArgumentException("在文件中未找到 old_text，请先调用 read_file 仔细确认文件内容和缩进");
        }
        if (matchCount > 1) {
            throw new IllegalArgumentException("模糊匹配到了 " + matchCount + " 处相似代码，请提供更多上下行代码以精确定位");
        }

        // 执行替换：将匹配到的原始行范围替换为 newText
        List<String> newContentLines = new ArrayList<>();
        newContentLines.addAll(contentLines.subList(0, matchStartIndex));
        newContentLines.add(newText);
        newContentLines.addAll(contentLines.subList(matchEndIndex, contentLines.size()));

        return String.join("\n", newContentLines);
    }

    /**
     * 计算子串在原字符串中的非重叠出现次数。
     */
    private static int countOccurrences(String source, String target) {
        if (target.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = source.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    /**
     * 替换原字符串中第一个匹配的子串。
     */
    private static String replaceFirst(String source, String target, String replacement) {
        int idx = source.indexOf(target);
        if (idx == -1) {
            return source;
        }
        return source.substring(0, idx) + replacement + source.substring(idx + target.length());
    }
}

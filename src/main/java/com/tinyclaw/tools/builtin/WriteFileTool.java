package com.tinyclaw.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 实现了创建或覆盖写入文件的工具。
 */
public class WriteFileTool implements Tool {

    /**
     * 引擎的工作区物理边界，工具只能在此目录及其子目录下操作
     */
    private final String workDir;

    /**
     * @param workDir 工作区根目录
     */
    public WriteFileTool(String workDir) {
        this.workDir = workDir;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "write_file";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "write_file",
                "创建或覆盖写入一个文件。如果目录不存在会自动创建。请提供相对于工作区的相对路径。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "要写入的文件路径，如 src/main.go"
                                ),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "要写入的完整文件内容"
                                )
                        ),
                        "required", List.of("path", "content")
                )
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String execute(JsonNode args) throws IOException {
        String relativePath = args.path("path").asText();
        String content = args.path("content").asText();

        if (relativePath == null || relativePath.isEmpty()) {
            throw new IllegalArgumentException("参数 'path' 不能为空");
        }

        // 1. 路径拼接 + 穿越防护
        Path resolved = Paths.get(workDir).resolve(relativePath).normalize();
        if (!resolved.startsWith(Paths.get(workDir).normalize())) {
            // 检查最终路径是否仍以工作目录开头，防止逃逸出工作区
            throw new SecurityException("路径穿越检测：不允许写入工作区外的路径 " + relativePath);
        }

        // 2. 自动创建缺失的父级目录
        Path parent = resolved.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // 3. 写入文件内容
        Files.writeString(resolved, content != null ? content : "");

        return "成功将内容写入到文件: " + relativePath;
    }
}

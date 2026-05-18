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
 * 实现了读取本地文件内容的工具。
 */
public class ReadFileTool implements Tool {

    /**
     * 单次读取的最大字符数，防止大文件撑爆 Context（OOM）
     */
    private static final int MAX_LENGTH = 8000;

    /**
     * 引擎的工作区物理边界，工具只能在此目录及其子目录下操作
     */
    private final String workDir;

    /**
     * @param workDir 工作区根目录
     */
    public ReadFileTool(String workDir) {
        this.workDir = workDir;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "read_file";
    }

    /**
     * {@inheritDoc}
     * <p>
     * 向大模型清晰地描述这个工具的用途和参数格式，遵循 JSON Schema 规范。
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "read_file",
                "读取指定路径的文件内容。请提供相对工作区的路径。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of(
                                        "type", "string",
                                        "description", "要读取的文件路径，如 cmd/claw/main.go"
                                )
                        ),
                        "required", List.of("path")
                )
        );
    }

    /**
     * {@inheritDoc}
     * <p>
     * 1. JSON 参数解析 → 2. 路径拼接（防范路径穿越） → 3. 物理 IO → 4. 长度截断保护。
     */
    @Override
    public String execute(JsonNode args) throws IOException {
        // 1. 延迟解析：将大模型传过来的 JSON 参数提取为 path
        String relativePath = args.path("path").asText();
        if (relativePath == null || relativePath.isEmpty()) {
            throw new IllegalArgumentException("参数 'path' 不能为空");
        }

        // 2. 拼接绝对路径 + 路径穿越防护
        Path resolved = Paths.get(workDir).resolve(relativePath).normalize();
        if (!resolved.startsWith(Paths.get(workDir).normalize())) {
            throw new SecurityException("路径穿越检测：不允许访问工作区外的路径 " + relativePath);
        }

        // 3. 执行物理 IO 操作
        byte[] bytes = Files.readAllBytes(resolved);
        String content = new String(bytes);

        // 4. 核心防线：长度截断保护
        if (content.length() > MAX_LENGTH) {
            content = content.substring(0, MAX_LENGTH) + "\n...[截断，文件过大，仅显示前 " + MAX_LENGTH + " 字符]";
        }

        return content;
    }
}

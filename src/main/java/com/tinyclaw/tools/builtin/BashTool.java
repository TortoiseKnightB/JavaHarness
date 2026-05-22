package com.tinyclaw.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.tools.Tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 在当前工作区执行任意的 bash 命令。
 * <p>
 * 拥抱 YOLO 哲学：对模型意图给予最高信任，对底层资源施加最冷酷的物理拦截。
 * 4 大驾驭底线：工作区约束 + 30s 超时 + 错误自纠错回传 + 8000 字符截断。
 */
public class BashTool implements Tool {

    /**
     * 单次命令输出的最大字符数，防止大输出撑爆 Context（OOM）
     */
    private static final int MAX_LENGTH = 8000;

    /**
     * 命令执行超时时间（秒）
     */
    private static final int TIMEOUT_SECONDS = 30;

    /**
     * 引擎的工作区物理边界
     */
    private final String workDir;

    /**
     * @param workDir 工作区根目录
     */
    public BashTool(String workDir) {
        this.workDir = workDir;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "bash";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "bash",
                "在当前工作区执行任意的 bash 命令。支持链式命令(如 &&)。返回标准输出(stdout)和标准错误(stderr)。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "要执行的 bash 命令，例如: ls -la 或 go test ./..."
                                )
                        ),
                        "required", List.of("command")
                )
        );
    }

    /**
     * {@inheritDoc}
     * <p>
     * 4 大驾驭底线：
     * 1. 工作区约束 — ProcessBuilder.directory(workDir)
     * 2. 超时控制 — Process.waitFor(30, SECONDS)
     * 3. 错误原样回传 — 不抛异常，而是将 stderr/异常信息作为正常输出返回，让大模型自纠错
     * 4. 长度截断 — 输出超过 8000 字符时截断
     */
    @Override
    public String execute(JsonNode args) throws Exception {
        String command = args.path("command").asText();
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("参数 'command' 不能为空");
        }

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);

        // 【驾驭底线 1】：绑定执行的工作区目录
        pb.directory(new java.io.File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append("\n");
                }
                output.append(line);
            }
        }

        // 【驾驭底线 2】：超时控制
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return output + "\n[警告: 命令执行超时(" + TIMEOUT_SECONDS + "s)，已被系统强制终止。如果是启动常驻服务，请尝试将其转入后台。]";
        }

        // 【驾驭底线 3】：错误原样回传（自愈机制）
        int exitCode = process.exitValue();
        String outputStr = output.toString();
        if (exitCode != 0) {
            return "命令执行失败 (exit code: " + exitCode + ")\n输出:\n" + outputStr;
        }

        // 无输出时给明确的反馈
        if (outputStr.isEmpty()) {
            return "命令执行成功，无终端输出。";
        }

        // 【驾驭底线 4】：长度截断保护
        if (outputStr.length() > MAX_LENGTH) {
            outputStr = outputStr.substring(0, MAX_LENGTH)
                    + "\n\n...[终端输出过长，已截断至前 " + MAX_LENGTH + " 字节]...";
        }

        return outputStr;

        // todo：超时和内存溢出控制存在问题，如果bash开启外部服务会阻塞本线程
    }
}

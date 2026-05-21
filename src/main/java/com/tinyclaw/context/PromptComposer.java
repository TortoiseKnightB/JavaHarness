package com.tinyclaw.context;

import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 负责根据工作区环境动态生成 System Prompt。
 * <p>
 * 组装策略遵循 OpenClaw 的分层加载哲学，像搭积木一样将极简内核、AGENTS.md 和 Skills 动态拼接。
 */
public class PromptComposer {

    private static final Logger log = LoggerFactory.getLogger(PromptComposer.class);

    /**
     * 极简内核：仅确立基本身份与最底线的红线纪律
     */
    private static final String CORE_KERNEL = """
            # 核心身份
            你名叫 java-tiny-claw，一个由驾驭工程驱动的骨灰级研发助手。
            你具备极简主义哲学，拒绝废话。你能通过系统提供的内置工具，创建、读取、修改和执行工作区中的代码。

            # 核心纪律 (CRITICAL)
            1. 如需检查文件是否存在，请使用 bash 的 ls 或 test -f，而不是对目录使用 read_file。
            2. 创建新文件时，务必使用 write_file，并同时提供 path 和 content 参数。
            3. 编辑文件前务必先读取现有文件，以理解上下文。
            4. 无论何时你需要写代码或创建文件，都要直接使用 write_file 工具。
            5. 遇到工具执行报错时，仔细阅读 stderr，尝试自己修正命令并重试。
            6. 始终用中文回复，以便传达你的进展和想法。
            """;

    /**
     * 工作区物理边界目录
     */
    private final String workDir;

    /**
     * 技能加载器
     */
    private final SkillLoader skillLoader;

    /**
     * @param workDir 工作区根目录路径
     */
    public PromptComposer(String workDir) {
        this.workDir = workDir;
        this.skillLoader = new SkillLoader(workDir);
    }

    /**
     * 组装并返回一条完整的 Role.SYSTEM 消息。
     * <p>
     * 三步组装：1) 极简内核 → 2) AGENTS.md（可选）→ 3) Skills（可选）。
     *
     * @return 包含最终 System Prompt 的 Message 对象
     */
    public Message build() {
        StringBuilder promptBuilder = new StringBuilder();

        // 1. 注入极简内核
        promptBuilder.append(CORE_KERNEL);

        // 2. 外部化状态：加载项目专属规范 (AGENTS.md)
        Path agentsMDPath = Paths.get(workDir, "AGENTS.md");
        try {
            String content = Files.readString(agentsMDPath);
            promptBuilder.append("\n# 项目专属指南 (来自 AGENTS.md)\n");
            promptBuilder.append("以下是当前工作区特有的架构规范与注意事项，你的行为必须绝对符合以下要求：\n");
            promptBuilder.append("```markdown\n");
            promptBuilder.append(content);
            promptBuilder.append("\n```\n");
        } catch (IOException e) {
            log.debug("[Composer] AGENTS.md 未找到，跳过: {}", e.getMessage());
        }

        // 3. 动态加载技能外挂 (Skills)
        String skillsContent = skillLoader.loadAll();
        if (!skillsContent.isEmpty()) {
            promptBuilder.append(skillsContent);
        }

        return new Message(Role.SYSTEM, promptBuilder.toString());
    }
}

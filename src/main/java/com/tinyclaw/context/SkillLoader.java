package com.tinyclaw.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * 负责从本地文件系统中加载并解析符合 Agent Skills 规范的技能模板。
 * <p>
 * 极简实现：不引入第三方 YAML 库，手写基于字符串切割的轻量级 YAML Frontmatter 解析器。
 */
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    /**
     * 工作区物理边界目录
     */
    private final String workDir;

    /**
     * @param workDir 工作区根目录路径
     */
    public SkillLoader(String workDir) {
        this.workDir = workDir;
    }

    /**
     * 扫描 .claw/skills 目录，解析所有 SKILL.md，并格式化为字符串准备注入 Context。
     * <p>
     * 如果目录不存在，静默返回空字符串（说明当前工作区没有配置技能）。
     *
     * @return 格式化后的技能描述字符串，无技能时返回空串
     */
    public String loadAll() {
        Path skillBaseDir = Paths.get(workDir, ".claw", "skills");

        if (!Files.isDirectory(skillBaseDir)) {
            return "";
        }

        StringBuilder skillsBuilder = new StringBuilder();
        skillsBuilder.append("\n### 可用专业技能 (Agent Skills)\n");
        skillsBuilder.append("以下是你拥有的标准化外挂技能，请在符合 description 的描述场景下严格遵循其正文指令：\n\n");

        try (Stream<Path> stream = Files.walk(skillBaseDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            Skill skill = parseSkillMD(content);

                            skillsBuilder.append("#### 技能名称: ").append(skill.name()).append("\n");
                            skillsBuilder.append("**触发条件**: ").append(skill.description()).append("\n\n");
                            skillsBuilder.append("**执行指南**:\n");
                            skillsBuilder.append(skill.body());
                            skillsBuilder.append("\n\n---\n");
                        } catch (IOException e) {
                            log.warn("[SkillLoader] 读取技能文件失败: {} - {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[SkillLoader] 遍历技能目录失败: {}", e.getMessage());
        }

        if (skillsBuilder.length() < 100) {
            return "";
        }

        return skillsBuilder.toString();
    }

    /**
     * 极简解析带有 YAML Frontmatter 的 Markdown 内容。
     * <p>
     * 以 {@code ---} 为分隔符切分为三部分：空、Frontmatter、Body。
     * Frontmatter 中逐行提取 {@code name:} 和 {@code description:} 字段。
     *
     * @param content SKILL.md 文件的原始文本内容
     * @return 解析后的 Skill 对象，解析失败时返回默认值
     */
    private static Skill parseSkillMD(String content) {
        String name = "Unknown Skill";
        String description = "No description provided.";
        String body = content;

        if (content.startsWith("---\n") || content.startsWith("---\r\n")) {
            String[] parts = content.split("---", 3);
            if (parts.length == 3) {
                String frontmatter = parts[1];
                body = parts[2].strip();

                for (String line : frontmatter.split("\n")) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("name:")) {
                        name = trimmed.substring("name:".length()).strip();
                    } else if (trimmed.startsWith("description:")) {
                        description = trimmed.substring("description:".length()).strip();
                    }
                }
            }
        }

        return new Skill(name, description, body);
    }
}

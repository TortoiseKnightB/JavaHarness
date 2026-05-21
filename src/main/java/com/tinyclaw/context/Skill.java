package com.tinyclaw.context;

/**
 * 从 SKILL.md 中解析出的标准化技能结构。
 *
 * @param name        技能名称（来自 YAML Frontmatter 的 name 字段）
 * @param description 触发条件描述（来自 YAML Frontmatter 的 description 字段）
 * @param body        Markdown 格式的执行指令正文
 */
public record Skill(String name, String description, String body) {
}

package com.mindbridge.agent.service.skill;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 技能模型（不可变）。
 *
 * <p>一个 Skill 是一段可注入回复提示词的 Markdown 指导内容，
 * 通过 YAML frontmatter 声明适用场景（intent、riskLevel）和匹配关键词。</p>
 *
 * @param name        技能名称（唯一标识）
 * @param description 技能描述
 * @param intents     适用的意图类型集合
 * @param riskLevels  适用的风险等级集合
 * @param keywords    匹配关键词集合（用于 selectBy 输入匹配）
 * @param version     技能版本
 * @param content     Markdown 正文（注入到 prompt 的实际指导内容）
 */
public record Skill(
        String name,
        String description,
        Set<IntentType> intents,
        Set<RiskLevel> riskLevels,
        Set<String> keywords,
        String version,
        String content
) {
    public Skill {
        Objects.requireNonNull(name, "skill name must not be null");
        Objects.requireNonNull(description, "skill description must not be null");
        Objects.requireNonNull(content, "skill content must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("skill content must not be blank: " + name);
        }
        intents = intents == null ? Set.of() : Set.copyOf(intents);
        riskLevels = riskLevels == null ? Set.of() : Set.copyOf(riskLevels);
        keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
        version = version == null ? "1.0" : version;
    }
}
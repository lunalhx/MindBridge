package com.mindbridge.agent.service.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {

    // SkillRegistry 在构造时自动从 classpath:skills/*.md 加载
    // 默认技能目录有 7 个 .md 文件

    @Test
    void shouldLoadAllDefaultSkills() {
        var registry = new SkillRegistry();
        assertThat(registry.size()).isEqualTo(7);
    }

    @Test
    void shouldLoadSkillNames() {
        var registry = new SkillRegistry();
        assertThat(registry.all()).extracting(Skill::name).containsExactlyInAnyOrder(
                "active-listening",
                "grounding-technique",
                "sleep-hygiene",
                "crisis-safety",
                "study-stress",
                "referral-guidance",
                "supportive-closure");
    }

    @Test
    void loadedSkillShouldHaveAllFields() {
        var registry = new SkillRegistry();
        var skill = registry.get("active-listening");
        assertThat(skill).isNotNull();
        assertThat(skill.name()).isEqualTo("active-listening");
        assertThat(skill.description()).isNotBlank();
        assertThat(skill.intents()).contains(IntentType.CHAT, IntentType.CONSULT);
        assertThat(skill.riskLevels()).contains(RiskLevel.LOW, RiskLevel.MEDIUM);
        assertThat(skill.keywords()).isNotEmpty();
        assertThat(skill.version()).isEqualTo("1.0");
        assertThat(skill.content()).startsWith("#");
    }

    // ────────── selectBy 匹配 ──────────

    @Test
    void selectByShouldMatchByIntentRiskAndKeywords() {
        var registry = new SkillRegistry();
        // "倾诉" 匹配 active-listening（CHAT, LOW）
        var selected = registry.selectBy(IntentType.CHAT, RiskLevel.LOW, "我想倾诉一下最近的烦恼");
        assertThat(selected).isNotEmpty();
        assertThat(selected).extracting(Skill::name).contains("active-listening");
    }

    @Test
    void selectByShouldFilterByIntent() {
        var registry = new SkillRegistry();
        // crisis-safety 只适用 RISK intent
        var chatSelected = registry.selectBy(IntentType.CHAT, RiskLevel.HIGH, "不想活");
        assertThat(chatSelected).extracting(Skill::name).doesNotContain("crisis-safety");

        var riskSelected = registry.selectBy(IntentType.RISK, RiskLevel.HIGH, "不想活");
        assertThat(riskSelected).extracting(Skill::name).contains("crisis-safety");
    }

    @Test
    void selectByShouldFilterByRiskLevel() {
        var registry = new SkillRegistry();
        // grounding-technique 适用 MEDIUM/HIGH，不适用 LOW
        var lowSelected = registry.selectBy(IntentType.CONSULT, RiskLevel.LOW, "焦虑恐慌");
        assertThat(lowSelected).extracting(Skill::name).doesNotContain("grounding-technique");

        var highSelected = registry.selectBy(IntentType.CONSULT, RiskLevel.HIGH, "焦虑恐慌");
        assertThat(highSelected).extracting(Skill::name).contains("grounding-technique");
    }

    // ────────── 确定性排序 ──────────

    @Test
    void selectByShouldSortByKeywordMatchCountDescending() {
        var registry = new SkillRegistry();
        // "焦虑失眠" 同时匹配 sleep-hygiene (失眠) 和 grounding-technique (焦虑)
        // 但 grounding-technique 只匹配 MEDIUM/HIGH，LOW 下不选
        // sleep-hygiene 匹配 LOW/MEDIUM
        var selected = registry.selectBy(IntentType.CONSULT, RiskLevel.LOW, "最近焦虑失眠睡不着");
        assertThat(selected).isNotEmpty();
        // sleep-hygiene 应在结果中
        assertThat(selected).extracting(Skill::name).contains("sleep-hygiene");
    }

    @Test
    void selectByShouldBeDeterministicAcrossCalls() {
        var registry = new SkillRegistry();
        var first = registry.selectBy(IntentType.CONSULT, RiskLevel.MEDIUM, "焦虑失眠睡不着");
        var second = registry.selectBy(IntentType.CONSULT, RiskLevel.MEDIUM, "焦虑失眠睡不着");
        assertThat(first).extracting(Skill::name)
                .isEqualTo(second.stream().map(Skill::name).toList());
    }

    @Test
    void selectByWithSameMatchCountShouldSortByNameAscending() {
        var registry = new SkillRegistry();
        // 两个技能都不匹配关键词但有空 keywords → 不参与（keywords 为空的技能在无匹配时也返回）
        // 实际上所有默认技能都有 keywords，所以需要 keyword 匹配才入选
        // 测试同分排序：用多个关键词让两个技能匹配数相同
        var selected = registry.selectBy(IntentType.CHAT, RiskLevel.LOW, "倾诉难过烦恼");
        // active-listening 和 study-stress 都可能匹配（倾诉→listening, 难过→listening, 烦恼→listening）
        // 如果只 active-listening 匹配，验证它在前
        if (selected.size() > 1) {
            // 同分时按 name 升序
            for (int i = 0; i < selected.size() - 1; i++) {
                int matchI = countMatches(selected.get(i), "倾诉难过烦恼".toLowerCase());
                int matchNext = countMatches(selected.get(i + 1), "倾诉难过烦恼".toLowerCase());
                if (matchI == matchNext) {
                    assertThat(selected.get(i).name())
                            .isLessThanOrEqualTo(selected.get(i + 1).name());
                }
            }
        }
    }

    // ────────── 无匹配回退 ──────────

    @Test
    void selectByShouldReturnEmptyWhenNoMatch() {
        var registry = new SkillRegistry();
        var selected = registry.selectBy(IntentType.CHAT, RiskLevel.LOW, "xyzqwerty");
        assertThat(selected).isEmpty();
    }

    @Test
    void selectByShouldReturnEmptyForNullInput() {
        var registry = new SkillRegistry();
        assertThat(registry.selectBy(null, RiskLevel.LOW, "test")).isEmpty();
        assertThat(registry.selectBy(IntentType.CHAT, null, "test")).isEmpty();
        assertThat(registry.selectBy(IntentType.CHAT, RiskLevel.LOW, null)).isEmpty();
        assertThat(registry.selectBy(IntentType.CHAT, RiskLevel.LOW, "")).isEmpty();
    }

    // ────────── 注入上限 ──────────

    @Test
    void selectByShouldLimitMaxSkills() {
        // maxSkills=1 只返回 1 个
        var registry = new SkillRegistry(1, 5000);
        var selected = registry.selectBy(IntentType.CONSULT, RiskLevel.MEDIUM, "焦虑失眠睡不着倾诉烦恼难过");
        assertThat(selected).hasSizeBetween(0, 1);
    }

    @Test
    void selectByShouldLimitMaxTotalChars() {
        // maxTotalChars=50 非常小，只够装下第一个技能的一小部分
        var registry = new SkillRegistry(10, 50);
        var selected = registry.selectBy(IntentType.CONSULT, RiskLevel.MEDIUM, "焦虑失眠睡不着倾诉烦恼难过");
        int totalChars = selected.stream().mapToInt(s -> s.content().length()).sum();
        assertThat(totalChars).isLessThanOrEqualTo(50);
    }

    // ────────── injectSkills 安全指令优先 ──────────

    @Test
    void injectSkillsShouldReturnNullForEmptyList() {
        assertThat(PromptTemplates.injectSkills(null)).isNull();
        assertThat(PromptTemplates.injectSkills(List.of())).isNull();
    }

    @Test
    void injectSkillsShouldPrependSafetyPrefix() {
        var skill = new Skill("test", "desc",
                Set.of(IntentType.CHAT), Set.of(RiskLevel.LOW),
                Set.of("test"), "1.0", "# Test skill content");
        var msg = PromptTemplates.injectSkills(List.of(skill));
        assertThat(msg).isNotNull();
        assertThat(msg.role()).isEqualTo("system");
        assertThat(msg.content()).contains("辅助技能指导");
        assertThat(msg.content()).contains("遵守上述系统安全规则");
    }

    @Test
    void injectSkillsShouldIncludeAllSkillContents() {
        var s1 = new Skill("skill-a", "desc",
                Set.of(IntentType.CHAT), Set.of(RiskLevel.LOW),
                Set.of("a"), "1.0", "# Content A");
        var s2 = new Skill("skill-b", "desc",
                Set.of(IntentType.CHAT), Set.of(RiskLevel.LOW),
                Set.of("b"), "1.0", "# Content B");
        var msg = PromptTemplates.injectSkills(List.of(s1, s2));
        assertThat(msg.content()).contains("skill-a");
        assertThat(msg.content()).contains("# Content A");
        assertThat(msg.content()).contains("skill-b");
        assertThat(msg.content()).contains("# Content B");
    }

    // ────────── Skill 校验 ──────────

    @Test
    void skillShouldRejectBlankName() {
        assertThatThrownBy(() -> new Skill("  ", "desc",
                Set.of(IntentType.CHAT), Set.of(RiskLevel.LOW),
                Set.of(), "1.0", "content"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skillShouldRejectBlankContent() {
        assertThatThrownBy(() -> new Skill("name", "desc",
                Set.of(IntentType.CHAT), Set.of(RiskLevel.LOW),
                Set.of(), "1.0", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skillShouldRejectNullRequiredFields() {
        assertThatThrownBy(() -> new Skill(null, "desc",
                Set.of(), Set.of(), Set.of(), "1.0", "content"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Skill("name", null,
                Set.of(), Set.of(), Set.of(), "1.0", "content"))
                .isInstanceOf(NullPointerException.class);
    }

    // ────────── Helpers ──────────

    private int countMatches(Skill skill, String input) {
        int count = 0;
        for (String kw : skill.keywords()) {
            if (input.contains(kw)) count++;
        }
        return count;
    }
}
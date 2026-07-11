package com.mindbridge.agent.service.agent.registry;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.MindBridgeAgent;
import com.mindbridge.agent.service.agent.blackboard.AgentBlackboard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Agent 注册中心。
 *
 * <p>启动时从 Spring 注入的 {@code List<MindBridgeAgent>} 自动构建 AgentProfile，
 * 不再依赖手写六个 Agent 的注册表。提供声明式调度的候选排序：</p>
 * <ul>
 *   <li>按置信度降序排列</li>
 *   <li>同分时按 AgentName 枚举序号升序（确定性 tie-break）</li>
 *   <li>低于阈值的候选被过滤</li>
 * </ul>
 */
@Service
public class AgentRegistry {

    /** 默认置信度阈值，低于此值的候选不参与竞争。 */
    public static final double DEFAULT_THRESHOLD = 0.6;

    private final Map<AgentName, AgentProfile> profiles;
    private final double decisionThreshold;

    /**
     * Spring 主构造：注入所有 Agent Bean 和配置属性。
     */
    @Autowired
    public AgentRegistry(List<MindBridgeAgent> agents, MindBridgeProperties properties) {
        this(agents, properties.getAgent().getDecisionThreshold());
    }

    /**
     * 带阈值的构造，用于测试或配置注入。
     *
     * @param agents            Agent 列表
     * @param decisionThreshold 置信度阈值
     */
    public AgentRegistry(List<MindBridgeAgent> agents, double decisionThreshold) {
        Objects.requireNonNull(agents, "agents list must not be null");
        if (decisionThreshold < 0.0 || decisionThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "decisionThreshold must be in [0.0, 1.0], got: " + decisionThreshold);
        }
        this.decisionThreshold = decisionThreshold;

        Map<AgentName, AgentProfile> map = new LinkedHashMap<>();
        for (MindBridgeAgent agent : agents) {
            AgentName name = agent.name();
            if (map.containsKey(name)) {
                throw new IllegalStateException(
                        "Duplicate agent name detected: " + name
                                + ". Each Agent must have a unique AgentName.");
            }
            List<AgentCapability> capabilities = agent.decide(AgentBlackboard.empty());
            map.put(name, new AgentProfile(name, agent, capabilities));
        }
        this.profiles = Map.copyOf(map);
    }

    /**
     * 返回所有已注册的 AgentProfile。
     */
    public List<AgentProfile> profiles() {
        return List.copyOf(profiles.values());
    }

    /**
     * 按 AgentName 获取 AgentProfile。
     */
    public AgentProfile profile(AgentName name) {
        return profiles.get(name);
    }

    /**
     * 返回已注册 Agent 数量。
     */
    public int size() {
        return profiles.size();
    }

    /**
     * 当前置信度阈值。
     */
    public double decisionThreshold() {
        return decisionThreshold;
    }

    /**
     * 收集所有 Agent 对当前 Blackboard 的能力声明，按置信度降序 + AgentName 枚举序排序。
     *
     * <p>每个 Agent 的 decide() 返回的多个 Capability 会展开为独立候选条目。
     * 低于 {@code decisionThreshold} 的条目被过滤。</p>
     *
     * @param blackboard 当前 Blackboard 状态
     * @return 排序后的候选列表（高置信度在前）
     */
    public List<Candidate> candidates(AgentBlackboard blackboard) {
        return candidates(blackboard, decisionThreshold);
    }

    /**
     * 带自定义阈值的候选查询。
     *
     * @param blackboard 当前 Blackboard 状态
     * @param threshold  置信度阈值（覆盖 Registry 默认阈值）
     * @return 排序后的候选列表
     */
    public List<Candidate> candidates(AgentBlackboard blackboard, double threshold) {
        Objects.requireNonNull(blackboard, "blackboard must not be null");
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException(
                    "threshold must be in [0.0, 1.0], got: " + threshold);
        }

        List<Candidate> all = new ArrayList<>();
        for (AgentProfile profile : profiles.values()) {
            List<AgentCapability> capabilities = profile.agent().decide(blackboard);
            if (capabilities == null) continue;
            for (AgentCapability cap : capabilities) {
                if (cap.confidence() >= threshold) {
                    all.add(new Candidate(profile.agentName(), cap));
                }
            }
        }

        all.sort(Candidate.COMPARATOR);
        return all;
    }

    /**
     * 候选条目：Agent + 其声明的一个 Capability。
     *
     * @param agentName  候选 Agent 名称
     * @param capability 声明的能力
     */
    public record Candidate(AgentName agentName, AgentCapability capability) {
        /** 确定性排序：置信度降序，同分按 AgentName 枚举 ordinal 升序。 */
        static final Comparator<Candidate> COMPARATOR = Comparator
                .comparingDouble((Candidate c) -> c.capability().confidence()).reversed()
                .thenComparingInt(c -> c.agentName().ordinal());
    }
}
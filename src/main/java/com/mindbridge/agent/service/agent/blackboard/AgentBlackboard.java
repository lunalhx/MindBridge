package com.mindbridge.agent.service.agent.blackboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 不可变 Blackboard —— Agent 协作的共享状态总线。
 *
 * <p>所有状态变更以追加方式记录，每次更新返回新实例，旧实例不受影响。
 * 包含三类数据：artifacts（Agent 产出物）、events（审计事件）、flags（布尔状态标记）。</p>
 *
 * <p><b>不可变性保证：</b>
 * <ul>
 *   <li>addArtifact、addEvent、setFlag 均返回新 AgentBlackboard 实例</li>
 *   <li>artifacts()、events()、flags() 返回不可修改的视图</li>
 *   <li>内部集合使用防御性拷贝，外部修改不影响内部状态</li>
 * </ul></p>
 */
public final class AgentBlackboard {

    private final Map<String, AgentArtifact> artifacts;
    private final List<AgentEvent> events;
    private final Set<AgentFlag> flags;

    private AgentBlackboard(Map<String, AgentArtifact> artifacts, List<AgentEvent> events, Set<AgentFlag> flags) {
        this.artifacts = artifacts;
        this.events = events;
        this.flags = flags;
    }

    /** 创建一个空的 Blackboard。 */
    public static AgentBlackboard empty() {
        return new AgentBlackboard(
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptySet());
    }

    /**
     * 添加一个 artifact，返回包含新 artifact 的 Blackboard 实例。
     * 如果同名 artifact 已存在，会被覆盖（保留最新值）。
     */
    public AgentBlackboard addArtifact(AgentArtifact artifact) {
        Map<String, AgentArtifact> newArtifacts = new LinkedHashMap<>(this.artifacts);
        newArtifacts.put(artifact.name(), artifact);
        return new AgentBlackboard(
                Collections.unmodifiableMap(newArtifacts),
                this.events,
                this.flags);
    }

    /**
     * 追加一个审计事件，返回包含新 event 的 Blackboard 实例。
     * 事件只追加，不可删除或修改。
     */
    public AgentBlackboard addEvent(AgentEvent event) {
        List<AgentEvent> newEvents = new ArrayList<>(this.events);
        newEvents.add(event);
        return new AgentBlackboard(
                this.artifacts,
                Collections.unmodifiableList(newEvents),
                this.flags);
    }

    /**
     * 设置一个状态标记，返回包含新 flag 的 Blackboard 实例。
     * 如果该 flag 已存在则返回当前实例（幂等）。
     */
    public AgentBlackboard setFlag(AgentFlag flag) {
        if (this.flags.contains(flag)) {
            return this;
        }
        Set<AgentFlag> newFlags = new LinkedHashSet<>(this.flags);
        newFlags.add(flag);
        return new AgentBlackboard(
                this.artifacts,
                this.events,
                Collections.unmodifiableSet(newFlags));
    }

    /** 查询是否设置了指定标记。 */
    public boolean hasFlag(AgentFlag flag) {
        return flags.contains(flag);
    }

    /** 按名称获取 artifact。 */
    public Optional<AgentArtifact> getArtifact(String name) {
        return Optional.ofNullable(artifacts.get(name));
    }

    /**
     * 返回所有 artifact 的不可变视图。
     * 调用方无法通过返回的 Map 修改内部状态。
     */
    public Map<String, AgentArtifact> artifacts() {
        return artifacts; // already unmodifiable from constructor
    }

    /**
     * 返回所有事件的不可变视图。
     * 调用方无法通过返回的 List 修改内部状态。
     */
    public List<AgentEvent> events() {
        return events; // already unmodifiable from constructor
    }

    /**
     * 返回所有标记的不可变视图。
     * 调用方无法通过返回的 Set 修改内部状态。
     */
    public Set<AgentFlag> flags() {
        return flags; // already unmodifiable from constructor
    }

    @Override
    public String toString() {
        return "AgentBlackboard{artifacts=" + artifacts.keySet()
                + ", events=" + events.size()
                + ", flags=" + flags + "}";
    }
}

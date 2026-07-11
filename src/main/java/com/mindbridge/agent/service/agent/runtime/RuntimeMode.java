package com.mindbridge.agent.service.agent.runtime;

/**
 * Agent 运行时模式。
 *
 * <p>通过配置 {@code mindbridge.agent.runtime-mode} 切换：</p>
 * <ul>
 *   <li>{@link #SEQUENTIAL} —— 当前默认的有限步顺序循环（Supervisor 架构）</li>
 *   <li>{@link #GRAPH} —— 图编排运行时（批次 3 预留，尚未实现）</li>
 *   <li>{@link #EVENT_DRIVEN} —— 声明式事件驱动运行时（批次 3 预留，尚未实现）</li>
 * </ul>
 */
public enum RuntimeMode {
    SEQUENTIAL,
    GRAPH,
    EVENT_DRIVEN
}
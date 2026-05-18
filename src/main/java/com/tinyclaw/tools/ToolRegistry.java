package com.tinyclaw.tools;

import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.model.ToolResult;

import java.util.List;

/**
 * 定义了工具的注册与分发执行接口。
 */
public interface ToolRegistry {

    /**
     * 挂载一个新的工具到系统中。
     *
     * @param tool 实现了 Tool 接口的具体工具
     */
    void register(Tool tool);

    /**
     * 返回当前系统挂载的所有可用工具的 Schema，供模型参考。
     *
     * @return 已挂载工具的 ToolDefinition 列表
     */
    List<ToolDefinition> getAvailableTools();

    /**
     * 路由并执行模型请求的工具，返回物理世界的结果（Observation）。
     *
     * @param call 大模型返回的工具调用请求
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall call);
}

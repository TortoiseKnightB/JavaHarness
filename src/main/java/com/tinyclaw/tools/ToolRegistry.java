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
     * 返回当前系统挂载的所有可用工具的 Schema，供模型参考。
     */
    List<ToolDefinition> getAvailableTools();

    /**
     * 实际执行模型请求的工具，并返回物理世界的结果（Observation）。
     */
    ToolResult execute(ToolCall call);
}

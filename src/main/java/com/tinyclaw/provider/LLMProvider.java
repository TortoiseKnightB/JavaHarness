package com.tinyclaw.provider;

import com.tinyclaw.model.Message;
import com.tinyclaw.model.ToolDefinition;

import java.util.List;

/**
 * 定义了与大模型通信的统一契约。
 * Main Loop 只需要依赖此接口，不关心底层是 Claude 还是 OpenAI。
 */
@FunctionalInterface
public interface LLMProvider {

    /**
     * 接收当前的上下文历史、可用工具列表，发起一次大模型推理。
     *
     * @param messages       当前上下文历史
     * @param availableTools 当前挂载的所有可用工具定义
     * @return 模型返回的消息（可能包含纯文本或工具调用）
     */
    Message generate(List<Message> messages, List<ToolDefinition> availableTools);
}

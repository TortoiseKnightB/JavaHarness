package com.tinyclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinyclaw.model.ToolDefinition;

/**
 * 所有具体工具必须实现的通用接口。
 * <p>
 * 一个工具必须能说出自己的名字和描述，能给出严谨的参数 JSON Schema，
 * 并且能接收一段原始的 JSON 参数去执行具体逻辑。
 */
public interface Tool {

    /**
     * 返回工具的全局唯一名称，大模型通过这个名字调用它。
     *
     * @return 工具名称
     */
    String name();

    /**
     * 返回用于提交给大模型的工具元信息和参数 JSON Schema。
     *
     * @return 工具定义
     */
    ToolDefinition definition();

    /**
     * 接收大模型吐出的 JSON 参数，执行具体业务逻辑。
     * <p>
     * 参数是 JsonNode，反序列化由各个具体工具内部自行处理。
     *
     * @param args 大模型返回的 JSON 参数
     * @return 工具执行结果字符串
     * @throws Exception 执行失败时抛出，会被 Registry 捕获并转化为 ToolResult.isError
     */
    String execute(JsonNode args) throws Exception;
}

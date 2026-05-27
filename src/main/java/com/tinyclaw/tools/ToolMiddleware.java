package com.tinyclaw.tools;

import com.tinyclaw.model.ToolCall;

/**
 * 工具中间件函数接口。
 * <p>
 * Middleware 在工具执行前被调用，可拦截并阻止工具执行。
 * 返回 null 表示放行，返回非空字符串表示拦截（该字符串为拦截原因，将作为错误信息返回给大模型）。
 * 这是防御纵深的核心机制——不修改任何底层工具代码，在 Registry 层统一加装安检哨卡。
 */
@FunctionalInterface
public interface ToolMiddleware {

    /**
     * 对工具调用进行拦截检查。
     *
     * @param call 大模型返回的工具调用请求
     * @return null 表示允许执行，非空字符串表示拦截原因
     */
    String check(ToolCall call);
}

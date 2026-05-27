package com.tinyclaw.tools;

import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ToolRegistry 的默认实现。
 * <p>
 * 使用 Map 以工具名称作为 Key 进行 O(1) 路由查找。
 * Registry 像前台总机——只负责接线（收 ToolCall）、查黄页（map 查找）、转接（调 Execute）。
 * Middleware 链在工具执行前构成防御纵深，任一中间件返回 false 即拦截执行。
 */
public class ToolRegistryImpl implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryImpl.class);

    /**
     * 工具注册表，name → Tool 实例
     */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 中间件链，按注册顺序依次执行
     */
    private final List<ToolMiddleware> middlewares = new CopyOnWriteArrayList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(Tool tool) {
        String name = tool.name();
        if (tools.containsKey(name)) {
            log.warn("[Registry] 工具 '{}' 已经被注册，将被覆盖。", name);
        }
        tools.put(name, tool);
        log.info("[Registry] 成功挂载工具: {}", name);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 将 Middleware 追加到拦截链末尾，按注册顺序依次调用。
     */
    @Override
    public void use(ToolMiddleware middleware) {
        middlewares.add(middleware);
        log.info("[Registry] 已挂载 Middleware: {}", middleware.getClass().getSimpleName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ToolDefinition> getAvailableTools() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool tool : tools.values()) {
            defs.add(tool.definition());
        }
        return defs;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 1. 路由查找：map.get(name) → 找不到说明模型产生幻觉，返回 isError=true
     * 2. Middleware 链拦截：任一中间件返回 false 则直接返回错误，底层工具不执行
     * 3. 执行：tool.execute(args) → 原始 JSON 直接丢给具体工具
     * 4. 封装：执行结果或异常统一包装为 ToolResult
     */
    @Override
    public ToolResult execute(ToolCall call) {
        // 1. 路由查找
        Tool tool = tools.get(call.name());
        if (tool == null) {
            String errMsg = "Error: 系统中不存在名为 '" + call.name() + "' 的工具。";
            return new ToolResult(call.id(), errMsg, true);
        }

        // 2. 【核心防御】在执行底层逻辑前，依次运行所有的 Middleware
        for (ToolMiddleware mw : middlewares) {
            String reason = mw.check(call);
            if (reason != null) {
                log.warn("[Registry] ⚠️ 工具 {} 被 Middleware 拦截: {}", call.name(), reason);
                return new ToolResult(call.id(), "执行被系统拦截。原因: " + reason, true);
            }
        }

        // 3. 执行工具逻辑
        String output;
        try {
            output = tool.execute(call.arguments());
        } catch (Exception e) {
            String errMsg = "Error executing " + call.name() + ": " + e.getMessage();
            return new ToolResult(call.id(), errMsg, true);
        }

        // 4. 封装成功结果
        return new ToolResult(call.id(), output, false);
    }
}

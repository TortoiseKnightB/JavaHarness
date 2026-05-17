package com.tinyclaw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// import com.tinyclaw.provider.LLMProvider;
// import com.tinyclaw.provider.ClaudeProvider;
// import com.tinyclaw.tools.ToolRegistry;
// import com.tinyclaw.tools.builtin.BashTool;
// import com.tinyclaw.context.ContextManager;
// import com.tinyclaw.engine.AgentEngine;

public class ClawApplication {

    private static final Logger log = LoggerFactory.getLogger(ClawApplication.class);

    public static void main(String[] args) {
        System.out.println("🚀 欢迎来到 java-tiny-claw 引擎启动序列");

        // TODO: 1. 初始化模型 Provider (大脑)
        // LLMProvider provider = new ClaudeProvider(...);

        // TODO: 2. 初始化 Tool Registry (手脚)
        // ToolRegistry registry = new ToolRegistry();
        // registry.register(new BashTool());

        // TODO: 3. 初始化上下文管理器 (内存管理器)
        // ContextManager ctxManager = new ContextManager(...);

        // TODO: 4. 组装并启动核心 Engine (操作系统心脏)
        // AgentEngine engine = new AgentEngine(provider, registry, ctxManager);
        // System.out.println("开始执行任务...");
        // try {
        //     engine.run("帮我检查一下当前目录下的文件并输出一个 README.md 大纲");
        // } catch (Exception e) {
        //     log.error("引擎运行崩溃", e);
        //     System.exit(1);
        // }

        log.info("架构蓝图搭建完毕，等待各核心模块注入！");
    }
}

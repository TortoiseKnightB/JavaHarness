# java-tiny-claw 工程蓝图

> 基于 Tony Bai《从 0 开始构建 Agent Harness》专栏，Go 版本 go-tiny-claw 的 Java 移植实现。

## 设计原则

- **零 Agent 框架依赖**：不引入 LangChain4j 等，保持对控制流的完全掌控
- **基础设施只取所需**：用 Maven 管依赖，不用 Spring Boot（避免 DI 容器成为新的"黑盒框架"）
- **Java 21**：利用 record、sealed class、Virtual Threads 等现代特性

## 四层架构映射

| go-tiny-claw | java-tiny-claw | 职责 |
|---|---|---|
| `cmd/claw/main.go` | `ClawApplication.java` | 启动入口，组装各模块 |
| `internal/engine/` | `engine/` | MainLoop + ReAct 循环 + Thinking |
| `internal/provider/` | `provider/` | LLM 接口抽象 + Claude/OpenAI 适配 |
| `internal/context/` | `context/` | Prompt 组装、Token 监控、Compactor |
| `internal/tools/` | `tools/` | ToolRegistry + Middleware 链 + 基础工具 |
| `internal/memory/` | `memory/` | 基于文件系统的记忆存取 |
| `internal/feishu/` | `feishu/` | 飞书机器人回调 |

## 核心数据流

```
用户任务 → ClawApplication
              ↓
         AgentEngine (MainLoop)
              ↓
         ContextManager.buildContext()
              ↓
         ┌─→ LLMProvider.generate(context)
         │        ↓
         │   返回: Answer 或 ToolCall
         │        ↓
         │   ToolCall → ToolMiddleware chain
         │        ↓
         │   ToolRegistry.execute(toolCall)
         │        ↓
         │   Observation 追加到 Context
         │        ↓
         └── 循环回 LLMProvider.generate()
```

## 项目目录结构

```
java-tiny-claw/
├── pom.xml
├── README.md
├── src/main/java/com/tinyclaw/
│   ├── ClawApplication.java              # 入口 main()
│   │
│   ├── model/                             # 领域模型（record）
│   │   ├── Message.java                   # 消息（role + content）
│   │   ├── ToolCall.java                  # 工具调用请求
│   │   ├── ToolResult.java                # 工具执行结果（Observation）
│   │   ├── AgentConfig.java               # 引擎配置
│   │   └── ThinkingResult.java            # 慢思考输出
│   │
│   ├── engine/                            # === 核心引擎层 ===
│   │   ├── AgentEngine.java               # 引擎门面，组装各模块
│   │   ├── MainLoop.java                  # ReAct 循环主逻辑
│   │   └── Thinking.java                  # 独立 Thinking 阶段
│   │
│   ├── provider/                          # === 大模型适配层 ===
│   │   ├── LLMProvider.java               # 接口（generate / streamGenerate）
│   │   ├── ProviderFactory.java           # 工厂
│   │   ├── ClaudeProvider.java            # Anthropic SDK 实现
│   │   └── OpenAICompatProvider.java      # OpenAI 兼容实现
│   │
│   ├── context/                           # === 上下文工程层 ===
│   │   ├── ContextManager.java            # 上下文生命周期管理
│   │   ├── PromptComposer.java            # 动态拼装系统提示（读取 AGENTS.md）
│   │   ├── Compactor.java                 # Token 阶梯压缩策略
│   │   └── EventInjector.java             # 运行时干预提醒注入
│   │
│   ├── tools/                             # === 工具与执行层 ===
│   │   ├── Tool.java                      # 工具接口（name + description + parameters）
│   │   ├── ToolRegistry.java              # 动态注册与分发
│   │   ├── ToolMiddleware.java            # 中间件接口（链式）
│   │   ├── ToolMiddlewareChain.java       # 中间件链执行器
│   │   ├── ApprovalGate.java              # 人类在环审批中间件
│   │   └── builtin/
│   │       ├── ReadTool.java              # 文件读取
│   │       ├── WriteTool.java             # 文件写入
│   │       ├── EditTool.java              # 精确编辑（多级模糊匹配）
│   │       └── BashTool.java              # Shell 命令执行（沙箱）
│   │
│   ├── memory/                            # === 文件系统记忆 ===
│   │   ├── MemoryStore.java               # 接口
│   │   └── FileMemoryStore.java           # 读写 TODO.md / PLAN.md / context.md
│   │
│   └── feishu/                            # === 飞书集成 ===
│       ├── FeishuBot.java                 # Webhook 回调处理
│       └── FeishuCardBuilder.java         # 审批卡片构建
│
├── src/test/java/com/tinyclaw/
│   ├── engine/MainLoopTest.java
│   ├── provider/ClaudeProviderTest.java
│   ├── context/CompactorTest.java
│   └── tools/ToolRegistryTest.java
```

## 关键设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| **构建工具** | Maven | 团队已有 Maven 权限配置，Java 生态标准 |
| **JSON** | Jackson | 事实标准，LLM API 全部 JSON |
| **HTTP 客户端** | `java.net.http.HttpClient`（JDK 内置） | 无需额外依赖，支持 HTTP/2 和异步 |
| **并发工具调用** | Virtual Threads（Java 21） | 专栏第 8 讲主题，轻量级并发 |
| **配置管理** | YAML + 简单 Properties 读取 | 不用 Spring Config，保持极简 |
| **日志** | SLF4J + Logback | 标准的门面 + 实现 |

## Maven 依赖（最小集）

- `com.fasterxml.jackson:jackson-databind` — JSON
- `org.slf4j:slf4j-api` — 日志门面
- `ch.qos.logback:logback-classic` — 日志实现
- `com.anthropic:anthropic-java-sdk` — Claude SDK（可选）
- `org.junit.jupiter:junit-jupiter` — 测试

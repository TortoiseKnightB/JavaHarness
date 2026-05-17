# java-tiny-claw 工程蓝图

> 基于 Tony Bai《从 0 开始构建 Agent Harness》专栏，Go 版本 go-tiny-claw 的 Java 移植实现。

## 设计原则

- **零 Agent 框架依赖**：不引入 LangChain4j 等，保持对控制流的完全掌控
- **基础设施只取所需**：用 Maven 管依赖，不用 Spring Boot（避免 DI 容器成为新的"黑盒框架"）
- **Java 21**：利用 record、sealed class、Virtual Threads 等现代特性
- **变量声明**：禁止使用 `var`，所有局部变量必须使用明确的类型声明
- **配置管理**：`application.yml`（kebab-case 命名），通过 `ConfigLoader` 加载，支持 `${ENV_VAR:default}` 占位符

## Javadoc 注释规范

- **Record 类**：在类级 javadoc 中使用 `@param` 标签描述各组件，不使用内联 `/** */`
- **多段落**：类描述的第二段开始使用 `<p>` 分隔
- **普通类字段**：使用 `/** */` 注释在字段上方
- **构造方法与公共方法**：使用 `@param` / `@return` 标签
- **`@Override` 方法**：同样必须包含 `@param` / `@return`，以 `{@inheritDoc}` 开头，`<p>` 后补充当前实现的特化细节

```java
/**
 * 第一段：类的简洁描述。
 * <p>
 * 第二段：补充说明、设计意图等。
 *
 * @param field1 字段1的描述
 * @param field2 字段2的描述
 */
public record Foo(String field1, int field2) {
}

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

## 核心数据流（Two-Stage ReAct）

```
用户任务 → ClawApplication
              ↓
         AgentEngine.run()
              ↓
    ┌─────────────────────────────────────────┐
    │ 每次 Turn:                              │
    │                                         │
    │  Phase 1 (Thinking): tools=空列表        │
    │       ↓                                 │
    │  🧠 强制输出纯文本推理轨迹                │
    │       ↓                                 │
    │  Phase 2 (Action): tools=正常传入        │
    │       ↓                                 │
    │  返回: Answer 或 ToolCall                │
    │       ↓                                 │
    │  ToolCall → ToolRegistry.execute()      │
    │       ↓                                 │
    │  Observation 追加到 Context              │
    │       ↓                                 │
    │  循环回 Phase 1 ────────────────────────┘
```

## 项目目录结构

```
java-tiny-claw/
├── pom.xml
├── README.md
├── src/main/resources/
│   └── application.yml                    # 应用配置（provider + engine）
│
├── src/main/java/com/tinyclaw/
│   ├── ClawApplication.java              # 入口 main()
│   │
│   ├── model/                             # 领域模型（record）
│   │   ├── Message.java                   # 消息（role + content + toolCalls）
│   │   ├── Role.java                      # 消息角色枚举（system/user/assistant）
│   │   ├── ToolCall.java                  # 工具调用请求（延迟解析参数）
│   │   ├── ToolResult.java                # 工具执行结果（Observation）
│   │   └── ToolDefinition.java            # 工具元信息（供模型理解工具有什么用）
│   │
│   ├── engine/                            # === 核心引擎层 ===
│   │   └── AgentEngine.java               # 引擎核心：Two-Stage ReAct (Phase1 Thinking + Phase2 Action)（已实现）
│   │
│   ├── config/                            # === 配置层 ===
│   │   ├── AppConfig.java                  # 应用全局配置 record
│   │   ├── ProviderConfig.java             # Provider 配置
│   │   ├── EngineConfig.java               # 引擎配置
│   │   └── ConfigLoader.java               # YAML 加载 + ${ENV:default} 占位符解析
│   │
│   ├── provider/                          # === 大模型适配层 ===
│   │   ├── LLMProvider.java               # 接口：generate(messages, tools)
│   │   ├── AbstractHttpProvider.java      # HTTP 基类：HttpClient + 认证 + 错误处理
│   │   ├── OpenAICompatProvider.java      # OpenAI 协议适配器（已接入智谱，正常）
│   │   ├── ClaudeProvider.java            # Anthropic 协议适配器（⚠ 智谱 /v4/messages 返回 404）
│   │   └── ProviderException.java         # Provider 层异常
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
| **HTTP 客户端** | `java.net.http.HttpClient`（JDK 内置） | 零 SDK 依赖，纯 HTTP JSON 调用 LLM API，支持 HTTP/2 和异步 |
| **并发工具调用** | Virtual Threads（Java 21） | 专栏第 8 讲主题，轻量级并发 |
| **配置管理** | `application.yml` + `ConfigLoader` | kebab-case 命名，支持 `${ENV_VAR:default}` 占位符 |
| **日志** | SLF4J + Logback | 标准的门面 + 实现 |

## 已知问题

- **ClaudeProvider**：代码已实现 Anthropic Messages API 格式翻译，但智谱 `/v4/messages` 端点返回 404，需确认正确的 Claude 兼容端点地址。`application.yml` 中 Claude 配置已注释备用。

## Maven 依赖（最小集）

- `com.fasterxml.jackson:jackson-databind` — JSON 序列化
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` — YAML 配置解析
- `org.slf4j:slf4j-api` — 日志门面
- `ch.qos.logback:logback-classic` — 日志实现
- `org.junit.jupiter:junit-jupiter` — 测试

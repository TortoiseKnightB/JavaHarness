# java-tiny-claw 工程蓝图

> 基于 Tony Bai《从 0 开始构建 Agent Harness》专栏，Go 版本 go-tiny-claw 的 Java 移植实现。

## 设计原则

- **零 Agent 框架依赖**：不引入 LangChain4j 等，保持对控制流的完全掌控
- **基础设施只取所需**：用 Maven 管依赖，不用 Spring Boot（避免 DI 容器成为新的"黑盒框架"）
- **Java 21**：利用 record、sealed class、Virtual Threads 等现代特性
- **变量声明**：禁止使用 `var`，所有局部变量必须使用明确的类型声明
- **时间日期类型**：使用 `java.time.LocalDateTime` 表示时间，使用 `java.time.LocalDate` 表示日期，禁止使用 `java.time.Instant`
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
```

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

## 运行时完整流程

### 启动阶段

```
ClawApplication.main()
  │
  ├─ 1. ConfigLoader.load("application.yml")
  │     └─ YAML 解析 → kebab-case → camelCase → ${ENV:default} 占位符解析
  │     └─ 输出: AppConfig { provider: ProviderConfig, engine: EngineConfig }
  │
  ├─ 2. createProvider(providerConfig)
  │     ├─ type=openai → new OpenAICompatProvider(model, apiKey, baseUrl)
  │     └─ type=claude → new ClaudeProvider(model, apiKey, baseUrl)
  │
  ├─ 3. ToolRegistryImpl.register(Tool) ×4
  │     ├─ ReadFileTool(workDir)     — 8000 截断 + 路径穿越防护
  │     ├─ WriteFileTool(workDir)    — 自动创建父目录 + 路径穿越防护
  │     ├─ BashTool(workDir)         — 30s 超时 + 错误自纠错 + 8000 截断
  │     └─ EditFileTool(workDir)     — 4 级模糊匹配（L1 精确→L2 换行→L3 Trim→L4 逐行去缩进）
  │
  └─ 4. AgentEngine(provider, registry, enableThinking)
        ├─ 引擎无状态：WorkDir 跟随 Session，composer 按需创建
        └─ eng.run(session, reporter)  // Session 承载历史 + 工作区绑定
```

### Two-Stage ReAct 主循环

```
AgentEngine.run(session, reporter)
  │
  ├─ composer = new PromptComposer(session.workDir())  // 每次 run 动态创建
  ├─ workingMemory = session.getWorkingMemory(6)       // 截取最近 6 条（第11讲）
  ├─ contextHistory = [composer.build()] + workingMemory
  │
  └─ while(true) {  // Main Loop
        │
        ├─ availableTools = registry.getAvailableTools()
        │
        ├─ [Phase 1: Thinking] — 仅当 enableThinking=true 时执行
        │     thinkResp = provider.generate(contextHistory, /* tools=空列表 */)
        │     contextHistory.add(thinkResp) → session.append(thinkResp)
        │
        ├─ [Phase 2: Action]
        │     actionResp = provider.generate(contextHistory, availableTools)
        │     contextHistory.add(actionResp) → session.append(actionResp)
        │
        ├─ if (!actionResp.hasToolCalls()) → break
        │
        └─ Fork-Join 并发执行：
              observationMsgs = new Message[toolCalls.size()]
              CompletableFuture.runAsync(registry.execute, VIRTUAL_THREADS) ×N
              CompletableFuture.allOf(futures).join()
              按原始顺序追加到 contextHistory → session.append(observationMsgs)
      }
```

### Provider 请求翻译链路

```
OpenAICompatProvider.generate(messages, tools)
  │
  ├─ 1. buildRequestBody(messages, tools)
  │     ├─ SYSTEM  → {"role":"system", "content":"..."}
  │     ├─ USER    → {"role":"user"} 或 {"role":"tool", "tool_call_id":"..."}
  │     ├─ ASSISTANT → {"role":"assistant", "content":"...", "tool_calls":[...]}
  │     └─ tools 非空时挂载 "tools" 字段（支撑两阶段隔离）
  │
  ├─ 2. HTTP POST → https://open.bigmodel.cn/api/paas/v4/chat/completions
  │     └─ Header: Authorization: Bearer {apiKey}
  │
  └─ 3. parseResponse(responseBody)
        ├─ choices[0].message.content → Message.content
        └─ choices[0].message.tool_calls[].function.{name, arguments} → ToolCall[]
```

### 工具分发链路

```
ToolRegistryImpl.execute(toolCall)
  │
  ├─ 1. tool = tools.get(toolCall.name())  // Map O(1) 查找
  │     └─ null → return ToolResult(isError=true, "工具不存在")
  │
  ├─ 2. output = tool.execute(toolCall.arguments())
  │     ├─ ReadFileTool  → Files.readAllBytes + 8000 截断 + 路径穿越防护
  │     ├─ WriteFileTool → Files.writeString + MkdirAll + 路径穿越防护
  │     ├─ BashTool → ProcessBuilder(workDir) + 30s 超时 + 错误自纠错 + 8000 截断
  │     └─ EditFileTool → fuzzyReplace(L1→L2→L3→L4) + 唯一性校验
  │
  └─ 3. return ToolResult(id, output, isError)
        └─ Exception → 捕获后 isError=true，错误信息送给大模型自纠正
```

### 飞书远程调用链路

```
ClawApplication
  │
  ├─ 1. 创建 AgentEngine
  │
  ├─ 2. 创建 FeishuBot（注入 AgentEngine）
  │     └─ FeishuBot.start()
  │           ├─ REST Client（App ID + App Secret 认证）→ 用于发送消息
  │           └─ WebSocket Client（App ID + App Secret 认证）→ 连接飞书事件服务器
  │
  ├─ 3. WebSocket 长连接就绪，挂起等待事件推送
  │
  ├─ 4. 用户在飞书聊天中 @机器人 发消息
  │     └─ 飞书服务器通过 WebSocket 推送消息事件 → FeishuBot
  │           ├─ 解析 chatId + 文本内容
  │           ├─ sessionMgr.getOrCreate(chatId, workDir)  // Session 物理隔离
  │           ├─ session.append(new Message(Role.USER, text))
  │           └─ 虚拟线程异步 → AgentEngine.run(session, reporter)
  │                 ├─ FeishuReporter.onThinking()  → 飞书发送 "🧠 正在深度思考..."
  │                 ├─ FeishuReporter.onToolCall()  → 飞书发送 "🛠️ 调用工具..."
  │                 ├─ FeishuReporter.onToolResult() → 飞书发送 "✅/❌ 执行结果"
  │                 └─ FeishuReporter.onMessage()   → 飞书发送最终回复
  │
  └─ 5. 回复消息通过飞书 REST API 实时发送到用户聊天窗口
```

> **一句话**：FeishuBot 通过 WebSocket 长连接订阅飞书消息 → 收到消息异步拉起 AgentEngine → AgentEngine 通过 FeishuReporter 将思考、工具调用、最终回答实时回传飞书。

## 实现细节

### Two-Stage ReAct 原理

大模型是**自回归**（Auto-regressive）的——Phase 1 调用 `provider.generate(contextHistory, List.of())` 时不传任何工具 Schema，模型别无选择只能输出纯文本推理。这段推理文字存入 `contextHistory` 后，Phase 2 模型看到自己刚才写下的规划，会顺理成章生成对应的 ToolCall。这就是**自回归锚定效应**——用模型自己的输出来约束模型的下一步行为，远比在 Prompt 里写"请先思考"有效。

`enableThinking` 开关：简单任务（如查天气）关闭以省 Token 和延迟；复杂任务（如重构代码）开启以强制深度规划。

### 上下文工程体系：动态 System Prompt 组装（第10讲）

System Prompt 不应是硬编码的字符串常量，而应是**模块化"编译"和"动态链接"的操作系统内核**。通过 `PromptComposer` 动态拼装 System Prompt，替代引擎中原先的 `DEFAULT_SYSTEM_PROMPT` 硬编码常量。

**三层分层加载策略**（借鉴 OpenClaw）：

| 层级 | 来源 | 说明 |
|------|------|------|
| L1 极简内核 | 硬编码在 `PromptComposer` 中 | 身份认知 + 纪律红线，<1000 Tokens |
| L2 工作区守则 | `{workDir}/AGENTS.md` | 由人类在工作区维护的项目专属规范，不存在则跳过 |
| L3 技能外挂 | `{workDir}/.claw/skills/*/SKILL.md` | 符合 Agent Skills 规范的领域 SOP 知识包，不存在则跳过 |

**Agent Skills 规范**：每个技能封装为 `.claw/skills/{name}/SKILL.md`，以 YAML Frontmatter（`name` + `description`）开头，后跟 Markdown 执行指令正文。`SkillLoader` 负责扫描和解析，手写字符串切割实现 YAML Frontmatter 提取，零第三方依赖。

**设计哲学**：
- **状态外部化**：将易变的业务规范剥离出核心引擎代码，交由人类在工作区以文件形式维护
- **静默降级**：AGENTS.md 或 skills 目录不存在时，引擎正常工作，不抛异常
- **渐进式暴露**：当前实现全量加载，后续演进方向——启动时只放技能元数据，大模型按需调用工具精确加载正文

### Session 管理与 Working Memory（第11讲）

**核心问题**：多端并发下，如果共用一个 `contextHistory`，不同用户的指令和数据会混杂在一起，导致大模型"精神分裂"。同时，长程对话的历史会无限膨胀，打爆 Context Window。

**解决方案**：引入 `Session` 实体 + `SessionManager` + Working Memory 截取。

**Session 实体**：
- `id`：会话唯一标识（飞书 chatId、终端 session ID 等）
- `workDir`：会话绑定的工作区目录（WorkDir 跟随 Session，引擎不再持有 WorkDir）
- `history`：完整历史消息列表
- `mu`：`ReentrantReadWriteLock`，保证并发安全

**孤儿 ToolResult 防线**：`getWorkingMemory` 截取后，如果首条消息是 `Role=USER + toolCallId≠null`（工具结果但其 ToolCall 已被截断丢弃），循环丢弃直到遇到正常消息，否则大模型 API 报 400 Bad Request。

**设计哲学**：
- **物理隔离**：每个用户/群聊分配独立 Session，历史绝不交叉
- **记忆截断**：只将最近 6 条发给大模型，控制 Token 消耗
- **生命周期解耦**：引擎不持有状态，Session 可随时休眠/唤醒

### Context Compaction 上下文压缩（第12讲）

Working Memory 按条数截断但不限制单条体积——一条 `read_file` 误读 1MB 日志就能打爆 Context Window（400 Bad Request）。不能简单删除超长消息（会导致 ToolCall→ToolResult 逻辑链断裂），必须采用**阶梯降级**：丢弃冗余数据，但保住意图和逻辑链。

**双重降级防线**：

| 消息区域 | 策略 | 操作 |
|----------|------|------|
| System Prompt | 永远保留 | 不动 |
| 远期历史（保护区之外） | Observation Masking | ToolResult 内容 >200 字符 → 替换为占位文本；ToolCall 不动 |
| Working Memory（保护区内） | Head-Tail Truncation | 单条 >1000 字符 → 保留前 500 + 后 500，中间截断 |
| 远期 Assistant 冗长推理 | 折叠 | 替换为 `"...[早期的推理思考过程已折叠]..."` |

### Plan Mode 与状态外部化（第13讲）

摒弃代码内的复杂状态机，直接教大模型用文件系统管理长程任务。Agent 已有 read_file/write_file/edit_file，将"大脑状态"写成本地文件。

| 文件 | 用途 |
|------|------|
| `PLAN.md` | 宏观架构设计、技术选型、全局约束 |
| `TODO.md` | 细粒度待办清单（`- [ ]`），完成一步立即打勾 `- [x]` |

**Plan Mode 开关**：`PromptComposer` 新增 `planMode` 参数，开启时 System Prompt 注入三步微代码——STEP 1 环境嗅探（分支 A 全新创建 / 分支 B 断点续传）、STEP 2 单步打勾（严禁攒到最后）、STEP 3 迷失自救（read TODO.md）。`AgentEngine` 新增 `planMode` 字段，`EngineConfig` 新增 `planMode` 组件，`application.yml` 中 `plan-mode` 配置。

**慢思考 vs Plan Mode**：正交——Plan Mode 管战略方向（别跑偏），Thinking Phase 管微观推理（别跳步）。

### Error Recovery 错误自愈（第14讲）

大模型面对报错时倾向于"道歉后放弃"或"盲目重试相同的错误操作"。

**核心认知**：报错不应只是陈述，而应是行动指南。工具执行失败时，基于当前工具和错误类型，向 Context 注入带有强烈倾向性的恢复建议（锦囊妙计），错误变为触发排障 SOP 的扳机。

**RecoveryManager** 匹配规则（基于 `String.contains` 关键字，生产环境建议改用 POSIX 标准错误码）：

**注入地点**：`AgentEngine` 中 Fork-Join 的工具执行 lambda 内，`result.isError()` 时调用 `recoveryMgr.analyzeAndInject()` 替换 `observationContent`。无匹配时返回原错误。

### 工具防御机制

**ReadFileTool / WriteFileTool — 路径穿越防护**：

```
Path resolved = Paths.get(workDir).resolve(relativePath).normalize();
if (!resolved.startsWith(Paths.get(workDir).normalize()))
    → throw SecurityException  // 拦截 ../../etc/passwd
```

- ReadFileTool：`Files.readAllBytes` 后 8000 字符硬截断 + `...[截断]` 标记
- WriteFileTool：`Files.createDirectories(parent)` 自动创建父目录 + `Files.writeString` 写入

**BashTool — 4 大驾驭底线**：

| 底线 | 机制 | 目的 |
|------|------|------|
| 工作区约束 | `ProcessBuilder.directory(workDir)` | 限制命令执行范围 |
| 超时控制 | `process.waitFor(30, SECONDS)` → 超时 `destroyForcibly()` + 返回警告 | 防止 `top`、常驻服务卡死 |
| 错误自纠错 | `exitCode != 0` 不抛异常，将 stderr/stdout 拼成字符串返回 | 让大模型自己分析报错并纠正 |
| 长度截断 | 8000 字符硬截断 + 截断提示 | 防 Context OOM |

**EditFileTool — 4 级模糊匹配链**（吸收大模型"缩进幻觉"）：

| 级别 | 策略 | 条件 | 结果 |
|------|------|------|------|
| L1 | 精确匹配 `countOccurrences()` | count==1 | `replaceFirst()` 直接替换 |
| L1 | 精确匹配 | count>1 | 报错"匹配到 N 处，请提供更多上下文" |
| L2 | `\r\n` → `\n` 换行符归一化 | 归一化后 count==1 | 替换 |
| L3 | `trim()` 忽略首尾空白 | Trim 后 count==1 | 替换 |
| L4 | `lineByLineReplace()` 逐行去缩进 | — | 见下方 |

`lineByLineReplace()` 滑动窗口流程：
1. 原文件按 `\n` 切分为 `contentLines`
2. oldText 按 `\n` 切分，每行 `strip()` 去首尾空白
3. 滑动窗口遍历 `contentLines[i..i+len(oldLines)]`，每行 `strip()` 后与 oldLines 逐行比对
4. **唯一性校验**：matchCount==0 → 报错"未找到，请 read_file 确认"；>1 → 报错"匹配到 N 处，请提供更多上下文"
5. matchCount==1 → 将匹配到的原始行范围整体替换为 newText

### Provider 翻译关键设计

**ToolCallID 必须携带**：User 消息带 `tool_call_id` 时，OpenAI 协议翻译为 `role: "tool"`，Claude 协议翻译为 `tool_result` block。这是维系大模型推理链条不被中断的关键——缺少它，模型会丢失工具调用→结果的关联。

**Arguments 延迟解析**：`ToolCall.arguments` 使用 `JsonNode`，Main Loop 和 Registry 完全不拆包不关心参数内容，将反序列化责任交给各具体工具的 `execute()` 内部。这实现了极致解耦——Registry 加新工具时不需要改任何一行分发代码。

**authHeaders() 模板方法**：`AbstractHttpProvider` 默认返回 `Authorization: Bearer {apiKey}`（OpenAI 协议），`ClaudeProvider` 覆盖为 `x-api-key` + `anthropic-version: 2023-06-01`。

### ToolRegistryImpl 路由分发

- **Map O(1) 查找**：`LinkedHashMap<String, Tool>` 保持注册顺序，按 name 精确路由
- **模型幻觉处理**：工具不存在 → 返回 `ToolResult(isError=true)`，不崩溃，让模型看到错误后自纠正
- **异常捕获**：`tool.execute()` 抛异常 → Registry 捕获转为 `isError=true`，错误信息原样返回

### ConfigLoader 占位符解析

`${ZHIPU_API_KEY:default_value}` 解析流程：
1. 正则 `\$\{(\w+)(?::([^}]*))?\}` 匹配占位符
2. `System.getenv(ENV_VAR)` 读取环境变量，不存在则用默认值
3. kebab-case → camelCase：`ObjectMapper.setPropertyNamingStrategy(KEBAB_CASE)` + 先写回 JSON 再 `readValue` 迂回实现（因为 `treeToValue` 不支持命名策略）

### Fork-Join 并发工具分发

**独立性假设**：大模型在同一 Turn 中并行下发的多个 ToolCall，引擎假设它们互不依赖——如果存在强依赖，模型会分两个 Turn 完成。引擎的职责是无脑并行，最大化 I/O 性能。

**实现**：

```
预分配 Message[toolCalls.size()]               // 每个 idx 坑位对应一个 ToolCall
   ↓
CompletableFuture.runAsync(task, VIRTUAL_THREADS) ×N  // Fork: N 个虚拟线程同时执行
   ↓
CompletableFuture.allOf(futures).join()         // Join: 阻塞等待全部完成
   ↓
按原始顺序遍历 observationMsgs 追加到 contextHistory  // 数组天然保序
```

**设计要点**：

| 要点 | 机制 | 与 Go 对应 |
|------|------|-----------|
| 无锁线程安全 | 预分配数组，每个虚拟线程通过专属 idx 并发写入 | `make([]Message, len)` |
| 等待全部完成 | `CompletableFuture.allOf().join()` | `sync.WaitGroup` / `wg.Wait()` |
| 结果天然保序 | 数组索引即为 ToolCall 原始顺序 | 完全一致 |
| 闭包传参 | `final int idx` 捕获，防止循环变量陷阱 | `go func(idx int, call ToolCall)` |

**适用范围**：当前坚持独立性假设（所有 ToolCall 无脑并行）。场景建议——探索阶段（read_file / grep 等只读）并发加速；执行阶段（edit / write / bash 等写操作）如有依赖建议分 Turn 执行。

### YOLO 哲学

本地开发环境**全权信任大模型**，bash 不加黑名单、不做静态正则拦截。理由：
- 只要允许 Agent 执行代码，静态黑名单总能被绕过（变量拼接、写脚本再执行）
- 过度权限校验是"安全剧场"——看似安全实则对真实风险帮助有限
- **信任在业务层，拦截在物理层**（超时 + 截断 + 路径约束）
- 出错了交给 Git 回滚
- 线上运维场景会在后续章节引入 Human-in-the-loop 人工审批

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
│   │   ├── AgentEngine.java               # 引擎核心：Two-Stage ReAct + Fork-Join 并发工具分发（第11讲重构：无状态）
│   │   ├── Session.java                   # 会话实体 + SessionManager + Working Memory 截取（第11讲已实现）
│   │   ├── Reporter.java                  # I/O 解耦接口：onThinking/onToolCall/onToolResult/onMessage（已实现）
│   │   ├── ConsoleReporter.java           # CLI 模式终端输出实现（已实现）
│   │   └── FeishuReporter.java            # 飞书模式：调用 REST API 发消息（已实现）
│   │
│   ├── config/                            # === 配置层 ===
│   │   ├── AppConfig.java                  # 应用全局配置 record
│   │   ├── ProviderConfig.java             # Provider 配置
│   │   ├── EngineConfig.java               # 引擎配置
│   │   ├── ServerConfig.java               # 启动模式配置（cli/feishu）
│   │   ├── FeishuConfig.java              # 飞书 SDK 配置
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
│   │   ├── Skill.java                      # 技能领域 record：name + description + body（第10讲已实现）
│   │   ├── SkillLoader.java                # 扫描 .claw/skills/，手写 YAML Frontmatter 解析（第10讲已实现）
│   │   ├── PromptComposer.java            # 动态拼装 System Prompt（极简内核+AGENTS.md+Skills）（第10讲已实现）
│   │   ├── ContextManager.java            # 上下文生命周期管理
│   │   ├── Compactor.java                 # 上下文压缩：双重降级防线（第12讲已实现）
│   │   ├── RecoveryManager.java            # 错误自愈：锦囊提示注入（第14讲已实现）
│   │   └── EventInjector.java             # 运行时干预提醒注入
│   │
│   ├── tools/                             # === 工具与执行层 ===
│   │   ├── Tool.java                      # 工具接口：name() + definition() + execute(JsonNode)（已实现）
│   │   ├── ToolRegistry.java              # 注册与分发接口（新增 register 方法）（已实现）
│   │   ├── ToolRegistryImpl.java          # Map<String, Tool> 实现 O(1) 路由分发（已实现）
│   │   ├── ToolMiddleware.java            # 中间件接口（链式）（后续）
│   │   ├── ToolMiddlewareChain.java       # 中间件链执行器（后续）
│   │   ├── ApprovalGate.java              # 人类在环审批中间件（后续）
│   │   └── builtin/
│   │       ├── ReadFileTool.java           # 文件读取：workDir 注入 + 路径穿越防护 + 8000 截断（已实现）
│   │       ├── WriteFileTool.java          # 文件写入：路径穿越防护 + 自动创建父目录（已实现）
│   │       ├── EditFileTool.java          # 精确编辑：4级模糊匹配（L1精确→L2换行→L3 Trim→L4逐行去缩进）（已实现）
│   │       └── BashTool.java              # YOLO 核心：4 大驾驭底线（已实现）
│   │
│   ├── memory/                            # === 文件系统记忆（后续章节） ===
│   │   ├── MemoryStore.java               # 接口
│   │   └── FileMemoryStore.java           # 读写 TODO.md / PLAN.md / context.md
│   │
│   └── feishu/                            # === 飞书集成（已实现） ===
│       └── FeishuBot.java                 # WebSocket 长连接客户端 + 事件分发（已实现）
│
├── src/test/java/com/tinyclaw/           #（待实现）
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
| **并发工具调用** | `CompletableFuture` + Virtual Threads + 预分配数组 | Fork-Join 模式：每个 ToolCall 由独立虚拟线程执行，预分配数组通过索引并发写入实现无锁线程安全且结果天然保序（已实现） |
| **配置管理** | `application.yml` + `ConfigLoader` | kebab-case 命名，支持 `${ENV_VAR:default}` 占位符 |
| **日志** | SLF4J + Logback | 标准的门面 + 实现 |

## 已知问题

- **ClaudeProvider**：代码已实现 Anthropic Messages API 格式翻译，智谱 `/v4/messages` 端点返回 404。
- **BashTool 进程阻塞**：`reader.readLine()` 在 `process.waitFor(30, SECONDS)` 之前执行。当大模型启动常驻服务（如 `java -jar server.jar`）时，stdout 管道不关闭，`readLine()` 永久阻塞 → 虚拟线程死锁 → 引擎卡死。30s 超时在读取完成后才触发，无法防御此场景。后续需将输出读取与超时等待放入独立的虚拟线程中竞速。

## Maven 依赖（最小集）

- `com.fasterxml.jackson:jackson-databind` — JSON 序列化
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` — YAML 配置解析
- `org.slf4j:slf4j-api` — 日志门面
- `ch.qos.logback:logback-classic` — 日志实现
- `com.larksuite.oapi:oapi-sdk:2.5.3` — 飞书 Java SDK
- `org.junit.jupiter:junit-jupiter` — 测试

## 参考资料

- 飞书服务端 SDK 概述：<https://open.feishu.cn/document/server-docs/server-side-sdk>
- 飞书 Java SDK 开发前准备（Maven 坐标）：<https://open.feishu.cn/document/server-side-sdk/java-sdk-guide/preparations>
- 飞书 Java SDK 长连接处理事件（WebSocket）：<https://open.feishu.cn/document/server-side-sdk/java-sdk-guide/handle-events>
- 飞书 Java SDK 调用服务端 API（发消息）：<https://open.feishu.cn/document/server-side-sdk/java-sdk-guide/invoke-server-api>
- 飞书 Java SDK 场景示例：<https://open.feishu.cn/document/server-side-sdk/java-sdk-guide/scenario-example>

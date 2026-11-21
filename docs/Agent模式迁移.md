# Shannon 模式迁移到 Atoms

本次参考本地 Shannon 源码，把应用开发最直接需要的执行模式迁入现有 Java 21 / Spring Boot / JDBC / MySQL项目，前端继续使用 Vue 3。没有复制它的安装脚本、服务部署或供应商配置；参考项目的文档与提示词属于分析资料。

## 已落地的能力

| Shannon 中的实现 | Atoms 中的实现 | 实际范围 |
| --- | --- | --- |
| `go/orchestrator/internal/workflows/patterns/react.go`：Reason / Act / Observe、迭代限制和观察窗口 | `AgentLoop`、`ModelClient.decide`、`AgentSettings` | 模型每轮选一个动作；Java 执行白名单动作，再回传有界观察 |
| `patterns/reflection.go`：评估结果并携带反馈重新生成 | `REVIEW` 动作、`ModelClient.review` | 单独的模型请求审查候选源码；默认真实模式必须复核通过，未通过可重新生成 |
| `python/llm-service/llm_service/tools/base.py`、`tools/registry.py` | `ToolMetadata`、`ToolParameter`、`AgentTool`、`ToolRegistry`、`ToolExecutor`、`ToolResult` | 结构化目录、参数校验、统一执行及结果；模型描述从实际注册目录生成 |
| `patterns/registry.go`：模式注册与选择 | `ExecutionPattern`、`PatternRegistry`、`run_patterns` | 支持任务级选择 ReAct、Reflection、DAG；所选策略持久化，恢复不改变 |
| `strategies/dag.go`、`patterns/execution/`：依赖调度及并行分支 | `DagExecutor`、`PLAN_DAG`、`agent_dag_nodes` | 固定开发分析图，两个分析节点并行、汇总节点等待依赖；已完成节点复用 |
| workflow control、持久化和暂停恢复 | 现有执行令牌 + `agent_checkpoints` + `agent_steps` | 记录待执行动作和完成结果；恢复复用结果，旧令牌不能更新版本、检查点、事件或用量 |
| streaming events | 现有 SSE / 轮询 + `Run.steps` + `AgentTrace.vue` | 查看最新任务每一步的动作、简短说明、结果与状态，刷新后仍可读取 |
| budget middleware | `agent_usage`、`ModelClient.Tracking` | 请求发出前预占一次调用；包括计划、动作选择、源码生成、复核、普通问答；恢复不清零 |
| context window | 现有项目历史裁剪 + 有界工具观察 | 保留原需求与近期历史，工具观察按配置保留最近若干条，读取单文件最多返回8000字符 |
| orchestrator / worker / model 分层 | `GenerationService` / `AgentLoop` / `ModelClient` / `Store` | 调度、开发循环、模型通信与持久化分别承担职责；问答和生图继续使用独立路径 |

这里的需求复核是同一模型的一次独立请求，以源码和本轮需求为依据，不是另一个训练出的模型，也没有执行生成的程序。演示模式使用明确标记的本地动作策略，不调用模型或伪造模型复核。

## 执行顺序

1. `GenerationService` 获取项目基准和历史；已有计划直接复用。
2. 根据任务创建时保存的策略选择动作：ReAct 调用 `ModelClient.decide` 返回结构化 JSON；Reflection、DAG 根据检查结果选择下一步。这里只保存简短操作说明，不请求或保存模型的内部推理过程。
3. 事务内保存待执行动作和 `RUNNING` 记录，校验当前执行令牌。
4. `AgentLoop` 调用 `ToolExecutor`，由注册表查找实现并校验参数。工具作用于数据库中的虚拟源码快照，不会读取宿主机文件或运行 shell。
5. 在同一个短事务内保存候选源码、观察、计数及步骤结果。模型网络请求期间不占用数据库事务。
6. 校验和所需复核都通过后，`FINISH` 才能结束循环，由原有版本提交逻辑保存新版本。

| 动作 | 输入与行为 |
| --- | --- |
| `PLAN_DAG` | 并行执行需求分析与架构分析，等待二者完成后汇总开发约束；节点分别持久化 |
| `LIST_FILES` | 列出候选源码路径；尚未生成时使用基准版本 |
| `READ_FILE` | 按完整相对路径读取虚拟工作区中的一个文件，查找不到就返回错误观察 |
| `GENERATE` | 根据本轮目标、计划、基准源码、候选源码和观察生成完整快照；修改后清除校验与复核标记 |
| `VALIDATE` | 复用现有文件路径、大小和支持的语法检查，拒绝与基准完全相同的快照 |
| `REVIEW` | 单独请求模型检查需求遗漏、占位实现和明显错误，将具体反馈交给下一轮 |
| `FINISH` | 必须有候选源码且校验通过；真实模式默认还要求需求复核通过 |

非法 JSON、未知动作、读取不存在的文件以及提前完成，都会成为失败观察并消耗动作步数，不能改变工具白名单或绕过完成条件。网络或供应商错误会结束当前执行，保留原有版本；调用预算耗尽立即失败。

## 工具元数据和统一执行器

`ToolMetadata` 保存名称、版本、描述、分类、超时秒数、认证要求、危险操作标记、会话要求与访问范围。`ToolParameter` 独立保存参数名称、类型、描述、是否必需、字符串长度限制及枚举值。参数对象不允许出现未声明字段；必需字符串也不能只包含空白。

`ToolRegistry` 在启动时注册可信 Java 工具，检查重名工具和参数；同一份定义用于：

- `GET /api/agent/tools` 返回工具元数据及参数 schema。
- 给动作选择模型提供工具名称、描述和参数 JSON Schema。
- 执行前检查参数类型、必需字段、长度及枚举值。

新工具实现 `AgentTool`，加入 `BuiltinTools.create()` 的注册列表即可参与发现和调用。注册表在启动时合并内置工具、运维配置生成的公网读取工具及显式批准的远程 MCP 工具。配置更新后重启加载，目录构建后不可变。当前没有 Python 插件加载器。

动作协议现在使用 `arguments`：

```json
{"action":"READ_FILE","arguments":{"path":"index.html"},"summary":"检查页面入口"}
```

旧检查点中的 `{"action":"READ_FILE","path":"index.html"}` 仍可读取，会转换成参数对象。默认沿用动作 JSON。在支持 Chat Completions 函数调用和 `tool_choice: required` 的模型上，可在私有配置设置 `atoms.agent.native-tool-calls: true`。ReAct 每轮通过 `tools` 发送注册目录并接收一个 `tool_calls` 动作，由 Java 校验并执行。每轮独立请求，下一轮从已有观察窗口获取结果。

`ToolExecutor` 使用 Java 21 虚拟线程执行工具，按元数据中的超时等待结果。工具线程、DAG 节点线程显式继承同一任务的持久化模型调用预算。超时会中断工具并结束任务；迟到的返回值不能作为后续源码检查点提交。执行耗时保存在 `agent_step_metrics`，前端执行记录显示秒数。

执行器允许虚拟工作区工具和受控工厂生成的公网 HTTPS 读取工具。需要登录身份、危险操作审批、宿主机权限或其他网络访问的工具会被拒绝。虚拟线程与超时用于控制等待时间，生成源码的运行隔离由独立的验收容器负责。

### 配置动态公网工具

在后端私有配置设置 `atoms.agent.http-tools-file`，文件内容为 JSON 数组。例如：

```json
[
  {
    "name": "HTTP_DOCS",
    "description": "查询公开文档",
    "url": "https://docs.example.com/search",
    "query": [{"name": "q", "description": "公开关键词", "required": true}]
  }
]
```

`atoms.agent.openapi-tools-file` 可指向一个本地 JSON 清单，其中 `baseUrl` 是运维批准的固定 HTTPS 基础地址，`operations` 将获准的 operationId 映射到 HTTP_ 工具名，`spec` 为内联的 OpenAPI 3.0 或 3.1 JSON 对象。例如 `"operations": {"searchDocs": "HTTP_OPENAPI_DOCS"}`。基础地址以清单为准。仅支持无认证、无请求体的固定路径 GET 和内联字符串查询参数。遇到引用、路径模板、写操作、额外参数类型或重复定义时启动失败。修改后需要重启。

两种工厂共用同一个执行路径。模型只能提供长度受限的查询值。连接使用预检的公网 DNS 地址，不跟随重定向，不附带凭据，响应限制为 16 KiB 的 JSON 或纯文本。启用前应确认目标服务允许匿名 GET 并适合接收公开查询。默认未配置远程工具时，只加载内置目录。

### 配置远程 MCP 工具

在后端 `application.yml` 设置 `atoms.agent.mcp-tools-file`，指向运维维护的 JSON 数组。例如：

```json
[
  {
    "name": "MCP_DOCS",
    "description": "查询公开文档",
    "endpoint": "https://docs.example.com/mcp",
    "remoteName": "search",
    "readOnly": true,
    "parameters": [{"name": "q", "description": "公开关键词", "required": true}]
  }
]
```

重启后工具出现在 `GET /api/agent/tools`。端点、远程工具名称和只读认定由运维配置。MCP 调用支持文本或结构化结果，保留固定 DNS、同源限制和 16 KiB 响应上限。

- **自动发现**。将 `parameters` 替换为 `"discover": true`，启动时按 `tools/list` 分页寻找指定的 `remoteName`。支持字符串、整数、数字和布尔参数，以及字符串枚举。远端发现的其他工具不会自动注册。
- **OAuth**。HTTP 工具定义可加入 `oauth`，配置 `tokenEndpoint`、`clientId`、`clientSecret`，以及可选 `scope` 和 `resource`。使用客户端凭据模式获取并刷新 Bearer 令牌。请把含密钥的清单保存在部署方管理的私有位置。
- **旧版 SSE**。将 `transport` 设置为 `legacy-sse`，`endpoint` 填写服务的独立 SSE 地址。客户端读取 `endpoint` 事件后，只向同源地址发送初始化与工具请求。
- **stdio**。将 `transport` 设置为 `stdio`，用 `stdioImage` 指定本机已有、会启动 MCP stdio 服务的可信 Docker 镜像，并移除 `endpoint`。每次调用在无外网、非 root、只读根文件系统的容器中完成，JSON-RPC 按行传输。可与自动发现一起使用。

默认的 `streamable-http` 使用 MCP `2026-07-28` 无状态请求，支持单条 JSON 或同一个 POST 的 SSE 响应。OAuth 浏览器授权、多轮服务器交互和任务扩展仍未接入。

## 三种执行策略

在输入框中选择执行方式即可；前端记住上次选择，新任务提交时通过 `pattern` 传给后端。现有客户端不传时默认 `react`。问答和生图继续使用原有执行路径。

| 策略 | 行为 | 模型请求 |
| --- | --- | --- |
| `react` / 自主执行 | 模型每轮自主选择目录内工具；失败结果回到下一轮 | 计划、每轮动作选择、生成及所需复核 |
| `reflection` / 复核修复 | 生成 → 静态校验 → 需求复核 → 保存；失败反馈驱动重新生成 | 计划、生成、复核；不为选择下一步额外调用模型 |
| `dag` / 分解开发 | 先执行开发分析图，再按 Reflection 流程开发 | 计划、三个分析节点、生成及复核；不为选择下一步额外调用模型 |

Reflection 和 DAG 始终进行需求复核；`require-review` 控制 ReAct 的完成门槛。

DAG 默认使用代码定义的固定图：

```text
需求分析 ─┐
          ├─ 汇总开发约束 → 生成 → 校验 → 复核 → 保存
架构分析 ─┘
```

前两个节点最多两个并发，汇总只能在两个依赖都成功后启动。`DagExecutor` 支持校验环、缺失依赖和重复节点，通用调度器上限20个节点、4个并发；当前产品模板固定三个节点、两个并发。各节点的依赖、结果、状态和耗时独立保存，恢复时只执行未完成节点。固定节点标识带 `v1.` 前缀，改变节点输入或语义时应变更模板版本，避免复用不兼容输出。

在私有配置设置 `atoms.agent.dynamic-dag: true` 可为真实模型启用动态分析图。模型提出 1～5 个分析节点及依赖，Java 检查参数、未知依赖、重复节点及循环，再以最多两个并发执行，并添加依赖所有分析节点的汇总步骤。模型提出的图和已完成的节点会持久化，恢复时复用。已开始运行的旧固定图继续执行原流程。

分析节点只返回文本。汇总结果会随生成与修复请求传入模型，源码快照和最终版本仍由同一个开发循环负责。

API 示例：

```json
POST /api/projects/{projectId}/runs
{"prompt":"创建一个任务看板","baseVersion":null,"mode":"build","model":"你的已配置模型","pattern":"dag"}
```

`GET /api/config` 包含可选策略；任务查询、项目详情和 SSE 包含实际 `pattern`、执行 `steps` 及 DAG `nodes`。任务创建时保存的策略不会因为前端后来切换选项而改变。

## 配置

在现有私有配置中覆盖即可，不需要 Python 服务或新框架：

```yaml
atoms:
  agent:
    max-steps: 12             # 动作总数，3～100；恢复不清零
    max-generations: 2       # 源码生成次数，1～10
    observation-window: 6    # 最近工具观察条数，1～20
    require-review: true     # 真实模式保存前必须进行源码需求复核
    max-model-calls: 20      # 文本HTTP请求总数，1～200；请求失败也计数
```

默认顺利路径通常为：计划 → 选择生成 → 生成 → 选择校验 → 校验 → 选择复核 → 复核 → 选择完成 → 保存。动作选择本身会调用模型，因此费用和延迟会高于原来的两次固定模型调用。

限额累计值保存在数据库，恢复不清零；配置阈值在执行时读取，修改配置重启后使用新阈值。模型调用上限是调用次数限制，不是严格的 token 预算。token 数来自成功响应中的 `usage.prompt_tokens` / `usage.completion_tokens`，同时展示返回用量的调用数；缺少 usage、失败和失效执行的响应可能没有 token 记录，不能拿它作为供应商账单。

## Token 预算和模型路由

可在私有配置添加下列配置。预算默认关闭，路由配置留空时仍使用原来的模型选择。

```yaml
atoms:
  agent:
    token-budget: 80000
    routing:
      build-fast-model: qwen3-coder-flash
      build-fallback-model: qwen-plus
      chat-fast-model: qwen-flash
      chat-fallback-model: qwen-plus
```

- HTTP 请求发出前，Java 按序列化请求的 UTF-8 字节数、固定余量和最大输出额度预留 token 预算。数据库事务同时检查请求次数和剩余预算，多节点并发与暂停恢复使用同一账本。
- 供应商返回用量时，以实际输入与输出 token 结算。请求失败、用量缺失或执行令牌失效时保留预留，防止未知成本被重复使用。任务接口的 `usage.chargedTokens` 包含已结算 token 和尚未释放的预留。预留是估算，供应商实际用量可能超过估算，结算后会阻止后续超预算请求。
- 配置快速模型后，模型下拉框会出现“自动选择”。300 字以内的问答或新建构建任务可选快速档，已有基准版本、DAG 构建和较长请求走默认模型。显式指定模型优先。
- 主模型返回可重试 HTTP 错误、传输失败或熔断时，可尝试一次同模式的备用模型。备用模型可以路由到独立供应商。每次尝试独立扣除调用次数与预留额度。快速及备用模型均须在对应模式白名单内。任务的模型字段仍记录初选模型。本功能作用于文本模型调用。
- 文本模型按模型分别累计可重试 HTTP 故障和网络 I/O 故障。默认连续 3 次打开熔断，冷却 30 秒后放行一次探测。熔断期间跳过该模型并按白名单尝试备用模型，跳过的模型不会消耗预算。用 `atoms.agent.routing.circuit-failure-threshold` 和 `atoms.agent.routing.circuit-cooldown-seconds` 调整，阈值设为 0 可关闭。状态由同一实例内的任务共享，重启后重置。

## 恢复与兼容

启动时 `schema.sql` 通过 `CREATE TABLE IF NOT EXISTS` 创建 Agent 使用的六张附加表：`agent_checkpoints`、`agent_steps`、`agent_usage`、`run_patterns`、`agent_step_metrics`、`agent_dag_nodes`。已有表保持兼容，不改写项目、版本、消息或旧检查点。没有策略记录的旧任务按 ReAct 恢复；旧候选源码检查点先校验后继续。

已完成的动作不会重复调用模型。暂停时正在进行的动作可能已经在供应商侧执行，恢复时可能重新调用；这是可重试的虚拟源码生成，不承诺外部请求严格执行一次。已经预占的模型调用次数不会退回。旧请求迟到后会被执行令牌拒绝写入。

默认使用 Temporal 从数据库派发待执行任务。手动暂停与待审批状态保留。主调度器为每个服务端执行的任务启动独立 Workflow，由 Activity 执行当前 Agent 逻辑。Temporal 记录调度历史并重试失联的 Activity，业务库检查点负责动作恢复。Research 各阶段和模板角色子任务已拥有独立的 Workflow 和 Activity 历史。模板认知策略的模型步骤也已接入独立 Worker。新任务使用原生 Temporal 父子工作流关系，并通过版本标记兼容旧历史。完成的 `FINISH` 检查点只代表准备保存，最终是否保存以任务 `SUCCEEDED` 与版本记录为准。

## 动态 DAG 并行修改源码

在 `application.yml` 同时启用 `atoms.agent.dynamic-dag` 与 `atoms.agent.dynamic-dag-edits`。动态 DAG 首轮生成按已保存的分析计划运行最多两个并行节点。每个节点返回相对路径到完整文件内容的映射，`null` 表示删除。节点结果持久化，暂停恢复时复用已完成的结果。

合并保留未参与修改的基准文件，相同改动只应用一次。不同节点修改同一文件、路径冲突或结果未通过源码校验时，交由完整生成步骤统一修订。最终候选仍经过原有校验与复核流程。

## 其他模式的迁移设计与状态

以下能力尚未完整迁移，表中区分了已有基础与后续所需工作：

| 模式 | Shannon 参考位置 | Java 落点及前置条件 |
| --- | --- | --- |
| 动态开发 DAG、多节点修改源码 | `strategies/dag.go`、`patterns/execution/` | 已支持可选的模型动态分析图与节点恢复；启用 `dynamic-dag-edits` 可按节点并行修改并合并源码 |
| Swarm、Debate、Tree of Thoughts | `swarm_workflow.go`、`patterns/debate.go`、`tree_of_thoughts.go` | 节点和共享预算完成后增加角色、候选结果、评选及收敛条件；不能简单并发调用模型后覆盖同一源码快照 |
| Token 预算、模型分层与故障降级 | `middleware_budget.go`、`internal/budget/`、`internal/models/` | 已实现估算预留、实际用量结算、自动选型及跨供应商备用模型。精准 tokenizer 计数和对齐全部供应商协议仍待迁移 |
| Temporal 长任务可靠性 | `go/orchestrator/internal/workflows/` | 已接入 Temporal Java SDK 和本地 Docker 服务，任务级 Activity 重试通过执行令牌保护持久化写入。Research 和 Agent 节点使用子工作流，并保留历史重放兼容标记 |
| MCP 和外部工具加载 | `python/llm-service/llm_service/tools/registry.py` | 已支持已批准的只读工具、自动发现、OAuth 客户端凭据、stdio 和旧版 SSE。浏览器授权与副作用工具审批仍待迁移 |
| 动态工具工厂与出站 SSRF 防护 | `tools/plugin_loader.py`、`tools/openapi_tool.py` | 已实现受控 JSON 公网 GET 工厂和 OpenAPI 只读子集导入，图片下载和工厂工具共用 `PublicHttps`。网络出口隔离、广泛 OpenAPI 兼容、外部 MCP 及其权限仍待迁移 |
| Skills、可配置流程 | `config/skills/`、`template_workflow.go` | 本地受控配置加载器与可执行工具权限分离；技能文本不能扩大执行权限 |
| 隔离编译与浏览器验收 | Rust Agent Core / WASI、Playwright 服务 | 已接入可选的 Docker Maven 测试与 Chromium 静态页面冒烟检查。启动生成的后端服务、联调和业务交互测试仍待实施 |
| Prometheus / OpenTelemetry | Shannon metrics / tracing | Spring Boot Actuator / Micrometer 与链路标识，把现有 run、step、模型调用关联起来；当前只有持久化步骤与用量 |
| 人工审批、定时任务、身份与租户 | Shannon approvals / scheduled workflows / gateway | 先补稳定身份、项目权限与审批记录，再引入 Spring 调度或 Temporal；当前匿名浏览器 Cookie 不是完整租户模型 |

这些能力都可以以 Java 为主实现。Python 只在未来选用的专用运行时或 SDK 确实没有可用 Java 接口时，作为隔离的工具适配服务考虑；当前没有这种需要。

## 验证

```powershell
cd backend
mvn test
cd ../frontend
npm test
npm run build
```

测试覆盖动作 JSON、元数据到模型描述的一致性、注册扩展、参数类型/枚举/必需字段/长度约束、提前完成拦截、超时、权限拒绝、Reflection 修复，以及 DAG 并行、依赖、环检查、失败阻断和节点恢复。通过本地 HTTP 模型桩验证调用预算、token 记录和跨工具线程、DAG线程的预算继承。项目记忆和原有暂停恢复测试继续保留，不调用真实付费模型。

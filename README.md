# Atoms Demo

Vue 3 + TypeScript + Spring Boot + MySQL 的 AI 应用生成工作台。界面参考 atoms.dev/zh/dashboard 的浅灰侧栏、居中创作区、圆角输入框与中性色布局。仅为现有功能调整样式，不包含参考站中的游戏、视频、积分或工具连接入口。

## 快速体验（不需要 MySQL 和模型密钥）

环境：Java 21、Maven 3.8+、Node.js 20.19+。

在项目根目录打开两个终端：

```bash
# 终端一：文件型 H2 数据库 + 明确标注的模板演示
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

```bash
# 终端二
cd frontend
npm ci
npm run dev
```

打开 http://127.0.0.1:5173 。前后端请始终使用同一个主机名，不混用 localhost 和 127.0.0.1，匿名项目依赖浏览器 Cookie。

`local` 配置默认使用 H2 文件数据库（`backend/data/`）和演示模板；演示支持个人记账、任务看板、落地页，以及“暗色”“绿色”“蓝色”修改。其他描述只会选择内置模板，不代表 AI 理解或完成了任意需求。界面会持续标注演示模式。

## MySQL 与真实模型

本地真实配置在 `backend/config/application-private.yml`（已排除Git），可直接修改该文件：

- `spring.datasource.url`、`username`、`password`：本地 MySQL 配置。
- `atoms.demo`：使用真实模型时设为 `false`。
- `atoms.model`：集中管理全部模型配置，包括文本地址与密钥、构建及对话模型选项，以及 `image` 子节点下的图像接口、密钥和模型选项。

原有地址、密钥和数据库密码已保留在私有配置中。`backend/src/main/resources/application.yml` 仅保留可公开的默认项。无论从项目根目录或 backend 目录启动，都会自动加载私有配置。

从后端工程直接启动，或在 IDEA 运行 `AtomsApplication`，不需要脚本或 `.env`：

```bash
cd backend
mvn spring-boot:run
```

已有本地 MySQL 时无须 Docker。请先创建 `atoms` 数据库，表结构会在启动时自动创建。如需另起容器，可在 `backend` 目录运行 `docker compose up -d mysql`，并把私有配置中的账号密码与 compose.yml 对齐。

模型使用 OpenAI 兼容的 `/chat/completions` 接口。`base-url` 必须包含 `https://` 或 `http://` 和供应商要求的 API 路径前缀，不包含 `/chat/completions`。`name` 应选择支持代码生成的通用对话模型。

阿里云百炼工作空间域名的兼容接口地址应包含 `/compatible-mode/v1`，例如 `https://<工作空间ID>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`。代码会追加 `/chat/completions`。代码生成使用通用对话模型（当前配置为 `qwen-plus`），不要使用 `qwen-mt-lite` 等专用翻译模型。

`application-local.yml` 仅用于 H2 + 内置模板演示；真实模型与 MySQL 启动时不要启用 `local` profile。模型密钥只在后端使用。

## 构建、对话、图像与模型切换

输入框下方可选择“构建应用 / 对话问答 / 图像生成”，并按模式切换模型。对话支持多轮上下文，图片可在项目内查看和下载；问答和生图不修改代码版本。现有阿里云 API Key 可在授权范围内复用，图像使用独立的原生接口，费用单独计量。

配置、推荐模型和手动验收步骤见 [模型配置与使用](docs/模型配置与使用.md)。重启后端会自动添加三张新表，保留原有项目与代码版本。

## 已实现

- 构建、普通问答、文生图三模式；按模式选择并记住模型。
- 多轮对话上下文、生成图片持久化与下载、模型与模式历史记录。
- 匿名浏览器空间、项目列表和搜索，刷新后恢复当前项目。
- 需求 → 计划 → ReAct 动作循环：查看文件、读取源码、生成或修复、静态校验、需求复核、保存。默认最多12步、2次源码生成、20次文本模型调用。
- 持久化执行记录、工具观察与候选源码检查点；支持暂停、服务重启后恢复，以及模型调用次数和供应商返回的 token 用量记录。
- 三种构建执行方式：自主执行（ReAct）、复核修复（Reflection）、分解开发（DAG）。DAG 并行分析需求与架构，完成后汇总开发约束；节点结果可独立恢复。
- 结构化工具元数据、参数 schema、注册目录及统一执行器；工具参数、权限范围、超时由 Java 执行路径检查。工具目录可通过 `/api/agent/tools` 查询。
- 有界任务线程池，每个项目同时只允许一个任务；每会话每小时最多20次生成。
- SSE 推送状态，轮询兜底；断线不自动重复提交。
- 前端静态应用预览，以及Java等后端工程的多文件源码查看、编辑、保存与导出。
- ZIP按实际文件路径打包，支持导出当前查看的历史版本；未保存的编辑需先保存。
- 不可变版本、查看历史、恢复为新版本、ZIP 源码导出。
- 乐观版本检查、短事务提交，失败不会覆盖已有版本。
- iframe sandbox、受限 CSP、来源窗口及预览标识校验、运行错误展示。
- 服务重启后把中断任务暂停；恢复时复用已完成的计划与动作结果，重试尚未完成的动作。

## 手动验收

1. 点“个人记账”，开始创造；在预览新增一笔金额，检查合计与笔数，删除后再次检查。
2. 继续输入“改成暗色主题”，检查新版本与预览。
3. 打开代码，编辑 CSS，点运行，再点保存；刷新确认代码仍然存在。
4. 打开版本历史，查看旧版，再恢复，确认生成了新版本且历史没有丢失。
5. 切换桌面/手机预览，停止并重新运行预览。
6. 导出 ZIP，解压后打开 index.html，检查三个文件与当前保存版本一致。
7. 新建任务看板，检查添加、完成、删除；新建落地页，检查行动按钮。
8. 真实模式配置无效模型或密钥，发起修改，确认错误提示与旧版本保留。
9. 在另一个无痕会话中，确认看不到原会话项目；请求其他会话项目 ID 应返回404。

## 构建

后端包含项目记忆、暂停恢复、Agent 动作与调用预算测试；前端包含输入和预览测试。

```bash
cd frontend
npm run build
cd ../backend
mvn test
mvn -DskipTests package
```

前端产物在 `frontend/dist/`，后端在 `backend/target/atoms-backend-0.1.0.jar`。

生产部署需要同源反向代理：静态资源指向前端 dist，`/api/` 转发 Java 服务并保留 Host；SSE 禁用代理缓冲。Java 默认仅监听127.0.0.1；容器部署可在配置中设置 `server.address: 0.0.0.0`。启用 HTTPS 后设置 `server.servlet.session.cookie.secure: true`，匿名访问 Cookie 也会沿用该设置。不要把 Vite 开发服务器用作生产服务。

## 文件结构

```text
frontend/src/App.vue             首页和工作台界面
frontend/src/style.css           基础样式与响应式布局
frontend/src/atoms-theme.css     Dashboard 主题与组件样式
frontend/src/api.ts              API与数据类型
frontend/src/preview.ts          预览组装、语法检查与错误桥接
backend/src/main/java/dev/atoms/
  AtomsApplication.java         Spring Boot 入口
  web/                          HTTP 接口、健康检查与请求防护
  agent/                        任务编排、工具执行、模型调用与任务持久化
    GenerationService.java      任务调度、暂停与恢复
    AgentLoop.java              执行循环与检查点
    pattern/                   ReAct、Reflection、DAG 策略与节点调度
    tool/                      工具目录、参数协议、执行器与内置工具
    model/                     模型客户端、响应解析与 HTTP 通信
    persistence/               任务事务、检查点、步骤、DAG 节点与模型用量
  workspace/                    项目快照、记录查询、源码校验、ZIP 导出与离线模板
backend/src/main/resources/      数据库schema与环境配置
backend/compose.yml             可选的本地MySQL容器
```

Shannon 模式的 Java 迁移范围、配置、执行流程及后续路线见 [Agent模式迁移](docs/Agent模式迁移.md)。当前使用 Java 实现，不依赖 Python、Go、Rust 或 Temporal 服务。

### Agent Skills

在后端私有配置中设置 `atoms.agent.skills-directory` 指向本地技能目录。技能使用 Shannon 的 Markdown 与 YAML frontmatter 格式，例如：

```markdown
---
name: review
version: 1.0.0
category: development
description: 按验收条件复核实现
requires_tools:
  - READ_FILE
budget_max: 5000
---
逐项检查本轮验收条件，并说明每项检查所依据的文件。
```

启动时加载技能。用 `GET /api/skills?category=development` 查看目录，用 `GET /api/skills/review@1.0.0` 查看指定版本。调用 `POST /api/projects/{id}/runs` 时可加上 `"skill": "review@1.0.0"`，或用 `"skill": "review"` 选择最新启用版本。任务提交后固定技能版本和正文，暂停恢复时继续使用同一份配置。

声明了 `requires_tools` 的技能使用构建模式与 ReAct，工具名须与 Atoms 注册表一致。正文作为现有系统提示的一部分，`requires_role` 提供角色提示。`budget_max` 与全局 token 预算均为正数时取较小值。当前 API 对标记为 `dangerous` 的技能返回 403。

### Workflow Templates

在后端私有配置中设置 `atoms.agent.workflow-templates-directory`。应用启动时加载目录中的 YAML，例如：

```yaml
name: project_review
description: 分析需求并汇总验收条件
version: 1.0.0
nodes:
  - id: requirements
    type: simple
    metadata:
      query: 列出本轮需求和要保留的现有功能
  - id: summary
    type: simple
    depends_on: [requirements]
    metadata:
      query: 根据依赖输出汇总实施步骤和验收条件
```

用 `GET /api/workflow-templates` 查看模板。在 `POST /api/projects/{id}/runs` 请求中设置 `"mode": "build", "template": "project_review"` 来执行模板。节点也可以通过顶层 `edges` 声明依赖，每条边包含 `from` 和 `to`。节点输出按任务保存供暂停恢复，完成后用于 Reflection 构建。模板最多 20 个分析节点，并发上限为 2。

通过 `extends: [project_review@1.0.0]` 继承指定版本，也可仅使用模板名。按声明顺序合并父模板，最后叠加子模板。同 ID 节点按字段覆盖，非空的 `depends_on` 或顶层 `edges` 替换继承所得列表。任务保存展开后的模板，恢复时可直接使用。

- `defaults.budget_agent_max` 给出默认节点预算，节点可用 `budget_max` 覆盖。值为 0 时仅使用任务级预算。节点与任务预算在同一事务预留并结算。分析请求将输出上限限制在节点预算的三分之一以内，输入预留沿用任务级估算。
- `on_fail.retry` 为暂时性网络故障和可重试的供应商响应增加 0～5 次尝试。尝试次数按次执行计算，模型用量和预留跨暂停恢复保留。
- `type: dag` 可在 `metadata.tasks` 中配置分析子任务。子任务用 `id`、`query` 和 `depends_on` 表达依赖，可用 `role` 指定分析视角。子任务共享父节点预算，结果各自保存，父节点按声明顺序合并输出。
- DAG 与 Supervisor 节点可用 `metadata.execution` 选择 `sequential`（按声明顺序传递上一步结果）、`parallel`（独立子任务并发）或 `hybrid`（按依赖调度并发）。`parallel` 和 `hybrid` 默认并发为 2，可用 `metadata.max_concurrency` 设置为 1～4。旧模板仍按原有的单并发规则执行。

- `type: cognitive` 搭配 `strategy: reflection` 会按初稿、复核、修订三个阶段运行，每个阶段都可恢复。显式预算低于 3000 token 时使用单轮分析，其余预算仍按节点共享。

- `strategy: debate` 使用三个项目视角，先给出立场，再经三轮回应和一次汇总。各阶段可恢复。预算低于 6000 token 时采用 Reflection，低于 3000 token 时采用单轮分析。辩论阶段使用较小的单次输出上限。
- `strategy: chain_of_thought` 输出按需求、约束、步骤和验收组织的方案。预算低于 4000 token 时使用普通单轮分析。

- `strategy: tree_of_thoughts` 按得分优先探索方案，深度最多 3，每次最多 3 个分支，总计最多 15 个，低于 0.3 的分支会剪除。每次扩展由同一次模型调用生成并评分，扩展结果和最终汇总均有检查点。预算低于 8000 token 时切换到 Chain of Thought。
- `on_fail.degrade_to` 支持认知节点在网络故障或可重试的供应商故障耗尽尝试后切换到 `react`、`reflection` 或 `chain_of_thought`。策略切换会保存，恢复后继续使用降级策略。

- `type: supervisor` 将目标动态拆成最多四个分析任务，按依赖执行后汇总。拆解、子任务和汇总各有检查点，使用同一节点预算。当前子任务只执行项目分析。

- 节点可通过 `tools_allowlist: [LIST_FILES, READ_FILE]` 启用工作区查阅。模型可以选择白名单中的一个操作或跳过。工具观察写入检查点，并提供给该节点的分析与子任务。白名单覆盖节点的查阅阶段。后续 Reflection 构建仍使用自己的执行策略。

- 认知节点支持 `on_fail.escalate_to: supervisor`。暂时性故障耗尽可用重试及降级后，任务会保存升级记录，再进入 Supervisor 分析流程，沿用同一个节点预算。

- `defaults.require_approval: true` 让任务在执行前进入 `AWAITING_APPROVAL`。可在工作台批准或拒绝，也可通过 `POST /api/runs/{id}/approval?approved=true` 或 `approved=false` 操作。审批通过后才开始模型调用，审批沿用项目会话权限。
- 工作台的「执行方式」可选择已配置的工作流模板。API 提交时可用 `template` 和 `templateVersion` 指定模板和版本，或直接使用目录返回的模板 ID。请求的模板未匹配时走常规执行策略。设置 `requireTemplate: true` 要求匹配，否则返回 400。兼容 Shannon 字段名 `template_name`、`template_version` 和 `disable_ai`，其中 `disable_ai` 对应模板必选，模板中的分析节点仍可调用模型。

设置 `atoms.agent.learning-router-enabled: true` 后，工作台可选择「自动选择」，API 可提交 `pattern: "auto"`。Java 路由器从当前项目最多 50 条同类型构建结果中查找相似需求，按成功率选执行策略。没有合适记录时使用 ReAct。路由使用本地字符相似度和数据库历史，无需调用模型。

项目记忆在近期对话之外，还会根据当前需求召回最多三条同项目的早期成功记录。当前检索使用最近 100 条成功记录中的字符相似度，召回内容作为参考资料随当前请求传给模型。

### Shannon Gateway 会话

`POST /api/v1/tasks` 可设置 `mode: "auto"` 使用项目内学习路由。匹配到显式模板时优先执行模板。DAG 和 Supervisor 模板可用 `metadata.role_tools` 为子任务角色分配只读工具。角色权限须来自节点的 `tools_allowlist`，未配置的角色没有工具权限。

`POST /api/v1/tasks` 接受 JSON 对象 `context`，它随任务保存并作为模型的参考资料。会话历史返回任务结果、用量、费用、时间和上下文元数据。通过 `PATCH /api/v1/sessions/{id}` 更新标题，通过 `DELETE /api/v1/sessions/{id}` 软删除会话。会话有活动任务时返回 409。

通过 `atoms.gateway.tenant-tokens.<tenant>` 配置各租户的独立 Bearer 令牌。至少配置一个令牌后，所有 `/api/v1/` 接口均要求有效的 `Authorization: Bearer ...`。每个租户令牌对应一个 Gateway 身份。未配置令牌时沿用访客会话模式。

### 运行指标

可用 `atoms.model.rates[模型ID].input` 和 `atoms.model.rates[模型ID].output` 配置输入与输出每百万 token 的美元费率。结算使用实际应答模型的费率，并将金额写入数据库。`atoms_model_estimated_cost_usd_total` 汇总配置费率后产生的估算费用，费率调整只影响后续结算。

设置 `atoms.metrics.enabled: true` 和 `atoms.metrics.token` 后，Prometheus 可以使用 Bearer 凭据抓取 `/metrics`。指标包含提交任务总数、各状态当前任务数、模型调用次数、供应商报告的输入和输出 token，以及已记录的 Agent 步骤耗时。指标从数据库汇总，应用重启后仍保留。模型调用计数包含已预留但可能失败的调用，token 指标使用供应商返回的实际用量。

Supervisor 的独立子工作流、角色工具执行及单独的审批人角色尚未接入。

## 当前边界

构建模式按需求生成静态页面或后端工程源码，版本以“文件相对路径 → 完整内容”保存，最多80个文件、总计1MB。静态页面的 index.html、style.css、script.js 支持预览，脚本按普通 script 执行；预览会移除内联事件处理器与外部资源引用。生成应用数据保存在内存中，重载预览会清空；工作台项目与源码另行持久化。静态校验不等于功能测试。

匿名访问 Cookie 是访问凭证，清除后无法找回该浏览器的项目；尚无注册登录与跨设备同步。限流状态保存在会话内，只适合本地单实例 Demo。当前启动恢复逻辑也按单实例设计，不应多实例共用数据库。

iframe 不是资源隔离容器，恶意死循环仍可能影响浏览器；“停止”只是卸载 iframe，并非可靠的 CPU 限制。公开运行任意生成代码前需增加独立预览域和执行资源隔离。

字体通过 Google Fonts 加载，网络不可达时自动使用系统字体。没有使用官网私有资源或伪造上线、支付等能力。

## 后端工程源码导出

在“构建应用”模式明确指定语言与框架，例如“生成Java Spring Boot后端工程，包含pom.xml、接口和启动说明”。后端源码会按实际目录保存并显示在代码面板，ZIP包含相同文件，不再强制三个静态文件。后端代码仅保存和导出，工作台不执行或编译生成的后端程序，请在本地IDE运行验证。

查看历史版本时，导出对应查看版本；有未保存编辑时，先点保存再导出。旧版已经生成的HTML版本不会自动变成后端工程，需要重新生成。

## GitHub 与公网部署

GitHub仓库保存源码，GitHub Actions自动构建包含前端的Java应用。完整服务使用Docker运行Java、MySQL和Caddy网关；GitHub Pages不能运行本项目的Java后端和MySQL。

部署步骤见 [GitHub与公网部署](docs/GitHub与公网部署.md)。私有配置和数据库密码均排除Git与Docker构建上下文，不要手动强制添加这些文件。

### Railway

使用Railway时按 [Railway部署说明](docs/Railway部署.md) 创建应用与MySQL服务，并设置 `SPRING_PROFILES_ACTIVE=railway`。应用自动监听平台端口并通过 `/api/health` 检查数据库连接。密钥只放Railway私密变量，不提交GitHub。

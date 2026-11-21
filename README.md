# Atoms Demo

Vue 3 + TypeScript + Spring Boot + MySQL 的 AI 应用生成工作台。界面参考 atoms.dev/zh/dashboard 的浅灰侧栏、居中创作区、圆角输入框与中性色布局。仅为现有功能调整样式，不包含参考站中的游戏、视频、积分或工具连接入口。

## 本地启动（MySQL 与 Temporal）

环境：Java 21、Maven 3.8+、Node.js 20.19+。

在项目根目录打开两个终端：

```bash
# 终端一：启动 Temporal，配置 MySQL 和模型密钥，然后运行后端
docker compose -p atoms-temporal -f deploy/compose.temporal.yml up -d --wait
cd backend
mvn spring-boot:run
```

```bash
# 终端二
cd frontend
npm ci
npm run dev
```

打开 http://127.0.0.1:5173 。前后端请始终使用同一个主机名，不混用 localhost 和 127.0.0.1，匿名项目依赖浏览器 Cookie。

使用本机 Docker MySQL 时，确保容器映射了端口 3306、已创建 `atoms` 数据库，并按下节填写数据库和模型配置。

## MySQL 与真实模型

本地真实配置在 `backend/config/application-private.yml`（已排除Git），可直接修改该文件：

- `spring.datasource.url`、`username`、`password`：本地 MySQL 配置。
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

模型密钥只在后端使用。

## 构建、对话、图像与模型切换

数据库测试需要运行中的 Docker。测试会启动独立的 MySQL 8.4 容器，为各组测试创建隔离的数据库，并在测试进程退出时清理容器和数据卷。可用 `ATOMS_MYSQL_TEST_IMAGE` 指定本机已安装的兼容 MySQL 镜像。


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
- 有界任务线程池，满载时待执行任务保留在数据库中。每个项目同时只允许一个任务，每会话每小时最多20次生成。
- SSE 推送状态，轮询兜底；断线不自动重复提交。
- 前端静态应用预览，以及Java等后端工程的多文件源码查看、编辑、保存与导出。
- ZIP按实际文件路径打包，支持导出当前查看的历史版本；未保存的编辑需先保存。
- 不可变版本、查看历史、恢复为新版本、ZIP 源码导出。
- 乐观版本检查、短事务提交，失败不会覆盖已有版本。
- iframe sandbox、受限 CSP、来源窗口及预览标识校验、运行错误展示。
- 服务重启后自动重新调度中断和排队的任务，保留用户主动暂停和待审批的状态。恢复时复用已经保存的计划与动作结果，重试尚未完成的动作。

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
    runtime/                    任务执行、暂停恢复、Temporal、Research 和 Swarm
    pattern/                    ReAct、Reflection、DAG 策略与节点调度
    approval/                   任务级和工具级审批策略
    schedule/                   定时任务定义、触发和入队
    gateway/                    Gateway Agent 配置和工具校验
    tool/                       工具注册、参数协议、执行器与内置工具
    model/                      模型路由、客户端和供应商响应解析
    network/                    工具访问外部网络时的地址校验
    persistence/                任务事务、检查点、步骤、DAG 节点与模型用量
    skill/                      技能加载和目录
  workspace/                    项目快照、记录查询、源码校验、ZIP 导出与离线模板
backend/src/main/resources/      数据库schema与环境配置
backend/compose.yml             可选的本地MySQL容器
```

Shannon 模式的 Java 迁移范围、配置、执行流程及后续路线见 [Agent模式迁移](docs/Agent模式迁移.md)。应用使用 Java 实现，默认通过 Temporal 调度任务。

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

工作台默认发送 `pattern: "auto"` 和 `model: "auto"`，可在「任务选项」覆盖。启用 `atoms.agent.learning-router-enabled` 后，构建优先复用当前项目中成功率较高的相似任务策略。新项目或没有相似记录时，后端根据需求长度和意图选择 DAG、Reflection 或 ReAct。禁用该开关后，自动执行方式回退为 ReAct。

构建自动选型时，计划和分析使用快模型，复杂任务使用默认构建模型生成，复核使用默认构建模型。研究自动选型时，规划与补充检索优先使用快模型，报告使用默认对话模型；`atoms.research.models` 中的阶段配置优先。快模型可通过 `atoms.agent.routing.build-fast-model` 和 `chat-fast-model` 指定，未配置时优先使用白名单中的内置快模型。没有可用快模型时采用对应默认模型。阶段模型随任务保存，暂停恢复时沿用原选择。

项目记忆在近期对话之外，还会根据当前需求召回最多三条同项目的早期成功记录。检索范围为最近 100 条成功记录，召回内容作为参考资料随当前请求传给模型。

### Shannon Gateway 会话

`POST /api/v1/tasks` 可设置 `mode: "auto"` 使用项目内学习路由。匹配到显式模板时优先执行模板。DAG 和 Supervisor 模板可用 `metadata.role_tools` 为子任务角色分配只读工具。角色权限须来自节点的 `tools_allowlist`，未配置的角色没有工具权限。

`POST /api/v1/tasks` 接受 JSON 对象 `context`，它随任务保存并作为模型的参考资料。会话历史返回任务结果、用量、费用、时间和上下文元数据。通过 `PATCH /api/v1/sessions/{id}` 更新标题，通过 `DELETE /api/v1/sessions/{id}` 软删除会话。会话有活动任务时返回 409。

通过 `atoms.gateway.tenant-tokens.<tenant>` 配置各租户的独立 Bearer 令牌。至少配置一个令牌后，受保护的 `/api/v1/` 接口要求有效的 `Authorization: Bearer ...`。每个租户令牌对应一个 Gateway 身份。未配置令牌时沿用访客会话模式。

配置令牌可通过 `GET/POST /api/v1/auth/api-keys` 列出和创建本租户 API Key，通过 `DELETE /api/v1/auth/api-keys/{id}` 撤销。生成的密钥仅在创建时返回明文，数据库保存哈希。API Key 可访问 Gateway 和 OpenAI 兼容入口，并可通过 `POST /api/v1/auth/refresh-key` 原子轮换自身。使用 `GET /api/v1/auth/me` 查看租户和凭据类型。配置令牌继续在后端 `application.yml` 管理。

为 `atoms.gateway.rate-limit.requests-per-minute` 或 `requests-per-hour` 设置正数后，已认证的 Gateway 请求按租户限流。配置令牌和签发的 API Key 共用额度，`/api/v1` 与 `/v1` 共用计数。使用同一数据库的后端实例会在同一事务更新固定时间窗口，超限返回 HTTP 429 和 `Retry-After`。默认 0 表示不限；分钟和小时窗口独立配置。

后端 `application.yml` 可用 `atoms.gateway.jwt.signing-key` 接受外部签发的 HS256 Bearer JWT。留空时禁用，启用时须提供至少 32 字节的密钥。JWT 必须包含 `sub`、`exp` 和已配置的 `tenant_id`，并匹配 `issuer`（默认 `shannon-platform`）。配置 `audience` 后也会校验 `aud`。JWT 与其他租户凭据共用请求额度，租户管理仍要求配置令牌。

可在 `application.yml` 设置 `atoms.gateway.google.client-id`，并在 `atoms.gateway.google.members` 中将 Google 账号的 `sub` 映射到已配置的租户。Vue 工作台顶部的「登录 / 注册」可使用 Gateway 凭据。配置 Google 登录后，同一入口会显示 Google 登录按钮，已开通账号首次登录时自动注册。API 客户端也可向 `POST /api/v1/auth/register` 提交 `{"provider":"google","token":"<Google ID token>"}`。Atoms 验证签名、签发者、受众与到期时间，按已配置的成员映射签发 15 分钟的访问令牌。使用 `POST /api/v1/auth/refresh` 和 `refresh_token` 可原子轮换单次使用的刷新令牌。此登录与现有租户空间共用权限。

只有 Gmail 地址时，可在 `atoms.gateway.google.gmail-members` 里用 `"[account@gmail.com]": tenant-name` 配置租户。首次登录须由 Google 提供已验证的 Gmail 声明，服务记录该邮箱与 Google `sub` 的绑定。其他 `sub` 无法使用此邮箱登录。

`GET /api/v1/stream/ws?workflow_id=...` 提供只读 WebSocket 任务事件。消息包含 `workflow_id`、`type`、`timestamp` 和数字 `seq`。可用 `types` 按事件类型筛选，并通过 `last_event_id` 或 `Last-Event-ID` 从已收到的序号续读。握手校验租户权限，服务端定时发送 ping，任务结束后关闭流。

daemon 可使用 Bearer 凭据连接 `/v1/ws/messages`，通过 `approval_request` 提交审批，并接收 `approval_response`。租户配置令牌可通过 `GET /api/v1/daemon/approvals` 查看待审请求，并向 `POST /api/v1/daemon/approvals/{id}/decision` 提交 `allow` 或 `deny`。签发的 API Key 可提交请求，审批决定由配置令牌提交。待审请求十分钟后自动拒绝。同一身份重连后可用原请求 ID 重发完全相同的请求，获取已保存的决定。Vue 工作台侧栏的「审批」可使用租户配置令牌查看、批准或拒绝这些请求。令牌仅在打开的面板中保留。可通过 `GET /api/v1/daemon/status` 查询使用同一业务数据库的 Gateway 实例上、本身份下的在线 daemon 数。WebSocket 心跳续约，连接关闭时移除计数，实例异常退出后记录到期。定时任务支持分发到同一 Gateway 身份的 daemon。

在后端 `application.yml` 可设置 `atoms.approval.opa-url` 指向 OPA 的 `/v1/data/.../decision`，并设置策略环境 `atoms.approval.environment`。交互式项目运行、Gateway 任务及模型兼容入口在入队前评价策略。策略返回 `{"result":{"allow":true,"require_approval":true,"reason":"需要复核"}}` 时，任务停在待审批状态并保存原因；拒绝返回 HTTP 403，策略服务故障返回 HTTP 503。OpenAI 兼容入口对待审批任务返回 HTTP 409，附任务与审批 ID。定时任务每次触发都评价策略，可以在运行历史中查看审批原因并作出决定。拒绝和策略服务故障会记录为该次运行失败，后续触发继续按计划进行。Daemon 提交的工具审批沿用独立流程。

在 `application.yml` 启用 `atoms.approval.plan-review-enabled` 后，构建任务先生成实施计划并等待审核。Vue 支持提交反馈、查看计划历史和批准当前版本。每次修订生成新的审批 ID，旧版本无法放行。批准后使用对应计划继续执行；工具级审批如已配置，可在后续动作独立触发。

为 `atoms.approval.supervisor-review-token` 配置独立的审批人凭据后，包含 Supervisor 节点的构建模板在计划阶段进入独立审核。提交者可以提交反馈和修订计划。审批人通过侧栏「Supervisor 审核」输入凭据，可查看部署内待审计划并批准或拒绝指定版本。请单独向审批人分发此凭据。

可在 `application.yml` 设置 `atoms.approval.tool-opa-url` 评价 Java Agent 的动作，或在 `tools-requiring-approval` 中以逗号列出需要审批的工具，例如 `GENERATE`。工具通过规则检查后，如需人工审批，Agent 会在执行前保存动作并等待。Vue 显示工具名称和审批原因。模板节点及其角色子任务触发审批时，还会显示对应节点。批准后执行已保存的动作，并复用已完成的节点结果。拒绝 Agent 动作循环的工具调用会结束任务，拒绝模板节点的工具调用会交给工作流处理节点失败。工具原有的执行权限仍然生效。

### OpenAI 兼容 Gateway

将客户端的 API 基址设为 Atoms 服务的 `/v1`。通过 `GET /v1/models` 获取可用对话模型，通过 `GET /v1/models/{id}` 获取模型详情。启用 Gateway 租户令牌时，使用相同的 Bearer 凭据访问 `/v1`。

`POST /v1/chat/completions` 支持文本字符串、文本块数组和用户消息中的 UTF-8 文本附件，返回文本回复、工具调用及用量。附件通过 `file` 内容块的 `file_data` 传入，格式为 base64 文本、JSON 或 PDF data URL。PDF 需要可提取文本且最多 20 页。每个请求最多四个文件，合计最多 48000 个字符。研究模式把附件保存到该会话的基准版本，作为可引用资料。请求经过 Atoms 的任务调度和模型计量，每次调用创建独立会话。响应 ID 移除 `chatcmpl-` 前缀后可用于查询 `GET /api/v1/tasks/{id}`。省略 `model` 时使用 `shannon-chat`，由部署默认对话模型执行。模型目录中的 `shannon-complex` 使用 Supervisor 拆解问题、委派角色并汇总文本答案。规划、角色结论和汇总保存为可恢复节点。启用 Temporal 时，这些模型步骤在 Agent 队列上运行，并记录为原生子工作流。模型目录还提供 `shannon-quick-research`、`shannon-standard-research` 和 `shannon-deep-research`。这些别名使用现有 Research 工作流和部署默认对话模型。快速模式直接检索用户问题，标准模式先拆解查询，深度模式默认最多执行三轮覆盖检查。新会话使用部署配置的研究参考工具获取外部资料。设置 `stream: true` 可通过 SSE 接收 `shannon_events` 生命周期事件和完整回复。正文只发送最终结果。设置 `stream_options.include_usage: true` 可在结束前接收用量。普通对话可通过 `image_url` 传入 base64 PNG、JPEG、GIF 或 WebP，要求所选模型支持 OpenAI 兼容视觉输入。研究和 Supervisor 模式目前接收文档文本。普通对话还支持 `tools` 函数定义、`tool_choice`（auto、none、required）和工具结果回传，函数由客户端执行。JSON 和 SSE 都可返回 `tool_calls`。Research 和 Supervisor 会在生成工作流草稿后处理调用方提供的函数。函数由调用方执行，结果通过后续请求回传。n 支持 1～4，候选分别生成、保存和计量，JSON 与 SSE 以候选索引区分。


通过 `application.yml` 的 `atoms.gateway.agents` 配置 Gateway Agent，每项填写 `name`、`description`、`category` 和已注册的只读 `tool`。`GET /api/v1/agents` 列出 Agent，`GET /api/v1/agents/{id}` 返回工具输入约束，`POST /api/v1/agents/{id}` 使用 `input` 与可选的 `session_id` 提交任务。工具调用通过策略审批和任务调度执行，结果可从任务或会话读取。

通过 Gateway 凭据登录后，可在 Vue 工作台侧栏打开「记忆文件」。Gateway 的 `GET /api/v1/memory/files` 列出当前身份的记忆目录，支持通过 `path` 列出子目录。`GET /api/v1/memory/files/{path}` 返回文本或 Base64 内容。将要共享的文件放入 `atoms.memory.user-files.directory` 下的 `<scope>/memory/`。租户令牌与其 API Key 使用同一个 scope，格式为 `g-` 加租户名 UTF-8 字节计算的 Java name UUID 去掉前两个字符。JWT 在此租户 scope 内按账号区分，目录名为 `u-` 加 `<tenant-scope>:<JWT sub>` 的 SHA-256 十六进制。记忆目录由部署方维护，Gateway 只读，目录内的符号链接不会列出或读取。

启用 `atoms.memory.user-files.recall-enabled` 后，Gateway 普通任务和 OpenAI 兼容聊天会在提交时保存最多 8000 字符的记忆快照，来源是 `MEMORY.md` 与最近抽取的笔记。旧任务恢复使用提交时的快照。启用 `auto-write-enabled` 后，符合长度条件的对话、研究和构建结果使用当前模型与任务预算提取可复用事实。笔记随成功结果提交，可在「记忆文件」的 `extracted/` 中查看。模型抽取失败时仍可保存原任务结果。

启用 `private-recall-enabled` 后，JWT 调用的 Gateway 任务和会话按租户与 JWT 主体隔离，同一主体可跨会话召回自己的记忆。此前创建的租户共享会话继续使用租户令牌或 API Key 访问。共享限额仍按租户计算。使用租户凭据提交的任务继续读取租户共享记忆。以上开关默认关闭。

`POST /api/v1/tools/{name}/execute` 接受 `arguments` 和可选的 `session_id`，需要 Gateway 凭据。可调用工作区读取工具及已配置的只读 HTTP/MCP 工具。请求等待任务结果并返回工具文本。策略要求审批时返回 HTTP 409 和审批 ID。等待超过 60 秒时返回 HTTP 504 和任务 ID，可通过任务接口查询结果。

`GET /api/v1/blob/{key}` 需要 Gateway 凭据，并按任务归属授权。daemon 事件的过大截图字段，以及超过 1500 KiB 的 Gateway Agent 工具输出，会保存为独立 Blob。引用保留 Shannon 的 `redis_key` 字段和 `shannon:blob:` 键格式，数据保存在 Atoms 数据库中。下载返回原始文本，Blob 七天未读取时过期。

`GET /openapi.json` 提供当前 Gateway REST 路由、参数、请求体媒体类型和响应状态描述，WebSocket 协议参见端点说明。`GET /health` 检查 Gateway 进程，`GET /readiness` 检查数据库和已启用的 Temporal 命名空间。

`GET /v1/llm-models` 返回可用于兼容入口的部署模型 ID、供应商和优先级。可用 `tier` 筛选，快速路由配置标为 `small`，其他模型标为 `medium`。`GET /api/v1/tasks/{id}/review` 可读取任务的待审批状态与原因。

`POST /v1/completions` 接受 Shannon CLI 格式的 `messages`、`specific_model`、`model_tier`、`temperature`、`max_tokens` 和可选函数定义。回复可包含客户端自行执行的函数调用。SSE 使用 `content_delta`、`done` 和 `[DONE]` 帧，OpenAI 兼容、Anthropic 和 Gemini 的正文可随上游 SSE 增量发送，结束帧包含汇总正文、工具调用和用量。OpenAI 兼容、Anthropic 和 Gemini 供应商均支持 CLI 回传助手函数调用及其配对结果。Gemini 函数调用返回的 `thought_signature` 需随该调用原样回传。兼容入口保留 Anthropic 的带签名或加密推理块、Gemini thought parts，以及 OpenAI 兼容模型返回的 reasoning_content。客户端可将返回的 provider_content 原样回传给同一供应商。OpenAI 兼容入口支持 prompt_cache_key（CLI 使用 cache_key），按当前身份隔离。Anthropic 供应商可配置 prompt-cache，Gemini 可配置现有的 cached-content 资源，带系统提示或工具声明的请求沿用普通生成。支持推理摘要的供应商可配置 include-thinking。

### 项目语义记忆

默认使用字符相似度召回旧项目记录。配置独立的 Embedding 服务可启用语义召回：

```yaml
atoms:
  memory:
    semantic:
      enabled: true
      endpoint: https://<embedding-service>/v1/embeddings
      api-key: ${ATOMS_EMBEDDING_API_KEY}
      model: text-embedding-3-small
      minimum-similarity: 0.65
```

语义召回仅在同一项目内检索近期上下文之外的成功任务。首次检索分批索引需求文本，向量按端点及模型缓存。模型输入最多附带三条相关旧需求及其结果。Embedding 服务只接收截短后的需求，不接收源码、历史回答或其他项目的数据。服务超时或异常时使用原有字符召回。Embedding 请求使用独立供应商配置，当前未纳入生成模型用量账本。

### 版本文档索引

代码面板可搜索当前查看的已保存版本中的 Markdown 和普通文本。首次查询为该版本建立持久化索引，文本按 800 个 Unicode 字符分块，相邻块重叠 120 个字符。结果最多五条，附文件路径、块编号和从零开始的字符区间。项目和 Gateway 会话的文档检索都校验归属。

ReAct 可通过 `SEARCH_DOCS` 读取本轮基准版本的相关片段。模板可把它加入只读工具白名单。默认按文本相关性排序。启用项目 Embedding 服务后，还可单独配置 `atoms.memory.documents.semantic-enabled: true`，让文档搜索结合向量相似度排序。该开关允许把查询词和同一版本的文档片段发送到已配置的 Embedding 服务。每次最多为 15 个新片段生成向量，向量按版本和模型持久化，服务不可用时继续使用文本匹配。

### 长对话上下文压缩

设置 `atoms.memory.compression.enabled: true` 后，长项目对话可以在任务开始时压缩较早的成功记录。超过 20 轮成功记录，或历史达到约 24,000 字符且有足够轮次时，系统把最多 20 条较早记录交给当前文本模型摘要。摘要输出上限为 500 token，并计入任务模型预算。

压缩成功后，模型仍收到最初需求、近期原文、语义召回和当前源码。摘要持久化到当前任务，暂停恢复与 Worker 重试会复用结果。摘要调用不可用时沿用原有历史裁剪。图像任务不使用该步骤。

### 资料研究

Vue 输入模式可选择「资料研究」。Gateway 可提交 `mode: "research"` 或 `context.force_research: true`。任务使用问答模型配置，可把问题拆成最多四个检索分支，并行查询本轮基准版本中的文档。报告按实际检索片段编号引用，来源清单由程序生成。计划、证据和报告分阶段保存，恢复时复用已完成节点。研究结果保存为消息。

可设置 `atoms.research.reference-tool` 为已注册的网络只读工具名。该工具须接受名为 `query` 的字符串参数。研究任务使用现有工具权限和调用预算。结构化工具可返回 `results` 数组，每条含 `url`、`title` 和 `snippet`。报告会保留有效 HTTPS 来源并按 URL 去重。纯文本连接器仍以工具名标记来源。当前未对来源可信度打分。

Gateway 可用 `research_strategy: deep` 或 `academic` 默认启用最多三轮补充检索。可通过 `context.iterative_max_iterations` 设置轮数上限（1～5）。仅设置 `atoms.research.refinement.enabled: true` 时默认一轮。每轮最多追加两个新查询，没有新查询时提前汇总。轮数上限、覆盖检查和证据都保留检查点。

在后端 `application.yml` 的 `atoms.research.models` 下可分别设置 `planning`、`refinement` 和 `report`。各值须属于 `atoms.model.chat-models`，留空使用任务所选模型。工作流在首次规划前保存阶段路由，暂停恢复和远程 Worker 读取同一份配置快照。各阶段沿用任务预算，并按实际调用模型记录用量及配置费率。

### Agent 和 Supervisor 记忆

设置 `atoms.memory.roles.enabled: true` 后，模板的角色节点会记录任务与结论，Supervisor 和 Swarm 会记录已验证的任务拆解。后续任务从同项目的成功运行中按相似度取最多两条经验。角色记忆按角色隔离，未命名角色按节点和子任务隔离。拆解记忆绑定模板版本与节点。经验仅作为新一轮分析的参考，暂停或重试期间产生的中间结果要等整项任务成功后才可召回。

### Swarm 运行中人工输入

带 `swarm` 节点的模板任务会显示「补充给 Swarm Lead」。Gateway 也可通过 `POST /api/v1/swarm/{id}/message` 提交 `{"message":"补充目标"}`。消息保存后，Lead 在拆解、团队复核和最终汇总阶段读取各自的输入快照。首个 Swarm 最终汇总开始后停止收消息，已接收的输入会在重试时复用。客户端可通过任务事件看到 `SWARM_HUMAN_INPUT`，事件正文保留在业务库。

设置 `atoms.swarm.peer-messaging.enabled: true` 可启用多轮 Agent 点对点交流。各角色先生成初稿，每轮可向同批队友发送最多三条定向消息。收件角色修订结论供下一轮使用。没有新消息时提前进入 Lead 复核。使用 `atoms.swarm.peer-messaging.max-rounds` 配置轮数上限，默认 2，支持 1～4。任务首次进入邮箱时保存轮数上限，暂停恢复时沿用该上限。消息和修订均作为 DAG 节点输出保存，工作台会显示发件人、收件人和消息正文。额外模型调用使用现有任务和节点预算。Vue 的 Swarm 任务板及 Gateway 的 `GET /api/v1/swarm/{id}/board` 从持久化计划与节点状态展示角色、任务依赖、发件、收件处理和 Lead 汇总进度。

设置 `atoms.swarm.role-loop.max-steps` 为 1～8 可启用独立角色动作循环，默认 0。角色可继续分析或提交结论，具有 `SEARCH_DOCS` 角色权限时也可检索基准文档。每个角色保存自己的动作、检索观察和步数上限。暂停恢复复用已完成的记录。启用 Temporal 时，各循环在所属 Agent 子工作流的 Activity 内执行；同批角色可在循环中用 `send_message` 发送定向消息，收件角色在下一步读取新消息。每步保存收件快照，暂停恢复会沿用原快照。角色提交最终结论后，Lead 会在首批任务全部完成时检查未读消息。有未读消息的角色最多重新激活一次，复核原结论后提交修订；Lead 使用修订结果。原有的轮次式交流可独立配置。

设置 `atoms.swarm.replanning.enabled: true` 后，Lead 可根据首轮复核及最新人工输入，每轮可追加或重派最多两个任务。通过 `atoms.swarm.replanning.max-rounds` 设置 1～4 轮，默认 1。新一轮可以依赖此前任一任务的最新结论，重派后的结果替代旧结论。没有新任务时提前结束。轮数上限随任务保存。重规划决策与补充执行均保存检查点，任务板标明新增角色与依赖。Lead 也可沿用原任务 ID，将其重派给不同角色。交接会包含原结论，Vue 任务板会标记重派任务。

### Temporal 开发环境

```bash
docker compose -p atoms-temporal -f deploy/compose.temporal.yml up -d --wait
cd backend
mvn spring-boot:run
```

Temporal 监听本机 7233，Web UI 在本机 8233。Docker 命名卷保存开发服务器历史。后端可通过 `atoms.temporal.target`、`atoms.temporal.namespace` 和 `atoms.temporal.task-queue` 指向指定服务和队列。切换到 Temporal 前先停止连接同一个业务库的旧调度实例；使用同一个 Temporal 队列的 Atoms 实例需共享同一个业务库。

Temporal 是默认执行器。服务端或命名空间不可用时，Atoms 不会自动改用本地执行器。仅在隔离开发和测试时可显式设置 `atoms.temporal.enabled: false`。生产 Compose 和 Railway 部署需将 `ATOMS_TEMPORAL_TARGET` 设置为应用可访问的 Temporal gRPC 地址，可用 `ATOMS_TEMPORAL_NAMESPACE` 和 `ATOMS_TEMPORAL_TASK_QUEUE` 指定命名空间和队列。部署使用独立的 Temporal 服务，本地 Compose 使用有持久卷的开发服务器。

每个由 Atoms 自己执行的任务 ID 对应一个 Temporal Workflow，执行逻辑在 Activity 中运行。Activity 重试时用新的执行令牌接管，旧令牌无法提交版本或检查点。暂停与审批状态仍以业务库为准。已保存的检查点会复用，中断时未确认的外部调用可能重发。

Gateway 的 `GET /api/v1/tasks/{id}/timeline` 读取 Temporal 历史，支持 `mode=summary|full` 与可选 `run_id`，仅返回事件类型、时间和序号。当前历史接口为只读。Research 的规划、证据检索、覆盖检查、补充检索和报告生成使用 `<task-queue>-research-evidence` 队列。主 Agent 的模型动作决策、普通构建 DAG 的规划与分析、模板顶层 simple 节点、模板认知策略的模型步骤，以及模板 DAG、Supervisor 和 Swarm 中的角色子任务使用 `<task-queue>-agents` 队列。新任务在主 Workflow 中启动原生子 Workflow，主历史记录子任务启动和结果，各子任务保留自己的 Workflow 和 Activity 历史。主任务用 Temporal 版本标记保留旧历史的独立 Workflow 执行路径，并使用业务库的节点检查点恢复。部署时应更新同一任务队列上的所有 Worker，再提交新任务。启用 Temporal 的应用默认启动三个队列的 Worker。可通过 `atoms.temporal.research-evidence-worker.enabled: false` 或 `atoms.temporal.agent-worker.enabled: false` 关闭某个实例上的对应 Worker，每个使用中的队列至少需要一个启用的实例。新构建任务由主 Workflow 推进 Agent 动作循环。每个 Activity 执行一次动作并保存检查点，Workflow 决定继续、等待控制操作或结束。暂停、恢复或取消在业务库提交后发送 Temporal Signal，唤醒正在等待的 Workflow，信号未送达时由定时复查兜底。工具执行保留在 Activity 中。模板顶层 DAG 也按就绪批次推进，每个 Activity 最多运行两个依赖已满足的顶层节点。Compose 中的 SQLite 开发服务器用于本地验证。

### 定时任务

侧栏的「定时任务」支持创建、编辑、暂停、恢复、删除和查看运行历史。Gateway 在 `/api/v1/schedules` 提供相同操作，配置租户令牌后通过 Bearer 凭据调用。

计划使用五段 Cron 与 IANA 时区，例如 `0 9 * * *` 和 `Asia/Shanghai`。每次触发会创建一个独立 Gateway 会话，并把普通问答任务与运行记录写入同一个数据库事务。默认调度在重启后合并处理离线期间错过的触发。启用 Temporal 后，计划定义和状态异步同步到原生 Schedule，定时触发由 Temporal 负责，接收任务仍会在业务库去重。Temporal 服务保持在线时，即使 Atoms Worker 离线也会保留已触发的工作流。当前支持至少一小时的调度间隔，工作台和 API 支持 `timeout_seconds`，从任务入队开始计时，到期记为 `TIMEOUT`。设为 0 表示取消时限，编辑时省略会保留旧值。`max_budget_per_run_usd` 设置单次美元预算，设为 0 表示不限。每次触发时固定预算，模型发出请求前按配置费率和估算 token 预留金额，收到用量后按实际输入与输出 token 结算。预算不足或所选模型缺少费率时，请求会被拦截。未收到用量时保留预留金额。

Vue 的定时任务面板可在工作台登录 Gateway 后选择 Daemon 执行位置。登录状态保留在当前浏览器页面的内存中。使用 API 创建计划时，可传入 `"execution_target":"daemon"` 和可选的 `"agent_name"`。连接 `/v1/ws/messages` 时使用同一凭据。任务获批后，服务发送 `message`，daemon 发送 `claim` 并在收到 `claim_ack.granted=true` 后执行，最后用同一连接发送 `reply`。回复进入会话与运行历史。领取连接发送的 `progress` 和受支持的 `event` 会进入任务历史及实时事件流，也可在 Vue 定时任务的运行历史中查看。领取有效期为 60 秒，进度心跳续期，首个进度心跳入库，过程事件按 daemon 序号去重，每次运行最多保存 1000 条 daemon 事件。没有 daemon 领取或运行超过时限时，记为 `TIMEOUT`。默认 600 秒，配置更短时限时使用更短值。服务检测到领取连接断开后，会释放领取并把该次运行记为失败。已经领取的任务不会自动重发，以免重复执行已经开始的操作。服务进程异常退出时，仍由任务时限处理未完成的领取。daemon 的模型调用与费用由其执行端负责。

### 运行指标

可用 `atoms.model.rates[模型ID].input` 和 `atoms.model.rates[模型ID].output` 配置输入与输出每百万 token 的美元费率。结算使用实际应答模型的费率，并将金额写入数据库。`atoms_model_estimated_cost_usd_total` 汇总配置费率后产生的估算费用，费率调整只影响后续结算。

设置 `atoms.metrics.enabled: true` 和 `atoms.metrics.token` 后，Prometheus 可以使用 Bearer 凭据抓取 `/metrics`。指标包含提交任务总数、各状态当前任务数、模型调用次数、供应商报告的输入和输出 token，以及已记录的 Agent 步骤耗时。指标从数据库汇总，应用重启后仍保留。模型调用计数包含已预留但可能失败的调用，token 指标使用供应商返回的实际用量。

Supervisor 角色子任务支持 Temporal 子工作流及白名单内的只读工具。单独的审批人角色尚未接入。

## 当前边界

构建模式按需求生成静态页面或后端工程源码，版本以“文件相对路径 → 完整内容”保存，最多80个文件、总计1MB。静态页面的 index.html、style.css、script.js 支持预览，脚本按普通 script 执行；预览会移除内联事件处理器与外部资源引用。生成应用数据保存在内存中，重载预览会清空；工作台项目与源码另行持久化。静态校验不等于功能测试。

匿名访问 Cookie 是访问凭证，清除后无法找回该浏览器的项目；尚无注册登录与跨设备同步。限流状态保存在会话内，只适合本地单实例 Demo。默认的数据库调度按单实例设计。启用 Temporal 后，使用同一任务队列的 Worker 可共享业务数据库。

iframe 不是资源隔离容器，恶意死循环仍可能影响浏览器；“停止”只是卸载 iframe，并非可靠的 CPU 限制。公开运行任意生成代码前需增加独立预览域和执行资源隔离。

字体通过 Google Fonts 加载，网络不可达时自动使用系统字体。没有使用官网私有资源或伪造上线、支付等能力。

## 会话工作空间

会话的「工作空间」可查看目录、预览和下载文本文件，并下载已生成的图片。自主执行（ReAct）中的 `WRITE_WORKSPACE_FILE` 可把报告、笔记等文本保存到 `artifacts/`，后续同会话任务可通过 `LIST_FILES` 和 `READ_FILE` 发现并读取。写入随会话持久化，任务暂停或终止后仍可查看。已有源码版本也可在文件列表中浏览，从「版本编辑」继续预览、手动修改及保存。

当前文件通过 MySQL 保存。隔离验收容器内的临时文件独立于会话工作空间。

## 后端工程源码导出

在“构建应用”模式明确指定语言与框架，例如“生成Java Spring Boot后端工程，包含pom.xml、接口和启动说明”。后端源码会按实际目录保存并显示在代码面板，ZIP包含相同文件，不再强制三个静态文件。后端代码仅保存和导出，工作台不执行或编译生成的后端程序，请在本地IDE运行验证。

查看历史版本时，导出对应查看版本；有未保存编辑时，先点保存再导出。旧版已经生成的HTML版本不会自动变成后端工程，需要重新生成。

## GitHub 与公网部署

GitHub仓库保存源码，GitHub Actions自动构建包含前端的Java应用。完整服务使用Docker运行Java、MySQL和Caddy网关；GitHub Pages不能运行本项目的Java后端和MySQL。

部署步骤见 [GitHub与公网部署](docs/GitHub与公网部署.md)。私有配置和数据库密码均排除Git与Docker构建上下文，不要手动强制添加这些文件。

### Railway

使用Railway时按 [Railway部署说明](docs/Railway部署.md) 创建应用与MySQL服务，并设置 `SPRING_PROFILES_ACTIVE=railway`。应用自动监听平台端口并通过 `/api/health` 检查数据库连接。密钥只放Railway私密变量，不提交GitHub。

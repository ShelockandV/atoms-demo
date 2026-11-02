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
- 需求 → 计划 → 生成 → 静态校验 → 最多一次格式或语法修复。
- 有界任务线程池，每个项目同时只允许一个任务；每会话每小时最多20次生成。
- SSE 推送状态，轮询兜底；断线不自动重复提交。
- 前端静态应用预览，以及Java等后端工程的多文件源码查看、编辑、保存与导出。
- ZIP按实际文件路径打包，支持导出当前查看的历史版本；未保存的编辑需先保存。
- 不可变版本、查看历史、恢复为新版本、ZIP 源码导出。
- 乐观版本检查、短事务提交，失败不会覆盖已有版本。
- iframe sandbox、受限 CSP、来源窗口及预览标识校验、运行错误展示。
- 服务重启后把中断任务标为失败；匿名访问 Cookie 保留时可再次读取已保存项目。

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

按需求不包含单元测试。

```bash
cd frontend
npm run build
cd ../backend
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
  ApiController.java            项目、任务、版本、SSE与导出
  Store.java                    JDBC持久化与事务
  GenerationService.java        状态机、校验与修复
  ModelClient.java              文本生成与多轮问答接口
  ModelCatalog.java             按模式管理模型白名单与默认项
  ImageClient.java               阿里云同步文生图与结果下载
  DemoTemplates.java            离线演示模板
  RequestGuard.java             同源写入检查与匿名访问Cookie
backend/src/main/resources/      数据库schema与环境配置
backend/compose.yml             可选的本地MySQL容器
```

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

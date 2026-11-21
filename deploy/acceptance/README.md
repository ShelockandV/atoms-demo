# 隔离验收

在仓库根目录构建所需镜像。构建镜像需要访问镜像仓库与依赖仓库，运行验收容器时禁用网络。

```powershell
docker build -f deploy/acceptance/Dockerfile.maven -t atoms-acceptance-maven:local .
docker build -f deploy/acceptance/Dockerfile.browser -t atoms-acceptance-browser:local .
```

在后端私有配置中填写已构建的镜像名，并让后端进程能够通过 Docker CLI 使用本机或受控 Docker 环境。两个镜像可独立启用，留空即跳过对应验收。

```yaml
atoms:
  agent:
    acceptance:
      maven-image: atoms-acceptance-maven:local
      browser-image: atoms-acceptance-browser:local
      spring-boot-http-path: /api/health
```

| 候选项目 | 检查 |
| --- | --- |
| 根目录含 `pom.xml` | 执行 Maven 离线 `test` 生命周期，编译源码并运行项目声明的测试。无测试项目只验证该生命周期成功。 |
| 声明 Spring Boot Maven 插件并配置 `spring-boot-http-path` | 离线执行 `package`，以非 root 启动可执行 JAR，只监听容器内 `127.0.0.1:18080`。配置的 GET 路径须在 20 秒内返回 2xx。 |
| 有根目录或唯一的子目录 `index.html` | Chromium 打开静态页面，检查页面加载、脚本初始化异常及缺失的本地页面资源。外部网络资源会被阻断。 |

Maven 镜像通过后端的 POM 预装依赖与插件。生成项目如果用了镜像里没有的版本或依赖，离线验收会失败，需要维护者重新构建包含所需依赖的可信镜像。验收时不会临时拉取镜像或安装依赖。

Docker 容器以非 root 用户运行，使用只读根文件系统、临时工作区、无网络和资源限制。候选文件通过标准输入传入，无需挂载宿主工作区或 Docker socket。每项检查限时 90 秒。候选源码、构建和浏览器脚本仍属于不可信输入，对外开放服务时应将执行器部署到专用的隔离运行环境。

验收通过才会把候选快照标记为已校验。编译、测试和页面错误进入现有修复反馈。Docker 缺失或镜像不可用会报告环境错误。

Spring Boot 探针可留空。启用时，应设置生成应用实际提供的健康或只读接口。Chromium 静态检查覆盖页面启动，交互流程与前后端联调仍需单独验收。

## Spring Boot 与浏览器联合验收

先构建上述 Maven 与 Chromium 镜像，再在仓库根目录构建联合镜像。

```sh
docker build -f deploy/acceptance/Dockerfile.joint -t atoms-acceptance-joint:local .
```

在后端 `application.yml` 配置验收场景。示例针对 Spring Boot 自己提供页面和 API 的项目。

```yaml
atoms:
  agent:
    acceptance:
      spring-boot-http-path: /api/health
      joint-image: atoms-acceptance-joint:local
      joint-scenario: >-
        {"page":"/","click":"加载任务","endpoint":"/api/tasks","method":"GET","responseText":"task-1","visibleText":"待办任务"}
```

`click` 指按钮的无障碍名称。浏览器点击它后，检查指定请求收到 2xx、响应包含 `responseText`，并确认点击前尚不可见的 `visibleText` 出现在页面。联合验收和 Spring Boot 在同一无外网容器内运行，页面与 API 使用同一个回环地址。场景由部署方配置，适用于匹配该交互的 Spring Boot 项目。分别部署的前端开发服务器仍走各自的验收路径。

构建联合镜像后，可在仓库根目录运行真实容器回归。测试通过后端验收入口传入一个临时 Spring Boot 项目，验证 Maven 离线测试、应用启动、浏览器请求和结果渲染，并检查响应内容不符时的错误反馈。

```sh
mvn -f backend/pom.xml -Dtest=JointAcceptanceContainerTest -Datoms.test.joint-container=true test
```

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
```

| 候选项目 | 检查 |
| --- | --- |
| 根目录含 `pom.xml` | 执行 Maven 离线 `test` 生命周期，编译源码并运行项目声明的测试。无测试项目只验证该生命周期成功。 |
| 有根目录或唯一的子目录 `index.html` | Chromium 打开静态页面，检查页面加载、脚本初始化异常及缺失的本地页面资源。外部网络资源会被阻断。 |

Maven 镜像通过后端的 POM 预装依赖与插件。生成项目如果用了镜像里没有的版本或依赖，离线验收会失败，需要维护者重新构建包含所需依赖的可信镜像。验收时不会临时拉取镜像或安装依赖。

Docker 容器以非 root 用户运行，使用只读根文件系统、临时工作区、无网络和资源限制。候选文件通过标准输入传入，无需挂载宿主工作区或 Docker socket。每项检查限时 90 秒。候选源码、构建和浏览器脚本仍属于不可信输入，对外开放服务时应将执行器部署到专用的隔离运行环境。

验收通过才会把候选快照标记为已校验。编译、测试和页面错误进入现有修复反馈。Docker 缺失或镜像不可用会报告环境错误。

Chromium 检查覆盖页面启动，并未覆盖按钮点击、端到端业务流程或生成后端的 HTTP 接口。

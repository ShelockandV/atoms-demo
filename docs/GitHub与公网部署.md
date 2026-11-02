# GitHub 与公网部署

本项目需要Java服务与MySQL，不能仅通过GitHub Pages提供完整功能。部署结构为：

```text
浏览器 → Caddy（80/443，同源）→ Spring Boot（前端静态资源 + /api）→ MySQL
```

代码仓库：https://github.com/ShelockandV/atoms-demo

## 代码与凭证

- `backend/src/main/resources/application.yml`：可以上传的公共默认配置。
- `backend/config/application-private.yml`：你本机的真实配置，自动加载，不上传。
- `backend/config/application-production.yml`：服务器真实配置，不上传。
- `backend/config/spring.datasource.password`、`mysql-root-password`：Compose数据库密码文件，不上传。

原来的本地密钥和MySQL密码已经迁移到私有配置，本地启动命令不变。所有模型配置仍集中在一个 `atoms.model` 下，不使用scripts目录或dotenv启动脚本。

## 1. 在服务器准备目录

服务器需要安装Docker Engine及Compose插件，并开放80端口；使用域名HTTPS时还需443端口。请使用具备Docker操作权限的用户执行。

```bash
git clone https://github.com/ShelockandV/atoms-demo.git
cd atoms-demo
mkdir -p backend/config
cp backend/config/application-production.example.yml backend/config/application-production.yml
openssl rand -base64 32 > backend/config/spring.datasource.password
openssl rand -base64 32 > backend/config/mysql-root-password
```

修改 `backend/config/application-production.yml` 中 `atoms.model.base-url` 和 `api-key`，填写同地域、同工作空间的真实模型配置。图像配置留空会复用文本密钥并推导原生图像接口。

数据库密码通过Compose secrets同时提供给应用与MySQL，无须重复填写。Compose仅创建新数据库，不会自动迁移你电脑上的历史项目与图片。

## 2. 设置配置文件权限并启动

应用容器以UID/GID 10001运行，因此只给此用户读取部署凭证的权限：

```bash
sudo chown 10001:10001 backend/config/application-production.yml backend/config/spring.datasource.password backend/config/mysql-root-password
sudo chmod 600 backend/config/application-production.yml backend/config/spring.datasource.password backend/config/mysql-root-password
docker compose -f deploy/compose.yml config --quiet
docker compose -f deploy/compose.yml up -d --build
docker compose -f deploy/compose.yml ps
```

启动后通过 `http://服务器公网IP` 访问。MySQL和应用端口仅在容器网络内暴露，公网入口统一走Caddy。

可以检查接口和日志：

```bash
curl http://127.0.0.1/api/config
docker compose -f deploy/compose.yml logs --tail=100 app
docker compose -f deploy/compose.yml logs --tail=100 gateway
```

访问后手动验证对话、生成、SSE进度、生图和源码导出。匿名项目通过Cookie区分；模型额度在供应商侧设置预算。

## 3. 域名与HTTPS

把域名A记录指向服务器公网IP，将 `deploy/Caddyfile` 第一行的 `:80` 改为真实域名，例如：

```caddyfile
atoms.example.com {
    encode gzip
    reverse_proxy app:8080 {
        flush_interval -1
    }
}
```

使用HTTPS后，把服务器配置的 `server.servlet.session.cookie.secure` 改为 `true`。Caddy会申请并续期证书，要求域名解析正确、80/443可从公网访问。

```bash
docker compose -f deploy/compose.yml restart app
docker compose -f deploy/compose.yml up -d --force-recreate gateway
```

## 4. 更新与备份

### 当前阿里云演示服务器

项目目录为 `/opt/atoms-demo`，公网入口为 `http://115.29.18.229`。在阿里云 Workbench 的 Shell 模式中执行维护命令：

```bash
cd /opt/atoms-demo
docker compose -f deploy/compose.yml ps
docker compose -f deploy/compose.yml logs --tail=100 app
curl --fail http://127.0.0.1/api/health
```

服务器首次使用 GitHub 源码压缩包部署，未安装 Git。更新时可以下载源码后覆盖公共文件，私有配置不会包含在压缩包中：

```bash
curl --fail --location https://codeload.github.com/ShelockandV/atoms-demo/tar.gz/refs/heads/main --output /tmp/atoms-demo.tar.gz
tar -xzf /tmp/atoms-demo.tar.gz -C /opt/atoms-demo --strip-components=1
cd /opt/atoms-demo
docker compose -f deploy/compose.yml up -d --build
```

安全组放行 TCP 80，数据库不映射到公网。容器设置了自动重启和数据库持久化卷，网站运行不依赖本机电脑在线。

### 使用 Git 更新的服务器

```bash
git pull --ff-only
docker compose -f deploy/compose.yml up -d --build
```

MySQL数据保存在命名卷中。停止服务使用 `docker compose -f deploy/compose.yml down`；不要加 `-v`，否则会删除数据库卷。更新前按自己的备份策略备份MySQL。

## GitHub Actions

`.github/workflows/build.yml` 会在推送main或PR时构建前端，并将其放入Spring Boot静态资源目录后打成JAR；构建产物名为 `atoms-application`。该工作流不包含服务器SSH凭证，也不会自行部署到未知服务器。CI构建成功不代表已经有公网运行地址。

如不使用Docker，可下载Actions中的JAR，准备外部MySQL与私有配置，再运行：

也可直接下载 [v0.1.0-demo 完整部署包](https://github.com/ShelockandV/atoms-demo/releases/tag/v0.1.0-demo)，其中已包含前端资源，不包含私有模型配置。

```bash
java -jar atoms-backend-0.1.0.jar --spring.config.additional-location=file:/你的配置目录/
```

配置目录下使用 `application.yml`，或使用 `application-production.yml` 并加 `--spring.profiles.active=production`；修改数据库地址为实际数据库地址，不使用Compose内部主机名mysql。

# Railway 部署

仓库：https://github.com/ShelockandV/atoms-demo

使用根目录Dockerfile构建一个应用服务，其中包含Vue前端与Java后端。MySQL创建为同项目中的独立服务。无需Caddy、SSH或自备服务器，不把本机私有配置上传到仓库。

## 1. 创建服务

1. 登录Railway已有账号，在工作空间创建项目。
2. 添加MySQL服务，并保留持久化Volume。本文假定服务名称为 `MySQL`。
3. 从GitHub仓库添加应用服务，选择 `ShelockandV/atoms-demo` 的 `main` 分支。
4. Root Directory使用仓库根目录，检测根目录 `Dockerfile`；不要设成frontend或backend。
5. 只运行一个应用实例。目前任务恢复逻辑按单实例设计。

## 2. 设置应用服务变量

在应用服务的Variables中添加：

```text
SPRING_PROFILES_ACTIVE=railway
MYSQLHOST=${{MySQL.MYSQLHOST}}
MYSQLPORT=${{MySQL.MYSQLPORT}}
MYSQLDATABASE=${{MySQL.MYSQLDATABASE}}
MYSQLUSER=${{MySQL.MYSQLUSER}}
MYSQLPASSWORD=${{MySQL.MYSQLPASSWORD}}
MODEL_BASE_URL=你的阿里云文本接口地址前缀
MODEL_API_KEY=你的阿里云API Key
MODEL_NAME=qwen-plus
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60
```

数据库变量采用服务引用，不手动复制明文密码。如果数据库服务不叫MySQL，请替换引用中的服务名。不要把MySQL的 `mysql://...` URL直接作为JDBC URL；应用会用上述变量组装JDBC地址。

模型密钥必须填写在Railway私密变量中，不写入GitHub。已有本地Key可以复用，但仍消耗其模型额度；当前部署流程不会在未确认前代为传输密钥。

可选变量：

| 名称 | 默认值 | 用途 |
|---|---|---|
| `MODEL_TIMEOUT_SECONDS` | 300 | 单次文本请求超时，最长1800秒 |
| `MODEL_MAX_OUTPUT_TOKENS` | 16000 | 文本输出长度上限 |
| `IMAGE_ENDPOINT` | 空 | 自动从同域名推导阿里云原生图像接口 |
| `IMAGE_API_KEY` | 空 | 复用文本API Key |
| `IMAGE_MODEL` | qwen-image-2.0 | 默认图像模型 |

Railway的 `PORT` 由平台注入，应用监听 `0.0.0.0`。前端和API同源，浏览器不会访问127.0.0.1地址。

## 3. 部署与公网域名

- Healthcheck Path：`/api/health`。
- Healthcheck Timeout：建议120秒。
- 部署成功后在Networking中Generate Domain，目标端口使用Railway分配的PORT（控制台要求显式端口时，与实际启动日志中的端口一致）。
- 使用Railway提供的HTTPS域名访问。railway profile默认启用Secure Cookie。

没有使用已弃用的 `railway.json` 配置格式；本项目使用Dockerfile、Spring profile和Railway控制台服务设置。

## 4. 验收

1. `/api/health` 返回 `{"status":"UP"}`。
2. 首页与 `/api/config` 均可访问。
3. 新建项目并对话，再刷新确认记录保留。
4. 生成一个小网页，检查预览、版本及导出。
5. 生图并刷新确认图片保存在MySQL。

模型调用会产生供应商费用。Railway试用额度不是永久免费的Java+MySQL托管资源，请在控制台核对账号实际额度和使用限制；不自动升级套餐或接受付款。

本地已有MySQL数据不会自动迁移到新数据库，需要迁移时另行导出导入。当前仍使用匿名浏览器Cookie隔离项目，不同设备不能自动共享原有项目。

# Ticket Center 部署说明

## 1. 环境准备

线上服务器只需要 Docker、三份 Compose、`deploy/` 目录和 `.env`，不需要 Java 源码、前端源码或 Dockerfile。首次部署前准备 Docker Compose v2，并确保服务器可以访问 GHCR。

复制环境变量模板：

```bash
cp .env.example .env
```

至少填写以下变量：

```env
DB_PASSWORD=
TICKET_REDIS_PASSWORD=
TICKET_RABBITMQ_USERNAME=
TICKET_RABBITMQ_PASSWORD=
TICKET_INTERNAL_TOKEN=
```

`TICKET_INTERNAL_TOKEN` 是两个业务服务调用内部接口时共用的随机凭据，不要提交到仓库。镜像默认使用 `ghcr.io/git-creat7/ticket-center` 的 `latest` 标签，也可以在 `.env` 固定版本：

```env
TICKET_IMAGE_REPOSITORY=ghcr.io/git-creat7/ticket-center
TICKET_IMAGE_TAG=<Git SHA 或版本号>
```

## 2. 拉取并启动

公开镜像可以跳过登录。私有 GHCR 仓库先登录，密码使用具备 `read:packages` 权限的 GitHub Personal access token：

```bash
docker login ghcr.io
```

拉取 CI 已发布的镜像并启动全部服务：

```bash
docker compose \
  -f docker-compose.middleware.yml \
  -f docker-compose.init.yml \
  -f docker-compose.app.yml \
  --profile full pull

docker compose \
  -f docker-compose.middleware.yml \
  -f docker-compose.init.yml \
  -f docker-compose.app.yml \
  --profile full up -d
```

查看状态：

```bash
docker compose \
  -f docker-compose.middleware.yml \
  -f docker-compose.init.yml \
  -f docker-compose.app.yml \
  --profile full ps
```

三份 Compose 的职责：

| 文件 | 内容 |
| :--- | :--- |
| `docker-compose.middleware.yml` | MySQL、Redis、RabbitMQ、Nacos |
| `docker-compose.init.yml` | MySQL 业务账号和 Nacos 配置的一次性初始化容器 |
| `docker-compose.app.yml` | `ticket-center-api`、`order-service`、Gateway 和前端镜像 |

`mysql-init` 和 `nacos-init` 成功后显示 `Exited (0)` 是正常状态。再次执行只会同步账号权限或检查已有配置，不会删除数据库和 Nacos 配置。

## 3. CI 镜像发布

`.github/workflows/ci.yml` 在 PR 和 `main` 分支 push 时执行后端、前端测试；只有 `main` 测试通过后才发布镜像。发布的镜像为：

```text
ghcr.io/<owner>/<repo>/ticket-center-api:<tag>
ghcr.io/<owner>/<repo>/order-service:<tag>
ghcr.io/<owner>/<repo>/ticket-gateway:<tag>
ghcr.io/<owner>/<repo>/ticket-web:<tag>
```

后端镜像共用仓库根目录的 `Dockerfile`，通过 `MODULE` 构建参数选择模块；前端使用 `ticket-center-web/Dockerfile`。线上不执行 `docker build`，更新版本时修改 `.env` 的 `TICKET_IMAGE_TAG` 后重新 `pull` 和 `up -d`。

## 4. 数据库与初始化

MySQL 第一次创建数据卷时，会由官方镜像执行：

- `deploy/mysql/01-ticket.sql`：`ticket_center` 库
- `deploy/mysql/02-order.sql`：`ticket_order` 库

`mysql-init` 使用 `DB_USERNAME`、`ORDER_DB_USERNAME` 和 `DB_PASSWORD` 创建业务账号，并分别授权两个数据库。业务服务不使用 root 账号。

MySQL 官方镜像只会在空数据目录执行初始化 SQL。已有数据卷不会因为重启自动更新表结构；需要变更时先备份，再按项目约定重新初始化或执行经过验证的 SQL。不要直接把旧单体数据卷当作新微服务数据使用。

## 5. Nacos 配置

`nacos-init` 等待 Nacos healthy 后，将以下配置导入 `public` 命名空间和 `TICKET_CENTER` 分组：

| Data ID | 文件 |
| :--- | :--- |
| `ticket-center-api.yaml` | `deploy/nacos/ticket-center-api.yaml` |
| `order-service.yaml` | `deploy/nacos/order-service.yaml` |
| `ticket-gateway.yaml` | `deploy/nacos/ticket-gateway.yaml` |

脚本只在配置不存在时导入，不覆盖控制台中的修改。Nacos 配置保存在 `nacos-data` 数据卷中。当前配置为单节点、关闭鉴权，适合本机开发和演示，不代表生产高可用方案。

## 6. 本机调试

本机调试需要 JDK 17、Maven 和 Node.js 24。先启动中间件并完成初始化，再在仓库根目录安装 Maven 模块：

```bash
mvn -DskipTests install
```

分别在三个终端启动 Java 服务：

| 模块 | 端口 |
| :--- | :---: |
| `ticket-center-api` | 8082 |
| `order-service` | 8083 |
| `ticket-gateway` | 8080 |

```bash
# 终端 1
cd ticket-center-api
mvn spring-boot:run

# 终端 2
cd order-service
mvn spring-boot:run

# 终端 3
cd ticket-gateway
mvn spring-boot:run
```

本机默认使用 `local` profile，Feign 和 Gateway 直连 8082、8083；可用 `TICKET_API_URL`、`ORDER_SERVICE_URL` 覆盖地址。前端另开终端：

```bash
cd ticket-center-web
npm ci
npm run dev
```

容器部署使用 `cloud` profile，通过 Nacos 服务发现。不要在相同端口同时启动本机和容器中的业务服务。

## 7. 常用地址

- 前端：`http://localhost:5173`
- Gateway：`http://localhost:8080`
- Nacos 控制台：`http://localhost:8848/nacos`
- 业务服务调试端口：`127.0.0.1:8082`、`127.0.0.1:8083`

可通过 `.env` 修改端口映射。Nacos gRPC 端口默认是 HTTP 端口加 1000，修改 `NACOS_HOST_PORT` 时同步修改 `NACOS_GRPC_HOST_PORT`。

## 8. 验证命令

不启动容器也可以先检查 Compose 合并配置：

```bash
docker compose \
  --env-file .env \
  -f docker-compose.middleware.yml \
  -f docker-compose.init.yml \
  -f docker-compose.app.yml \
  --profile full config --quiet
```

检查最终镜像名：

```bash
docker compose \
  --env-file .env \
  -f docker-compose.middleware.yml \
  -f docker-compose.init.yml \
  -f docker-compose.app.yml \
  --profile full config --images
```

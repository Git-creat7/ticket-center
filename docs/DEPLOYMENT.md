# Ticket Center 部署说明

## 1. 环境准备

单机部署只需要 Docker、三份 Compose、`deploy/` 目录和 `.env`，不需要 Java 源码、前端源码或 Dockerfile。首次部署前准备 Docker Compose v2，并确保服务器可以访问 GHCR。多服务器应用部署见第 9 节，应用节点不需要中间件和初始化文件。

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
| `docker-compose.init.yml` | MySQL 建库建表、业务账号和 Nacos 配置的初始化容器 |
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

建库建表与业务账号都由 `mysql-init` 容器完成，每次 `up` 都会执行，按库幂等：

- `deploy/mysql/01-ticket.sql`：`ticket_center` 库，库已存在则整文件跳过
- `deploy/mysql/02-order.sql`：`ticket_order` 库，库已存在则整文件跳过

之后使用 `DB_USERNAME`、`ORDER_DB_USERNAME` 和 `DB_PASSWORD` 创建业务账号，并分别授权两个数据库。业务服务不使用 root 账号。

因此从旧单体版本升级时，已有数据卷里缺少的 `ticket_order` 库会在下一次 `up` 时自动补建；`ticket_center` 库保留原数据。两份 SQL 含裸 `CREATE TABLE` 与种子数据，不能对已存在的库重复执行，已有库的表结构变更要先备份，再执行经过验证的迁移 SQL。

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

## 9. 多服务器部署

`docker-compose.app-prod.yml` 是独立的应用编排，不含 `depends_on`、固定容器名或源码构建，也不启动中间件。每台应用服务器运行一套前端、Gateway、`ticket-center-api` 和 `order-service`，连接同一组 MySQL、Redis、RabbitMQ 和 Nacos。入口负载均衡需单独配置，把请求分发到两台前端（5173）；直接调用 API 时分发到两台 Gateway（8080）。

以下以中间件服务器 `10.0.0.10`、应用节点 A `10.0.0.21` 和 B `10.0.0.22` 为例。中间件仍可单节点用于多实例演示，但不代表生产高可用。这套拓扑已在两台主机上联调通过（一台同时跑中间件和节点 A，另一台跑节点 B），验收结果见本节末尾。

### 中间件准备

先完成两个数据库、业务账号、RabbitMQ 用户和 Nacos 配置的初始化，再启动应用。可以在中间件服务器沿用 `docker-compose.middleware.yml` 与 `docker-compose.init.yml`；托管服务则按第 4、5 节准备数据库和配置。应用使用各自的业务数据库账号，不使用 root。

沿用仓库的中间件编排时，在该服务器 `.env` 设置 `NACOS_BIND_IP=10.0.0.10`，让 Nacos 的 HTTP 和 gRPC 端口绑定私网；不设置时仍为 `127.0.0.1`，其他服务器无法访问。绑定私网后，服务器自己的 `127.0.0.1:8848` 也不再可用，在服务器上检查注册表要用私网地址。只向应用节点开放 MySQL 3306、Redis 6379、RabbitMQ 5672、Nacos 8848/9848。Nacos gRPC 端口必须是 HTTP 端口加 1000。实际生产还需要开启 Nacos 鉴权，不能将默认未鉴权的控制台暴露到公网。

### 每台应用节点配置

应用节点只需 `docker-compose.app-prod.yml` 和根据 `.env.example` 填写的 `.env`。保留数据库、Redis、RabbitMQ、内部调用凭据及镜像设置，并取消模板末尾所需变量的注释。节点 A 的地址配置示例：

```env
DB_URL=jdbc:mysql://10.0.0.10:3306/ticket_center?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
ORDER_DB_URL=jdbc:mysql://10.0.0.10:3306/ticket_order?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
TICKET_REDIS_HOST=10.0.0.10
TICKET_REDIS_PORT=6379
TICKET_RABBITMQ_HOST=10.0.0.10
TICKET_RABBITMQ_PORT=5672
NACOS_SERVER_ADDR=10.0.0.10:8848
NACOS_DISCOVERY_IP=10.0.0.21
GATEWAY_URL=http://10.0.0.21:8080
```

节点 B 将 `NACOS_DISCOVERY_IP` 改为 `10.0.0.22`，`GATEWAY_URL` 改为 `http://10.0.0.22:8080`。这些是示例地址，部署时填写实际值。所有节点必须使用相同的 Redis、交易数据库、RabbitMQ vhost、`TICKET_INTERNAL_TOKEN` 和 Nacos namespace；开启 Nacos 鉴权后还要设置 `NACOS_USERNAME`、`NACOS_PASSWORD`。`ORDER_DB_PASSWORD` 未设置时沿用 `DB_PASSWORD`。

- `NACOS_DISCOVERY_IP` 通过 `SPRING_CLOUD_NACOS_DISCOVERY_IP` 映射到 `spring.cloud.nacos.discovery.ip`，必须是当前宿主机的私网 IPv4 地址，不能用容器 IP、`localhost` 或 `0.0.0.0`。不要把这个每节点不同的值写入 Nacos 的共享配置。
- 修改 `CORE_HOST_PORT`、`ORDER_HOST_PORT`、`BACKEND_HOST_PORT` 时，编排会同步设置 `spring.cloud.nacos.discovery.port`，注册宿主机端口而非容器端口；改端口只改 `.env`，不要直接改编排里的 `ports`，否则注册端口不会跟着变。健康检查仍访问容器内的 9082、9083、9080，不对外映射管理端口。业务端口绑定在所有网卡上，对外隔离依赖防火墙或安全组。
- 每台应用节点的容器必须能访问所有节点的注册 IP/端口，包括自己宿主机的私网地址。安全组、防火墙只对应用节点放行业务端口，前端/Gateway 入口只对负载均衡或必要的访问来源开放。节点是 Windows 时要为业务端口添加入站规则，端口改了规则也要跟着改，否则对端 Gateway 从 Nacos 拿到该实例后会有一半请求连接超时。
- `GATEWAY_URL` 是前端容器代理使用的 Gateway 地址，不是浏览器的 API 地址；浏览器仍请求同源的 `/api`。可使用本节点私网 Gateway 或独立的 Gateway 负载均衡地址。不要设置直连业务实例的 `TICKET_API_URL`、`ORDER_SERVICE_URL`，否则会绕过服务发现。
- 多台 API 必须启用同一个 OSS Bucket（`TICKET_OSS_ENABLED=true`，填写 AccessKey 等配置），或将 `/app/uploads` 改挂同一共享文件系统。默认 `backend-uploads` 只保存在单台机器，不能跨节点共享；已有本地上传文件需单独迁移。

### 启动与验收

在每台应用节点执行，不与单机三份 Compose 合并，也不需要 `--profile full`：

```bash
docker compose -p ticket-app --env-file .env -f docker-compose.app-prod.yml config --quiet
docker compose -p ticket-app --env-file .env -f docker-compose.app-prod.yml pull
docker compose -p ticket-app --env-file .env -f docker-compose.app-prod.yml up -d
docker compose -p ticket-app --env-file .env -f docker-compose.app-prod.yml ps
```

没有 `depends_on` 不代表自动等待外部中间件就绪。启动失败时先看 `docker compose -p ticket-app --env-file .env -f docker-compose.app-prod.yml logs --tail 100`；`restart: unless-stopped` 只在进程退出时重启，不会因 healthcheck 变成 unhealthy 自动重启。固定端口映射适合每台主机各一套，不要直接 `--scale`；同主机另起一套时需更换项目名和全部宿主机端口。中间件服务器同时作为应用节点时，默认的 8080、5173 常被其他项目占用，改 `BACKEND_HOST_PORT`、`FRONTEND_HOST_PORT` 等即可。

`deploy/verify-multinode.sh` 自动做三档验收：从 Nacos 读注册表确认每个服务各有两个 healthy 实例、两侧 Gateway 容器直连对端 API、分别经两台 Gateway 各打 20 次请求并用容器内 actuator 的 `http.server.requests` 计数统计每个节点实际处理了多少次。在节点 B 上运行，通过 SSH 读取节点 A 的容器计数，`SERVER_SSH`、`SERVER_IP`、`LOCAL_IP` 必填，`GW_B_PORT`、`NACOS_PORT` 不是默认值时另传：

```bash
SERVER_SSH=user@10.0.0.10 SERVER_IP=10.0.0.10 LOCAL_IP=10.0.0.22 bash deploy/verify-multinode.sh
```

实测结果：四组各 20 次请求全部 10/10 落到两个节点，零错误。`/event/hot` 走 `ticket-center-api`，未登录的 `/ticket-orders/me` 由 `order-service` 返回 401，两条路径都经 Gateway 的 `lb://` 轮询分发。验收后再手工经两台前端验证登录态共享、预约到支付/取消链路及图片访问；停止一台应用节点后实测 Nacos 立即摘除该实例（容器 stop 时主动注销），另一台 Gateway 立即和 45 秒后各 20 次请求全部 200。

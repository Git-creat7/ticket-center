# Ticket Center
![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring_Cloud-2023-blue?style=for-the-badge&logo=spring&logoColor=white)
![Vue.js](https://img.shields.io/badge/Vue.js-3.x-4FC08D?style=for-the-badge&logo=vuedotjs&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.x-3178C6?style=for-the-badge&logo=typescript&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.x-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7.x-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.x-FF6600?style=for-the-badge&logo=rabbitmq&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Container-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-CI/CD-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)
> 活动票务预约平台。个人全栈项目，后端围绕高并发抢票、缓存设计与消息驱动的最终一致性展开。
>
> **个人独立完成**：后端整体架构设计、核心抢票链路、并发控制、缓存优化、数据库设计、自动化测试、压测验证，以及 CI/CD 流水线与镜像交付方案设计。  
> **Agent 协助完成**：前端页面开发、部分部署脚本、配置模板以及文档的编写整理
## 1. 项目简介 + 核心成果

票务抢购的难点在于大量用户同时争抢有限库存：不能超卖，也不能用过重的锁把吞吐压垮。本项目将库存预扣、异步建单、限时支付和库存回补串成一条可恢复链路，并通过预约与候补处理售罄场景。

- **核心问题**：库存超卖与少卖、一人一票排重、消息重复或丢失、支付超时回补
- **量化结论**：历史基线中，100 并发提交 1,000 次请求争抢 50 张票，最终落库 50 笔，零超卖、零少卖；签到状态查询达到 2,860 ~ 3,005 req/s
- **项目成果**：完成微服务拆分、Redis 原子扣减、RabbitMQ 异步落库、失败重试、候补递补和 Testcontainers 集成验证

## 2. 核心架构图

```text
Vue 前端 :5173
      │ /api、/uploads
      ▼
Gateway :8080 ───── Nacos 注册与配置中心 :8848
      ├─ ticket-center-api :8082 ── ticket_center
      │    用户、活动、评价、评论、关注
      └─ order-service :8083 ───── ticket_order + RabbitMQ
           票档、库存、预约、候补、订单、签到、积分

抢票请求 → 预约落库 → Redis Lua 预扣 → RabbitMQ 建单 → 订单落库
                                      └→ 支付/取消/超时 → Redis 回补
```

两个业务服务通过 OpenFeign 调用内部接口，共用 Redis 登录态，但不跨库读取对方业务表。`ticket-common` 只放通用响应、会话校验、配置和跨服务 DTO，不共享业务 Entity 或 Mapper。

## 3. 核心设计：抢票链路与并发控制

第一次出现的三个概念：

- **预约号**：一次受理请求的唯一身份，用来查询结果，也用来校验后续回补，避免旧请求误释放新库存。
- **恢复任务**：与预约记录一起提交到 MySQL 的持久化任务；Redis、RabbitMQ 暂时不可用时，定时重试推进链路。
- **回补**：订单取消、支付超时或建单失败后，按预约号恢复 Redis 库存和一人一票资格。

**一句话结论：Redis Lua 原子预扣负责“不会超卖”，RabbitMQ + 持久化任务负责“最终能落库或回补”，数据库约束负责“重复请求也不会多卖”。**

```text
选择票档
  ├─ MySQL 创建预约记录和恢复任务，返回预约号（状态：处理中）
  ├─ 恢复任务调用 Lua：校验库存和一人一票 → 原子扣减
  ├─ 发布订单消息，消费者二次校验后写入订单
  └─ 支付成功，或取消/15 分钟超时 → 按预约号回补
```

三条一致性防线：

1. **Lua 原子扣减，解决超卖**：库存检查、排重和扣减在 Redis 单线程内一次完成，不在应用层串行加锁。
2. **消息幂等与持久化恢复，解决丢消息**：消费者按订单号幂等；重试耗尽后记录失败并创建回补任务，预约结果可查询。
3. **数据库唯一索引与预约行锁，解决并发重复落库**：`uk_user_ticket_active` 限制活跃订单唯一，预约行锁协调消费、取消和超时。

### 候补规则

售罄后可以加入候补，每人每票档最多一个有效候补。入队不占库存、不扣积分，释放名额优先按入队顺序递补；递补成功后获得限时支付资格，超时或取消则继续分配给下一位。候补记录、关联预约和恢复任务都保存在 MySQL，递补仍复用 RabbitMQ 异步建单。

预约状态主线为 `处理中 → 预扣成功 → 建单成功 → 已支付`；预扣失败、取消或超时进入失败/释放分支。候补状态主线为 `排队中 → 已递补 → 待支付`，递补失败会保留顺序等待恢复。

## 4. 关键技术优化点

统一按“问题 → 方案 → 收益”说明：

- **详情页读放大** → 活动基础信息缓存，前端单独读取订单服务的实时票档和库存 → 减少重复的跨服务查询，避免库存缓存过期导致误报售罄。
- **签到状态 Redis 往返多** → 整月 Bitmap 一次读回，位运算放在内存 → 往返从约 12 次降到 3 次，历史基线达到 2,860 ~ 3,005 req/s。
- **点赞、签到积分先读后写** → 用 Redis 命令返回值判断状态变化，MySQL 自增和行锁兜底 → 并发下计数与积分流水不重复。
- **列表 N+1 查询** → 批量加载分类、评价和订单关联数据，点赞状态使用 pipeline → 减少数据库和 Redis 请求次数。
- **深分页与同秒排序不稳定** → 使用 `(status, hot DESC, id DESC)` 复合索引，并统一追加 `id` 排序 → 减少 filesort，翻页结果稳定。
- **单体边界不清** → 活动/社区与交易分库，跨服务只走 Feign 内部接口 → 服务职责和数据归属可独立演进。

## 5. 质量与性能验证

集成测试使用 Testcontainers 启动真实的 MySQL、Redis 和 RabbitMQ，不依赖本机预装中间件。GitHub Actions 在 PR 和 `main` push 上运行后端测试与前端测试，`main` 测试通过后再构建镜像。

重点覆盖：

| 测试范围 | 验证内容 |
| :--- | :--- |
| 预约与候补流程 | 请求幂等、查询权限、预扣、候补优先、取消竞争、超时递补、故障恢复 |
| 订单一致性 | RabbitMQ 重复投递、死信回补、超时关单、数据库唯一约束 |
| 并发正确性 | 200 线程抢 10 张票恰好成功 10 笔；同一用户并发只成功 1 笔 |
| Redis 原子操作 | 验证码单次消费、签到积分、点赞计数与集合一致 |
| 微服务边界 | Gateway 路由、内部凭据过滤、Feign 故障返回和服务启动 |

以下是预约和微服务拆分前的历史压测基线，当前链路需要重新压测，不能直接作为现版本容量结论：

| 场景 | 并发 / 样本 | 吞吐量 | 平均响应 | p95 |
| :--- | :---: | :---: | :---: | :---: |
| 签到状态查询（Bitmap） | 200 / 10,000 | 2,860 ~ 3,005 req/s | 60 ~ 65 ms | 75 ~ 90 ms |
| 演出详情高频读（Cache） | 200 / 4,000 | 3,463 ~ 4,499 req/s | 22 ~ 35 ms | 34 ~ 64 ms |
| 秒杀抢票并发写（Lua + MQ） | 100 / 1,000 | 1,012 ~ 1,101 req/s | 41 ~ 54 ms | 87 ~ 122 ms |

强一致性结论：三轮 100 并发争抢 50 张票，Redis 结余为 0，MySQL 落库 50 笔，零超卖、零少卖、一人一票排重率 100%。完整方法见 [`benchmark/BENCHMARK_REPORT.md`](benchmark/BENCHMARK_REPORT.md)。

## 6. 技术栈选型

### 核心业务

- **Java 17 + Spring Boot 3.x**：提供长期支持的运行时和清晰的 Web、事务、定时任务基础。
- **MyBatis-Plus 3.x**：普通增删改查减少样板代码；行锁、幂等插入和联表查询保留在 Mapper.xml，关键 SQL 可控。
- **Vue 3 + TypeScript**：实现活动详情、预约状态、订单和候补等交互，类型检查降低前端接口变更风险。

### 基础设施

- **Redis 7.x**：承载登录态、热点缓存和库存预扣；Lua 保证检查与扣减的原子性，Bitmap 支持签到。
- **MySQL 8.x**：保存预约、候补、订单和积分等最终事实，依靠事务、行锁和唯一索引兜底。
- **RabbitMQ 3.x**：把建单、延时关单和失败补偿移出请求线程，吸收突发流量并支持重试。
- **Spring Cloud / Gateway / Nacos / OpenFeign**：提供统一入口、服务发现、配置管理和跨服务调用，保持业务边界清晰。

### 测试与交付

- **JUnit 5 + Testcontainers**：在真实中间件环境验证并发和消息行为，减少“本机能过、部署失败”。
- **Apache JMeter 5.6**：测量吞吐、P95/P99 和库存对账，不只看 HTTP 成功率。
- **Docker Compose + GitHub Actions**：本地编排中间件，CI 构建并推送镜像，线上只拉取固定版本。

## 7. 目录结构

```text
ticket-center/
├─ pom.xml                         Maven 多模块父工程
├─ ticket-common/                  通用响应、会话校验、配置、跨服务 DTO
├─ ticket-center-api/              用户、活动与社区服务，ticket_center 库
├─ order-service/                  交易与积分服务，ticket_order 库
│  ├─ src/main/resources/lua/      库存预扣、回补、候补脚本
│  ├─ src/main/resources/db/       交易库 SQL
│  └─ src/test/                    预约、候补、订单一致性测试
├─ ticket-gateway/                 统一入口与服务路由
├─ ticket-center-web/              Vue 3 用户端
├─ deploy/                         MySQL 账号和 Nacos 配置初始化
├─ benchmark/                      JMeter 计划、Linux 脚本与报告
├─ docs/DEPLOYMENT.md              详细部署说明
├─ Dockerfile                      CI 构建 Java 镜像，线上不需要
├─ docker-compose.middleware.yml   MySQL、Redis、RabbitMQ、Nacos
├─ docker-compose.init.yml         数据库账号和 Nacos 配置初始化
├─ docker-compose.app.yml          GHCR 应用镜像
├─ docker-compose.app-prod.yml     多服务器应用镜像，外接中间件
└─ PROGRESS.md                     优化决策与排查记录
```

## 8. 设计边界与后续规划

- 支付目前只做订单状态流转，验证码只写入 Redis，未接入真实支付和短信服务。
- 应用层支持多服务器多实例部署：每台节点运行 Gateway、`ticket-center-api` 和 `order-service`，向同一 Nacos 注册各自的宿主机私网 IP 和映射端口，Gateway/OpenFeign 按服务名调用。`docker-compose.app-prod.yml` 独立连接共享中间件，入口负载均衡需另行配置。已在两台主机上联调通过：每个服务各两个实例注册到 Nacos，经任一 Gateway 的请求按轮询精确落到两台机器（`deploy/verify-multinode.sh` 四组各 20 次均为 10/10）；停一台节点的容错验证尚未做。
- 应用多实例不等于中间件高可用：仓库自带的 MySQL、Redis、RabbitMQ、Nacos 仍是单节点，Nacos 默认关闭鉴权；实际生产需独立配置鉴权、访问控制及高可用部署。多台 API 实例上传文件需使用 OSS 或共享文件系统，不能依赖各自的本地数据卷。
- MySQL 与 Redis 没有分布式事务；预约通过持久化任务重试，恢复前可能短暂处于处理中。
- 死信补偿失败队列暂需人工对账；登录校验失败暂未做限流和锁定。
- 候补每个票档只处理一个进行中的递补，吞吐量需要单独压测。

后续可按优先级推进：生产环境 Redis Sentinel/Nacos 集群、可靠消息 Outbox、真实支付回调、补偿失败告警和候补批量递补。

## 9. 快速启动 / 部署说明

详细环境变量、初始化说明和本机调试方式见 [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)。单机部署只需要以下三份 Compose、`deploy/` 和 `.env`，不需要源码或 Dockerfile：

```bash
cp .env.example .env
docker login ghcr.io                 # 私有 GHCR 仓库需要，公开仓库可跳过
docker compose -f docker-compose.middleware.yml -f docker-compose.init.yml -f docker-compose.app.yml --profile full pull
docker compose -f docker-compose.middleware.yml -f docker-compose.init.yml -f docker-compose.app.yml --profile full up -d
docker compose -f docker-compose.middleware.yml -f docker-compose.init.yml -f docker-compose.app.yml --profile full ps
```

多服务器的应用节点只使用 `docker-compose.app-prod.yml` 和 `.env`，连接已初始化的共享中间件，见[多服务器部署](docs/DEPLOYMENT.md#9-多服务器部署)。不要与单机应用编排合并使用。

## 10. 接口说明 / 演示地址

- **本机演示地址**：前端 `http://localhost:5173`，Gateway `http://localhost:8080`，Nacos 控制台 `http://localhost:8848/nacos`
- **接口示例**：[`ticket-center-api/docs/api-demo.http`](ticket-center-api/docs/api-demo.http)
- **预约接口**：`POST /ticket-orders/reserve/{ticketId}` 返回预约号；`GET /ticket-reservations/{reservationId}` 查询处理结果；`GET /ticket-reservations/me` 查询当前用户记录。
- **订单操作**：使用预约记录中的 `orderId` 支付或取消，支付期限从订单创建时开始计算。

登录接口返回的 Token 直接放入 `authorization` 请求头，不加 `Bearer` 前缀。开发环境验证码写入 Redis，不发送短信。

# Ticket Center

一个用于学习 Redis、RabbitMQ 和高并发预约流程的活动票务项目。项目包含 Spring Boot 后端和 Vue 用户端，前后端放在同一个仓库中独立运行。

## 目录

```text
ticket-center/
├─ ticket-center-api/    Spring Boot 后端
├─ ticket-center-web/    Vue 用户端
├─ benchmark/            JMeter 性能压测计划、数据集与验收报告
├─ docker-compose.yml    MySQL、Redis、RabbitMQ（后端在 full profile 下）
└─ README.md
```

## 主要功能

- 手机验证码登录、密码登录和 Redis Token 会话
- 活动分类、热门活动、活动详情和附近活动查询
- 票档库存、限额预约和同一用户不能重复预约
- RabbitMQ 异步创建订单、失败重试和死信队列
- 模拟支付、取消订单、超时取消和库存回补
- 活动评价、点赞、关注和 Feed 动态
- Bitmap 签到和 HyperLogLog 访问统计
- 可选的阿里云 OSS 图片上传

## 预约流程

```text
选择票档
  -> Lua 检查库存和重复预约
  -> Redis 预扣库存
  -> RabbitMQ 发送订单消息
  -> 消费者创建 MySQL 订单
  -> 用户支付或取消
```

## 使用技术

- 后端：Java 17、Spring Boot、MyBatis-Plus
- 数据：MySQL、Redis、Lua、Redisson
- 消息队列：RabbitMQ
- 前端：Vue 3、Vite、TypeScript
- 本地环境：Docker Compose

## 启动项目

准备本地环境文件：

```powershell
Copy-Item .env.example .env
```

填写 `.env` 后启动基础服务：

```powershell
docker compose up -d
docker compose ps
```

启动后端：

```powershell
cd ticket-center-api
mvn spring-boot:run
```

后端地址：`http://localhost:8080`

也可以让后端一起跑在容器里，省掉本机的 JDK 和 Maven：

```powershell
docker compose --profile full up -d --build
```

新开一个终端启动前端：

```powershell
cd ticket-center-web
npm install
npm run dev
```

前端地址：`http://localhost:5173`

## 接口示例

常用请求放在 [`ticket-center-api/docs/api-demo.http`](ticket-center-api/docs/api-demo.http)。

开发环境中的验证码保存在 Redis：
```text
tc:login:code:{手机号}
```
登录接口返回的 Token 直接放入 `authorization` 请求头，不添加 `Bearer`。

## 性能压测与验收报告

项目配备了完整的 **Apache JMeter 5.6.3** 压测套件，支持在 CLI 模式下一键执行全场景并发压测并生成 HTML 可视化交互式看板。

详细的压测数据、数据强一致性核对与图表指引详见：  
**[JMeter 性能压测与数据一致性验收报告 (benchmark/BENCHMARK_REPORT.md)](benchmark/BENCHMARK_REPORT.md)**

### 核心性能指标摘要

同一配置下重复测量 4 轮，取均值并给出区间。这台机器的测量噪声最高达 30%，**单轮数字没有意义**，因此下表同时列出区间：

| 压测场景 | 目标接口 | 并发线程 | 样本总量 | 吞吐量均值 (QPS) | 吞吐量区间 | 平均响应时间 |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **签到状态查询 (Bitmap)** | `GET /user/sign/status` | 200 | 10,000 | **1,015.0 req/s** | 983.2 ~ 1,056.0 | 182 ~ 197 ms |
| **演出详情高频读 (Cache)** | `GET /event/1` | 200 | 4,000 | **3,597.0 req/s** | 3,210.3 ~ 4,246.3 | 24 ~ 36 ms |
| **秒杀抢票并发写 (Lua+MQ)** | `POST /ticket-orders/reserve/3` | 100 | 1,000 | **676.2 req/s** | 570.1 ~ 793.7 | 73 ~ 99 ms |

错误率除个别轮次出现 0.15% 的启动期连接失败外均为 0。

* **详情页调优效果**：演出详情从基线 565.6 QPS / 266.27 ms 提升到 3,597.0 QPS / 24~36 ms，约 **5.4 倍**。做法是把原先每请求 4 次网络往返（1 次 Redis GET + 1 次 PFCOUNT + 2 次 MySQL）收敛为聚合缓存 1 次读取，并把 UV 计算从读路径剥离。该提升远超 30% 的噪声区间，结论可靠。
* **一处已知的未定位回归**：签到接口从原始基线 2,542.6 QPS 稳定回落到约 1,015 QPS，三轮复测波动仅 7.2%，是稳定复现而非抖动。签到链路代码未改动，连接池已恢复基线配置，两者均已排除。尚未定位，因此报告中不对此下结论，也未把它计入调优成果。
* **秒杀抢票强一致性防资损核对**：100 并发争抢 50 张限量秒杀票（1,000 笔请求打入），**每一轮**均通过——Redis 预扣结余精确为 0，MySQL 最终订单精确落库 50 笔，零超卖、零少卖，一人一票排重率 100%。


## 项目说明

- 支付功能只是订单状态流转，没有接入真实支付平台。
- 验证码目前只写入 Redis，没有接入真实短信服务。
- MySQL 与 Redis 之间没有使用分布式事务，主要通过 Lua、状态更新和补偿降低数据不一致。
- 当前项目主要用于学习和展示核心业务流程。

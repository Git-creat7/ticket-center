# Ticket Center

> 活动票务预约平台。个人全栈项目，后端为重点，围绕高并发抢票、缓存设计与消息驱动的最终一致性展开。

## 项目定位

票务抢购的难点不在功能多，而在同一时刻大量用户争抢有限库存：既不能超卖造成资损，也不能因为加锁过重把吞吐压死。本项目用 Redis + Lua 把库存预扣做成原子操作，用 RabbitMQ 把订单落库转为异步，并为投递失败、消费失败、支付超时各配一条补偿路径。

- **解决的问题**：库存超卖与少卖、一人一票排重、异步链路的消息丢失与重复投递、订单超时未支付的库存回补
- **应用场景**：演出票、场馆预约、限量商品秒杀等「库存有限 + 瞬时高并发」的读写混合场景
- **项目价值**：完成从登录鉴权、缓存优化、原子扣减、异步落库到失败补偿的完整闭环，并以自动化测试与压测数据验证正确性和性能

<p align="center">
  <!-- 演示图片：把截图放到 docs/images/ 下，替换下面的 src 即可 -->
  <img src="docs/images/demo.png" alt="Ticket Center 演示" width="100%" />
</p>

<p align="center"><sub>演示：活动详情与票档预约流程。</sub></p>

## 技术栈

- **后端**：Java 17 / Spring Boot 3.5.14 / MyBatis-Plus 3.5.9
- **数据与缓存**：MySQL 8.4 / Redis 7（Lua 脚本、Bitmap、HyperLogLog、GEO）/ Redisson 分布式锁
- **消息队列**：RabbitMQ 3.13（死信队列、延时关单）
- **前端**：Vue 3.5 / Vite 8 / TypeScript 6 / Pinia / Axios
- **测试与交付**：JUnit 5 / Testcontainers / GitHub Actions / Docker Compose / Apache JMeter 5.6.3

## 核心设计：抢票链路

```text
选择票档
  ├─ Lua 原子脚本：查库存 → 查一人一票 → 扣减库存        （Redis，单次往返）
  ├─ 发送订单消息                                        （RabbitMQ）
  ├─ 消费者落库：DB 二次校验库存与排重 → 写订单
  └─ 用户支付 / 主动取消 / 15 分钟延时关单 → 库存回补
```

把库存预扣放进 Lua 是这条链路的关键：检查与扣减在 Redis 单线程内原子完成，并发下不存在「都读到有货、都去扣减」的窗口，因此不需要在应用层加锁串行化。落库交给消息队列异步执行，用户请求在预扣成功后即可返回。

三条防线保证异步链路不丢不重：

1. **消息重复投递** — 消费者按订单号幂等，已存在则直接跳过
2. **消费失败** — 重试耗尽后进死信队列，由补偿消费者回滚 Redis 预扣与一人一票资格
3. **数据库兜底** — `tb_ticket_order` 上有 `uk_user_ticket_active` 唯一索引（借虚拟生成列实现「活跃订单唯一、取消后可重购」），Redisson 锁失效时由它拦住重复落库

## 工程要点

**详情页聚合缓存**：原实现每请求 4 次网络往返（Redis GET + PFCOUNT + 2 次 MySQL），收敛为聚合视图缓存单次读取，并把 UV 统计从读路径剥离到写路径。调优当时实测 QPS 565.6 → 3,558.7，平均耗时 266ms → 16.9ms。

**签到接口的往返次数**：签到状态查询原本按位逐次访问 Redis（今日 `GETBIT`、连签 `BITFIELD`、当月 `BITCOUNT`、本周七天各一次，加拦截器两次，实测每请求 12 次往返）。改为整月位图一次读回、位运算在内存完成，往返降到 3 次，调优当时实测 QPS 1,206 → 3,374。定位手段是 `CONFIG RESETSTAT` + `INFO commandstats` 直接数命令数，而非猜测。

**并发计数的原子性**：签到发积分、评价点赞、评论计数都避免「先读后写」——点赞用 `ZREM`/`ZADD` 的返回值判定状态变化，计数交给 MySQL 自增，跨月位图与积分流水用 `FOR UPDATE` 加乐观条件兜底。

**列表查询的 N+1**：订单列表、评价列表、演出分类名三处改为批量加载，点赞状态用 pipeline 一次取回。

**排序索引与深分页**：热门榜单的排序键写成降序复合索引（`(status, hot DESC, id DESC)`），避免 filesort；分页统一带第二排序键 `id`，防止同秒数据在翻页时错乱。

## 质量保障

**自动化测试**：17 个测试类、69 项测试。集成测试通过 Testcontainers 拉起真实的 MySQL 8.4、Redis 7、RabbitMQ 3.13，不依赖本机预装中间件。

```bash
cd ticket-center-api
mvn test
```

重点覆盖并发正确性：

| 测试类 | 验证内容 |
| :--- | :--- |
| `TicketReserveConcurrencyTest` | 200 线程抢 10 张，成功数恰好 10、库存精确归零；50 线程同一用户只成功 1 笔 |
| `TicketOrderConsistencyTest` | 死信补偿的回滚与防误回滚、重复投递幂等、超时关单 |
| `SignAndLikeConcurrencyTest` | 20 线程并发签到只发一份积分；并发点赞计数与 Redis 集合保持一致 |
| `LoginCodeAtomicConsumptionTest` | 20 线程抢同一验证码，恰好 1 次成功 |

**持续集成**：`.github/workflows/ci.yml` 在 push 与 PR 上运行后端全量测试与前端构建。

## 性能压测

使用 Apache JMeter 5.6.3 在 CLI 模式下执行（2026-09-03，连续 3 轮）。第 1 轮是容器重启后的冷启动，只有稳态的 63% ~ 82%，故下表取第 2、3 轮的稳态区间。数字来自 JMeter dashboard 而非 summariser——后者的分母含线程创建开销并取整到秒，会低估 23%：

| 压测场景 | 目标接口 | 并发 | 样本 | 吞吐量（稳态区间） | 平均响应 | p95 |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| 签到状态查询（Bitmap） | `GET /user/sign/status` | 200 | 10,000 | **2,860 ~ 3,005 req/s** | 60 ~ 65 ms | 75 ~ 90 ms |
| 演出详情高频读（Cache） | `GET /event/1` | 200 | 4,000 | **3,463 ~ 4,499 req/s** | 22 ~ 35 ms | 34 ~ 64 ms |
| 秒杀抢票并发写（Lua+MQ） | `POST /ticket-orders/reserve/3` | 100 | 1,000 | **1,012 ~ 1,101 req/s** | 41 ~ 54 ms | 87 ~ 122 ms |

客户端与服务端同机，JMeter 自身也占 CPU，因此这是「该配置在这台机器上的相对表现」，不是服务端容量上限。详情页两轮相差 29.9%（配置完全相同），所以**小于 30% 的差异在本环境下无法与噪声区分**，报告中不对小幅差异下结论。

秒杀场景的「零错误」指 HTTP 层：1,000 笔请求全是 200，但真正下单成功的只有 50 笔，其余 950 笔是业务层的库存不足与限购拒绝（响应体 `code` 非 200，HTTP 状态仍是 200）。该吞吐量衡量的是链路处理能力，不是每秒成交 1,000 单。

**强一致性核对**：100 并发争抢 50 张限量票（打入 1,000 笔请求），三轮均通过——Redis 预扣结余精确为 0，MySQL 最终落库 50 笔，零超卖、零少卖，一人一票排重率 100%。

完整的测量方法、噪声基准与调优过程记录在 [`benchmark/BENCHMARK_REPORT.md`](benchmark/BENCHMARK_REPORT.md)。

压测需要本机安装 JMeter，并把可执行文件路径写入 `.env` 的 `JMETER_EXEC`（不填则取 PATH 中的 `jmeter`）：

```bash
# 1. 造压测用 Token（需后端与容器已启动）
python3 benchmark/generate_tokens.py

# 2. 执行全部场景并生成 HTML 报告
pwsh ./benchmark/run_all_benchmarks.ps1
```

脚本在 Windows PowerShell 5.1 与 Linux/macOS 的 PowerShell Core（`pwsh`）下均可运行。

## 快速启动

准备环境变量：

```bash
cp .env.example .env
```

`.env` 中的 `DB_PASSWORD`、`TICKET_REDIS_PASSWORD`、`TICKET_RABBITMQ_USERNAME`、`TICKET_RABBITMQ_PASSWORD` 为必填项，缺失时 Docker Compose 会直接报错退出而不是静默使用空密码。

启动基础服务（MySQL、Redis、RabbitMQ）：

```bash
docker compose up -d
docker compose ps
```

前后端也一起跑在容器里，省掉本机的 JDK、Maven 与 Node：

```bash
docker compose --profile full up -d --build
```

前端 `http://localhost:5173`，后端 `http://localhost:8080`。容器内前端把 `/api` 与 `/uploads` 反向代理到后端服务，两者通过 Docker 内部网络通信。

需要热更新时改用本机启动。后端：

```bash
cd ticket-center-api
mvn spring-boot:run
```

前端另开一个终端：

```bash
cd ticket-center-web
npm install
npm run dev
```

## 接口调试

常用请求整理在 [`ticket-center-api/docs/api-demo.http`](ticket-center-api/docs/api-demo.http)，可直接在 IDEA 或 VS Code 中发起。

开发环境的登录验证码不发短信，写入 Redis：

```text
tc:login:code:{手机号}
```

登录接口返回的 Token 直接放入 `authorization` 请求头，不加 `Bearer` 前缀。

## 目录结构

```text
ticket-center/
├─ ticket-center-api/        Spring Boot 后端
│  ├─ src/main/resources/lua/     Redis 原子脚本（预扣、回滚、验证码消费）
│  ├─ src/main/resources/db/      建表与种子数据
│  └─ src/test/                   单元测试与 Testcontainers 集成测试
├─ ticket-center-web/        Vue 3 用户端
├─ benchmark/               JMeter 压测计划、诊断脚本与验收报告
├─ docs/images/             README 演示图片
├─ docker-compose.yml       MySQL、Redis、RabbitMQ（前后端在 full profile 下）
└─ PROGRESS.md              优化决策与排查记录
```

## 已知边界

- 支付仅为订单状态流转，未接入真实支付平台；验证码只写入 Redis，未接入短信服务。
- MySQL 与 Redis 之间没有分布式事务。预扣成功到消息到达 broker 之间存在一个无保护窗口：进程此刻崩溃会导致该笔预扣既不落库也不回滚，表现为少卖。彻底解决需要本地消息表或事务消息，当前规模下选择用死信补偿 + 定时扫库把窗口压到最小，并在文档中显式标注。
- 死信补偿重试耗尽后消息会进入补偿失败队列，该队列目前没有消费者，需人工介入对账。
- 登录接口尚未对校验失败次数做限流，验证码与密码登录均缺少失败锁定。
- 压测脚本的 Linux 分支尚未在真实 Linux 环境实跑验证。

更详细的优化过程、踩坑记录与未修项清单见 [`PROGRESS.md`](PROGRESS.md)。

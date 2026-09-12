# ticket-center 开发进度与优化记录

> 记录项目状态、优化决策与踩过的坑，便于跨会话接续与面试复盘。

## 当前位置

**日期**：2026-09-12
**状态**：微服务拆分已交付。两台主机联调通过（Nacos 双实例注册、Gateway 跨主机轮询 10/10、停一台节点另一台继续服务），拆分后的性能退化已定位并修复（详情 1,376 → 2,511 ~ 3,584 QPS，秒杀 223 → 728 QPS），order-service 78 项测试全绿。剩余差距是拆分固有代价，不再追。
**仓库**：Maven 多模块父工程。`ticket-gateway/` 统一入口（Spring Cloud Gateway + Nacos 注册发现）、`ticket-center-api/` 用户/活动/社区服务（`ticket_center` 库）、`order-service/` 票档/库存/预约/候补/订单/签到/积分服务（`ticket_order` 库 + RabbitMQ）、`ticket-common/` 通用响应/会话校验/跨服务 DTO、前端 `ticket-center-web/`、压测 `benchmark/`。部署拆成 `docker-compose.middleware.yml` / `init.yml` / `app.yml` 三份，线上只拉镜像不构建。

## 已完成

- [x] 手机验证码登录、密码登录、Redis Token 会话（双拦截器刷新）
- [x] 演出分类、热门演出、演出详情、附近演出（GEO）
- [x] 票档与库存管理，Lua 原子预扣 + 一人一票
- [x] RabbitMQ 异步创建订单，消费失败重试与死信队列补偿
- [x] 模拟支付、主动取消、15 分钟延时关单、库存回补
- [x] 积分体系：签到获取、购票抵扣、取消退还，全流程流水记录
- [x] 演出评价、点赞、关注与 Feed 动态
- [x] Bitmap 签到、HyperLogLog UV 统计
- [x] 可选阿里云 OSS 图片上传
- [x] JMeter 压测套件（3 场景）+ 秒杀强一致性核对
- [x] 目录重构：后端迁入 `ticket-center-api/` 完成前后端分仓（commit `3d5d4be`）
- [x] 交付加固：压测脚本去硬编码、凭据外置、运行时产物纳入 gitignore
- [x] 一致性优化四项（见下方"优化记录"）
- [x] 微服务拆分：单体拆为 gateway + ticket-center-api + order-service，两个业务服务分库、经 OpenFeign 内部接口互调、共用 Redis 登录态；Nacos 承担注册发现与配置中心；Compose 拆成中间件/初始化/应用三份，CI 构建镜像推 GHCR（commit `55ef5347`）
- [x] 多服务器部署：`docker-compose.app-prod.yml` 独立连接共享中间件，按宿主机私网 IP 注册 Nacos；`deploy/verify-multinode.sh` 三档验收（注册表、容器级互通、Gateway 轮询分发）
- [x] 拆分后性能回归：秒杀 Lua 预扣前置、详情页票档拆出、受理后即时推送恢复任务（见优化记录 17）

## 待办

- [x] **跑通 `TicketOrderConsistencyTest`**：已全面修复 `TicketOrderDeadConsumer` 与 `TicketOrderProducer` 的 Lua 回滚传参，4 个核心一致性用例 `mvn test` 100% 绿灯 PASS
- [x] **配置 Actuator**：已在 `application.yaml` 暴露 `health,info,metrics` 端点
- [x] **签到接口性能回归已结案**：原因是按位逐次打 Redis（每请求 12 次往返），不是当时怀疑的 Actuator 埋点。改成整月位图读一次、内存里算位后，稳态 1,206 → 3,374 QPS、147 → 40 ms（见优化记录 14）
- [x] **Testcontainers 改造**：`src/test/java/asia/creat/support/IntegrationTestcontainers.java` 统一起 mysql:8.4（`withInitScript("db/ticket.sql")`）、redis:7-alpine、rabbitmq:3.13-management，`@DynamicPropertySource` 注入随机映射端口，集成测试不再依赖手动启动的中间件，也不再受本机 3306 占用影响
- [x] 后端 Dockerfile + 纳入 docker-compose，实现一条命令跑起全栈
- [x] **GitHub Actions**：`.github/workflows/ci.yml` 两个 job，后端 `mvn -B test`（Testcontainers 跑全量集成测试）、前端 `npm ci` + `npm run build`，无 `continue-on-error`
- [x] **修复删除图片的路径穿越漏洞**（见优化记录 8）
- [x] **修复签到与点赞的 check-then-act 并发缺陷**（见优化记录 9）
- [x] **全量 review 的 P0/P1/P2 三档修复**（见优化记录 13）
- [x] **MQ 补偿链路三处缺陷（B1/B2/B3）与 `releaseStock` 事务边界（C）**（见优化记录 13）
- [x] **复查清单剩余七项**：评价列表 N+1、三处热门排序索引、`RedisIdWorker` 时区、拦截器 401/403 JSON 体、Lettuce 与 Redisson 池参数、`maxLimit` 兜底（见优化记录 15）。剩演出列表的分类名 N+1 与 `debug` 日志未动

## 优化记录

### 1. `myOrders` 的 N+1 查询

- **问题**：订单列表在 `stream().map()` 里逐条 `ticketMapper.selectById` 和 `eventMapper.selectById`，一页 10 条订单产生 21 次 DB 往返。
- **修复**：先抽取 `ticketIds` / `eventIds` 去重成 Set，两次 `selectBatchIds` 转成 Map，循环内查 Map。降到 3 次查询。
- **记忆点**：**N+1 最爱藏在"看起来只是一行"的循环体里**。凡是在 `map`/`for` 内部出现 `selectById`，一律要问"这能不能批量"。判断依据不是代码长度，是它在循环的第几层。

### 2. 积分退还按票价反推，票档改价即退错（资损）

- **问题**：`releaseStock` 用 `ticket.getPrice() - order.getPrice()` 反推当初抵扣了多少积分。但 `tb_ticket.price` 是可变的——运营调价后，任何一笔老订单取消都会退错：票价调高就多退，调低就少退甚至不退。
- **修复**：`tb_ticket_order` 增加 `used_credits` 列，下单时把实际抵扣值写进订单快照，取消时直接按该列退还。顺带去掉了这里已无用的 `ticketMapper.selectById`。
- **记忆点**：**订单是快照，不是外键的视图**。凡是"下单那一刻的事实"——成交价、抵扣额、折扣率、税率——都要落在订单行上，不能事后从关联表反推。关联表的值会漂移，订单的历史不该跟着漂。这类 bug 平时测不出来，只在"改了价 + 取消老订单"的组合下才暴露，属于典型的资损型缺陷。


### 4. 兜底定时任务全表拉取且多实例重复执行

- **问题**：`releaseTimeoutOrders` 每 60 秒把所有超时未支付订单一次性 `list()` 进内存，无上限。正常情况下延时队列已经关掉它们、这里是空集，但一旦 MQ 积压或消费者挂过，就可能一次拉出几万条。多实例部署时每个实例都会跑。
- **修复**：加 Redisson `tryLock`，拿不到锁直接跳过本轮；查询加 `orderByAsc("create_time")` + `limit 500`。
- **记忆点**：**兜底任务的危险在于它平时不干活**。因为常态是空集，容量问题在测试和日常运行中永远不暴露，只在主链路已经出故障时才引爆——正好是系统最脆弱的时刻。所以兜底逻辑必须自带限流。另外多实例下 CAS 能保证结果正确，但不能免除重复劳动，锁是省资源不是保正确。


### 6. 详情页伪缓存穿透放大与 UV 读写解耦（调优实战）

- **问题**：`GET /event/1` 名义上加了 Redis 缓存，但实测 QPS 仅 565.6 req/s、平均耗时 266ms。通过 `EventDetailProfilingTest` 采样测量 2,000 样本发现：每次请求只缓存了 `Event` 实体，仍需穿透查询 2 次 MySQL（分类名 + 票档列表），MySQL 查询耗时占比高达 55%~59%，且叠加了 Redis HyperLogLog `PFCOUNT` 计算，单次请求经历 4 次网络 RTT。
- **修复**：
  1. 缓存目标从单表 `Event` 改为全量聚合视图 `EventDetailVO`（命中 0 次 MySQL），并在演出更新及开票时同步双写失效；
  2. 将 UV 想看人数从 `EventDetailVO` 中剥离，前端采用独立 `uv` 响应式状态管理，去重分支调用 `eventApi.getViews(id)`，实现长效静态缓存与实时动态统计解耦；
  3. Lettuce 连接池曾从 `max-active: 10` 扩容至 `50`，**因无证据支持收益已恢复为 10**（见下）。
- **实测收益（变量隔离，三轮压测）**：
  - **阶段一，仅聚合缓存 + UV 剥离**（连接池 10）：QPS 565.6 → 3,558.7，平均耗时 266.27ms → 16.90ms，最大长尾 1,135ms → 152ms；
  - **阶段二，连接池扩到 50**：QPS 3,122.6，较阶段一 -12.3%；
  - **阶段三~五，连接池恢复 10 后连测三轮**：3,210.3 / 4,246.3 / 3,372.7。**四轮同配置测量极差 28.8%，远大于配置间的 12.3%**——阶段二那个"下降"完全落在噪声里。结论是"无法判定"，不是"连接池有害"。已恢复 `max-active: 10`。


- **记忆点 1**：**"缓存了部分字段"比"完全不缓存"更具迷惑性**。一个接口只要还查 MySQL，它的耗时下限就仍由慢速存储和网络 RTT 决定，Redis 只是让人误以为已经优化过了。判断一个接口是否真的走缓存，看的不是有没有调 Redis，而是**命中路径上还剩几次 I/O**。
- **记忆点 2**：**动静分离**。把 UV 这类高频变动字段塞进本该长 TTL 的聚合缓存，要么逼着缓存频繁失效，要么每次请求单独实时计算——两种都让聚合缓存失去意义。
- **记忆点 3**：**判断一个差异是否真实，前提是先知道"什么都不改能差多少"**。这条结论我改了两次：只做 A/B 两次测量时，看到 12.3% 的差异很自然归因为配置；加测一轮同配置复现（差 9.8%）后改判"无法区分"；再加两轮才发现同配置极差达 **28.8%**，噪声比最初估计的高三倍。**没有噪声基准的对照实验，产出的是故事不是结论；而噪声基准本身也需要足够的样本量才可信。**
- **记忆点 4**：**统计脚本本身也会撒谎**。`run_all_benchmarks.ps1` 原先用 `Max(1, duration)` 兜底除零，压测跑进 1 秒内时分母被钉死，4,000 样本直接算成"4,000 QPS"。这个数字看起来完全正常、不会报错，只会静静地进报告。已修复。




### 9. 签到与点赞"先读后写"，并发下积分/计数发双份

- **问题**：两处同一个模式。签到是 `getBit` 判断今天没签过 → `setBit` → `credits + 10`；点赞是 `ZSCORE` 判断没赞过 → `liked + 1` → `ZADD`。判断和写入之间有窗口，双击或并发请求都能穿过：bitmap 最终仍是 1，但积分加了 20；ZSet 最终只有一个成员，但 `liked` 加了 2。
- **修复**：改用 Redis 命令自身的返回值做原子判定，不再单独读一次。
  - 签到：`SETBIT` 返回该位**旧值**，旧值为 0 的那次调用才是当天首次签到，只有它加积分；
  - 点赞：先 `ZREM`，返回 1 说明本次移除成功（取消点赞）；返回 0 再 `ZADD`，返回 true 说明本次新增成功（点赞）。只有真正改变了集合的调用才动 DB 计数。
- **验证**：`SignAndLikeConcurrencyTest` 用 `CountDownLatch` 起跑线放 20 个并发请求打同一个用户。签到 20 次并发后积分为 10（不是 200），点赞 20 次并发后 `liked` 相对基线只变化 0 或 1。
  - 第一版断言写错过：断言 ZSet `zCard <= 1` 是**恒真的**（ZSet 成员本身不可重复，这个断言永远不会失败）。改成断言"Redis 集合里有没有这个用户"与"`liked` 变化量"必须一致——点赞修复把 Redis 写入提到了 DB 之前，这条才真正卡住"集合加了人但计数没加"的脱节。
- **记忆点 1**：**"检查后写入"（check-then-act）是并发 bug 的标准模板**，`SETBIT`/`ZADD`/`ZREM`/`SETNX` 这些命令的返回值本身就是"我是不是那个改变了状态的人"的答案，用它替代额外的一次读，就把两步合成一步。看到"先查再改"的代码就该问：这两步之间如果插进另一个请求会怎样？
- **记忆点 2**：**一个永远不会失败的断言，和没有断言是一回事**，但它更糟——它让人以为验证过了。写完断言要反问"什么输入能让它红"，答不出来就说明它没在测东西。

### 12. 容器 MySQL 是 UTC，新订单几十秒就被自动关单

- **症状**：预约成功后回到票夹，订单已变成"已取消"；同时票夹显示的预约时间比实际早 8 小时（21:21 显示成 13:21）。两个症状同一个根因。
- **定位**：先排除"点票夹触发取消"这个表象——关单日志的线程名是 `scheduling-1`，不是 `nio-8080-exec-*`，说明动手的是每 60 秒一轮的兜底扫库任务 `releaseTimeoutOrders`，用户点票夹只是刚好看到了已经发生的结果。日志显示订单 21:21:43 落库、21:21:47 就被关单，只隔 4 秒，而超时阈值是 15 分钟。
  - 对比两个容器的 `date`：backend 是 `21:25 CST`（`TZ=Asia/Shanghai`），mysql 是 `13:25 UTC`（`TZ` 为空）。compose 的 mysql 服务没有任何时区配置，容器 MySQL 的 `NOW()` 与 `UTC_TIMESTAMP()` 完全相同。
  - `create_time` 是 `timestamp DEFAULT CURRENT_TIMESTAMP DEFAULT_GENERATED`，由**数据库**生成，实体上没有 `@TableField(fill = ...)`，所以写进去的是 UTC 的 `13:21:43`；而 deadline 由 `LocalDateTime.now().minus(ORDER_TIMEOUT)` 在 **JVM** 算出，是 CST 的 `21:06:47`。`LocalDateTime` 不带时区，驱动把字面值原样发给 MySQL，于是 `13:21:43 < 21:06:47` 成立——**每一张新订单都显得像 8 小时前创建的**。`ORDER_TIMEOUT` 与 `ORDER_TTL` 都是 15 分钟，常量没有不一致。
  - 显示错 8 小时是同一条链：MySQL 按 session 时区（UTC）渲染这一列，Connector/J 读进无时区的 `LocalDateTime` 不做换算就透传到前端。URL 里的 `serverTimezone=Asia/Shanghai` 对 `LocalDateTime` 不起作用。
  - 本机开发一直没暴露，因为原生 MySQL 跟随 Windows 系统时区，本来就是 +08:00，与 JVM 一致（实测 `offset_hours = 8`）。**这是容器化引入的缺陷。**
- **修复**：compose 的 mysql 服务加 `TZ: Asia/Shanghai`，一个变量就够。曾多加一条 `--default-time-zone=+08:00`，实测是冗余的：`time_zone` 默认值 `SYSTEM` 表示直接调 OS 的 localtime，而官方镜像自带 tzdata，`TZ` 一设 `NOW()` 就跟着走了。"命名时区要先导入时区表"只约束 `SET time_zone='Asia/Shanghai'` 那种写法，`SYSTEM` 模式不受影响。
  - 历史数据不用迁移：`create_time` 是 TIMESTAMP，内部存绝对时刻，换 session 时区后直接渲染成北京时间（旧订单从 `13:21:43` 变为 `21:21:43`）。`pay_time` 是 DATETIME、由应用以 CST 写入，本来就是北京时间，两列因此自动对上。
- **验证**：重建容器后 `time_zone` 仍显示 `SYSTEM`，但 `NOW()` 与 `UTC_TIMESTAMP()` 已相差 8 小时（`offset_hours = 8`）——**看 `time_zone` 的值会误判成没生效，要看两个时间函数的差**。新下一单 21:37:24，跨两轮扫库到 21:40:54 仍为 `status = 0`，无任何关单日志。同期旧订单的 MQ 延时消息在建单后 15 分钟准时到达，幂等判断识别 `currentStatus=2` 正确跳过——**延时队列一直是对的，坏的只有扫库兜底这一条路**。
- **记忆点 1**：**跨进程比较时间，时区必须是显式契约，不能是各自的默认值。** "取当前时间"（JVM）和"存当前时间"（MySQL）由两个进程完成，只要默认时区不同，比较就系统性出错。`LocalDateTime` 在这里最危险——它不带时区，驱动因此**不做任何转换**，字面值直传，错得悄无声息。跨进程的时间比较应该用 `Instant`/`OffsetDateTime`，或者干脆让比较整个发生在数据库端（`create_time < NOW() - INTERVAL 15 MINUTE`），一端算完就没有对齐问题。
- **记忆点 2**：**环境只统一一半，比完全不统一更危险。** 第 7 节为了缓存序列化把 JVM 钉成 CST，却没管数据库——在那之前 JRE 镜像和 MySQL 镜像**都是 UTC，恰好能对上**；钉死一半反而把"巧合正确"变成了"稳定错误"。凡是靠环境一致才成立的假设，要么把所有参与方都钉死，要么把假设本身消掉。
- **记忆点 3**：**症状出现的时机不等于触发原因。** "点票夹后被取消"看着像因果，实际是定时任务每 60 秒扫一轮，用户点票夹时看到的是已经发生的结果。**查线程名比查时间顺序更快定位真正的触发者**——`scheduling-1` 一眼就把 HTTP 请求排除了。

### 13. 全量 review 后的 P0/P1/P2 修复轮

一次通读全仓的 review 列出 28 项，本轮做完 P0（阻断）、P1（安全）、P2（正确性）三档，P3 与文档项未动。下面按档记录，同类问题合并。

**P0 · 阻断**

- **上传图片无法显示**：静态资源未映射到 `uploads` 目录，返回的 URL 前端取不到；`FileStorageService` 落盘成功但没有任何一条路径能读回来。
- **头像上传静默失效**：前端拿到 URL 后没有回写 `tb_user_info.icon`，刷新即丢。
- **库存变动后详情聚合缓存不失效**：详情页缓存的 `EventDetailVO` 内嵌票档 stock，下单/取消后最长 30 分钟显示旧库存（`evictEventDetailCacheAfterCommit`，挂 `afterCommit` 而不是事务内——事务内删缓存的话，删除到提交之间的并发读会把旧值写回，反而留下一份能活满整个 TTL 的脏数据）。

**P1 · 安全**

- **删图接口越权**：`DELETE /upload/image` 走的不是管理员模型——用户本来就该能删自己的图，管理员化会把正常功能锁死。改为**归属校验**：上传时文件名带 `{userId}-{uuid}.{ext}` 前缀，删除时只认前缀里的 userId。归属信息必须落在路径本身，因为图片在评价发布前就能被撤回，此时它还没进任何数据库记录，没有别的地方可查归属。修复前只要求登录，而图片 URL 通过评价接口公开可见——**抓一遍别人的评价就能把他的图全删掉**。没有归属前缀的历史文件一律拒删。（与优化记录 8 的路径穿越是同一个文件的两个不同漏洞：那个是能删到目录外，这个是能删别人的。）
- **匿名 UV 可无限刷**：未登录访客每次生成新 UUID 塞进 HyperLogLog，基数随刷新次数线性增长。改按 `getRemoteAddr()` 计入。
- **验证码无发送限流**：同一手机号可无限触发。加 Redis 计数限流。
- **登录白名单散落在拦截器里**：抽成 `PublicEndpoints`，让"哪些路径不需要登录"只有一处定义。

**P2 · 正确性**

- **成交价未在预约时冻结**（与优化记录 2 同源）：`TicketOrderMessage` 只带 id，消费端落库时重读 `ticket.getPrice()`。**暴露窗口是预约到落库之间的 MQ 投递延迟**——正常是毫秒级，但积压、消费重试、死信重入都会把它拉长，这期间运营调价，用户就按他没见过的新价成交。`pay()` 只做 `status = 1, pay_time = NOW()`，不重读票档，所以 15 分钟待支付窗口本身不在暴露面内。修法是在 `reserveTicket` 里把 `ticket.getPrice()` 写进消息（那次查询本来就有，不额外增加 IO），消费端只认快照，`null` 才回退到当前票价（滚动发布期间队列里的旧格式消息）。

- **`signCount` 与 `/user/sign/status` 对同一用户给出不同的连签天数**：`signCount` 直接从今天起算，今天还没签到时第一位就是 0，直接返回 0；`getSignStatus` 从昨天起算，返回真实天数。把"今天已签就数到今天，否则数到昨天"抽成 `streakEndDay(today, isTodaySigned)`，两处共用；`getSignStatus` 把已经读过的今日位传进去，不多打一次 Redis（签到接口有未结案的性能回归，不宜再加往返）。
- **静态资源 404 被兜底处理器吞掉**：`@ExceptionHandler(Exception.class)` 出现在用户 advice 里会盖过 Spring 内建的 `ResponseEntityExceptionHandler`，`NoResourceFoundException` 因此变成 500 + 一条 ERROR 堆栈。实测更糟：**返回的是 HTTP 200 + 500 的 JSON body**，`<img onerror>` 根本不触发，浏览器拿 JSON 当图片渲染。单独加 `@ResponseStatus(HttpStatus.NOT_FOUND)` 的处理器。

- **库存预热不恢复一人一票 Set**：`TicketStockCacheInitializer` 只对 stock key 做 `setIfAbsent`，资格 Set 一直没人管。Redis flush、主从切换丢数据、key 格式迁移之后 Set 是空的，已持活跃订单的用户能再次通过 Lua 的 `sismember`。超卖仍被 MySQL 的 `stock > 0` 与 `uk_user_ticket_active` 挡住，**丢的是用户体验**：预约拿到订单号、页面显示成功，落库时被 `activeCount` 检查拒掉，走完 3 次重试进死信才回滚这次预扣。补 `rebuildReservationSets()`，从 `status IN (0,1)` 的订单按 ticket 分组 `SADD`。

**MQ 补偿链路与事务边界（原「已知问题」B1/B2/B3/C，本轮一并修完）**


- **B1 · confirm 回调回滚 Redis 时不查 MySQL**：死信消费者是先 `getById` 确认没落库才回滚，confirm 回调直接回滚——同一件补偿事两套实现，**这个不对称本身就是缺陷的证据**。消息其实已入队并落库、只是确认丢了（Broker 重启、网络抖动），Redis 库存就会多加 1、资格 Set 成员被清掉，用户能再抢一张。修法是抽出 `rollbackIfNotPersisted`，与死信消费者共用同一判定。**查不动库时选择不回滚**：回滚错了会超发，不回滚只是预扣泄漏，可由重启预热对齐——两种失败模式代价不对等，就往代价小的那边倒。残留竞态（确认丢失且消费者尚未落库）由 `uk_user_ticket_active` 收口，影响收敛为"别人多了一次预约机会"。
- **B2 · 落单幂等键用「人+票」而不是 `order.id`**：那是业务规则，不是消息去重。用户取消后 `status=2` 不在 `(0,1)` 里，同一条消息此时被重投会**再落一单**。改成 `getById(message.getId())`，两件事分开：幂等看订单号，一人一票交给 Redis Set 与唯一索引。
- **B3 · `tryLock()` 拿不到锁就抛异常进死信**：锁按 userId 且不等待，同一用户连点两档、或 prefetch 把同一用户两条消息投给不同线程时，后一条重试 3 次仍冲突就进死信，死信消费者查无单于是回滚 Redis——**用户明明抢到了，资格被系统自己收走**。改成 `tryLock(5s)` 有界等待（`createTicketOrder` 本身是毫秒级，5 秒能吸收绝大多数瞬时冲突），不传 leaseTime 交给看门狗续期，避免落库慢于租期时锁被提前释放。
- **C · `releaseStock` 把 Redis 写在事务中间**：Redis 不参与 MySQL 事务回滚。Lua 执行完（库存 +1、资格已清）后续步骤失败 → 事务回滚 → 订单仍是待支付 → 下次关单时 Lua 返回 0（资格记录已经没了）→ 抛异常 → 再次回滚，**订单永久卡在待支付，MySQL 库存也再回补不了**，用户看到一张点取消永远报错的订单。改为挂 `afterCommit`：MySQL 是唯一事实来源，订单已取消、库存已回补就算成功，Redis 这步失败只留告警。Lua 的 rollback 本身幂等（`srem` 返回 1 才 `incrby`），重复执行安全。



- **记忆点 1**：**测试可以把 bug 钉死**。`SignStreakTest` 原有一条用例断言"今日未签到时 `signCount` 返回 0"，还写了理由"语义是截至今天的连续天数"——它把缺陷当成规格固定住了。发现代码与测试冲突时，先判断哪一边是对的，不要默认改代码让测试变绿。相关的 commit message 也不可信：`a98b4ca` 声称"收敛 signCount 与 getSignStatus 两处同源实现"，实际只统一了底层的 `continuousSignDays` 辅助方法，入口的口径差异原样留着。
- **记忆点 2**：**review 报告的数量是下限，不是清单**。"分页缺第二排序键"点了两处，按同一个模式清查全仓是四处。同一模式的缺陷要按模式搜一遍，只修被点名的那几处等于留着已知问题。
- **记忆点 3**：**改协议之前先读消费端**。把业务错误改成真实 HTTP 状态码看着更规范，但前端错误分支不读非 2xx 的 body，改完所有错误提示都会退化成 `Request failed with status code 400`。**协议的正确性由两端共同定义**，只看一端的代码得出的结论是不完整的。
- **记忆点 4**：**"数据库能兜住"不等于"用户没事"**。资格 Set 丢失时唯一索引确实防住了两张活跃订单，但用户走完的是"预约成功 → 订单不存在"这条路，还白等了三次重试加一轮死信。**兜底约束保护的是数据，不是体验**，两者都要单独看。
- **记忆点 5**：**预热的顺序本身是一层保护**。先补资格 Set 再写库存键：库存键不存在时 Lua 第一步就返回"库存不足"，谁都抢不到票；反过来先放开库存，资格 Set 没补完的窗口里已持票用户能再抢一张。**初始化的先后顺序要按"哪一步失败时系统是安全的"来排。**

### 14. 签到接口性能回归结案：12 次 Redis 往返压到 3 次

压测报告里挂了一条未结案的回归：`GET /user/sign/status` 从基线 2,542.6 QPS 掉到 983 ~ 1,056，三轮复测极差只有 7.2%，不是噪声。报告当时怀疑是 Actuator 的每请求埋点，并给出了「从 `pom.xml` 移除 actuator 再压一轮」的对照实验方案。

**那个假设是错的，对照实验也不会有结论。** `git log -S 'spring-boot-starter-actuator'` 落在 `3d5d4be`，正是记录 2,542.6 基线的那次提交——两轮压测的 classpath 上都有 actuator。报告里「本次新增该依赖」这句话本身与提交历史不符。

真正的原因是接口**最多只读两个 key，却按位逐次打 Redis**：今日 1 次 `GETBIT`、连签 1 次 `BITFIELD`、当月累计 1 次 `BITCOUNT`、本周七天各 1 次 `GETBIT`，加上 `RefreshTokenInterceptor` 每请求固定的 `HGETALL` + `PEXPIRE`。用 `CONFIG RESETSTAT` + 单请求 + `INFO commandstats` 实测：**每请求 12 次往返**。

其中 `getbit` 是 8 次而不是 7 次，因为它既读了今天、又在周循环里把今天再读一遍。而且这个数字**随星期几摆动**：周日本周七天全部落在今天或之前，要 8 次；周一只要 6 次。一个只读接口的开销跟着日历变化近 2 倍。

改法是把整月位图一次 `GET` 回来，在内存里算位：`monthBitmap()` 按 key 缓存整月字节（一个月最多 4 字节），`isSigned()` 按 `SETBIT` 的高位在前语义定位（第 N 天在第 `(N-1)/8` 字节、掩码 `0x80 >>> ((N-1)%8)`），`countSignedDays()` 用 `Integer.bitCount` 顶替 `BITCOUNT`。往返降到 **3 次**（2 次拦截器 + 1 次位图），跨月周或连签回溯到上月时 4 次。

**必须走 `RedisCallback` 拿裸字节，不能用 `opsForValue().get()`**：位图是任意二进制，经 `StringRedisTemplate` 的 String 序列化器解码会破坏 0x80 以上的字节——而每月 1 号、9 号这些天正好落在那些位上。

**验证**：同机、同 JMeter 计划（200 线程 × 50 轮 = 10,000 样本）、同一批 token，改前改后各跑三轮（改前那三轮是把改动 `git stash` 后重新构建镜像跑的，不是拿 8 月 28 日的旧数据对比）：

| 轮次 | 改前（12 次往返） | 改后（3 次往返） |
| --- | --- | --- |
| 第 1 轮（冷启动，JIT 未热） | 1,046.5 QPS / 172 ms | 2,395.2 QPS / 66 ms |
| 第 2 轮 | 1,194.9 QPS / 149 ms | 3,349.0 QPS / 41 ms |
| 第 3 轮 | 1,206.1 QPS / 147 ms | 3,373.8 QPS / 40 ms |

稳态 **1,206 → 3,374 QPS（2.8 倍），平均耗时 147 → 40 ms（降 73%）**，两侧错误率均为 0。功能侧：`SignStreakTest` 从 5 条补到 9 条（新增本周每日状态、当月累计、跨月周、位图短于待查日四条），改动前后全绿；另在真实容器上用 `SETBIT` 造了一个跨 4 字节的散点位图（1 号 / 24 号 / 29 号 / 30 号），七天状态、连签 2 天、当月 4 天与 Redis 自己的 `BITCOUNT` 全部吻合。

### 15. 复查清单里剩余七项：评价列表 N+1、排序索引、ID 时区、拦截器错误体、连接池

对一份 12 项的复查清单逐条验证，**5 项此前已修、1 项两个半句都不成立、6 项真实存在**（其中一项比清单描述的更严重）。不成立的那项值得记：清单说 `CacheClient` 与 `SpringRabbitListener` 是死代码——`CacheClient.queryWithPassThrough` 正在 `EventServiceImpl.java:67` 服役，只有 `set` 未被调用；而 `SpringRabbitListener` 这个类在仓库里根本不存在，真实的消费者是 `TicketOrderConsumer` / `TicketOrderCancelConsumer` / `TicketOrderDeadConsumer`。**清单里的类名先 grep 一遍再动手，否则会去"修"一个不存在的文件。**

**评价列表的 N+1（本轮主项）**：`toReviewVO` 逐条调用，每条评价各查一次作者（SQL）、各查一次 ZSCORE（Redis）。一页 10 条 = 1 次分页 + 10 次 SELECT + 10 次 ZSCORE。改成 `toReviewVOList` 批量组装，单条路径复用批量实现（`List.of(review)`）避免同一套字段映射写两遍。作者用一次 `listByIds`；点赞状态**必须用 pipeline 而不是 `ZMSCORE`**——每条评价的点赞集合是各自独立的 key，`ZMSCORE` 只覆盖同一个 key 的多个 member，用不上。实测一页 3 条的请求 SQL 从 5 条降到 **3 条**（count + 分页 + 一条 `IN`），且不随页大小增长。

**排序索引缺失（比清单说的更严重）**：清单只提"热门排序"，实际有三个排序点无索引可用——`queryHotEvents`、`queryEventByCategory`、`queryHotReview`。三条索引都必须把 `DESC` 写进定义，DDL 见迁移注意。

**`RedisIdWorker` 时区偏差**：`LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)` 取本地墙上时钟却当 UTC 读，东八区下算出的 epoch 秒比真实值**大 28800**，把 ID 反解回时间会差 8 小时。改用 `ZonedDateTime.toEpochSecond()`。顺带修了一个更隐蔽的：原来时钟读两次（一次算时间戳、一次取计数键的日期），**跨零点的瞬间会拿到时间戳在新一天、计数键还在旧一天**，改成只读一次。这一项是**潜在缺陷**——目前没有任何代码反解 ID 里的时间戳，所以线上没有可观测的错误行为。

**拦截器 401/403 无响应体**：拦截器在 Controller 之前返回，落在 `@RestControllerAdvice` 之外，只 `setStatus(401)` 就直接 return，body 是空的。抽 `InterceptorErrorWriter` 手写 `Result` 体，`body.code` 与 HTTP 状态码同值。前端 `http.ts` 的错误拦截器原先直接把 `AxiosError` 再抛出去，于是 403 在界面上显示为 `Request failed with status code 403`，一并改成优先读 `body.msg`。

**连接池参数**：Lettuce `max-active` 从 10 提到 50、`min-idle` 从 1 提到 8。依据是优化记录 14 的实测数据——每次 Redis 往返边际成本 11.9 ms，而 Redis 自报 `usec_per_call` 只有 2~15 微秒，差三个数量级，那 11.9 ms 基本全是池等待（200 个 Tomcat 线程 ÷ 10 条连接 ≈ 每条连接后面排 20 个请求）。Redisson 是**另一个独立的池**，补了 `connectionPoolSize(32)` 与 `retryAttempts(3)`——默认 `retryAttempts` 为 -1 会让请求线程无限等待，直到 Tomcat 线程池被耗尽。

**`MybatisConfig` 的 `maxLimit`**：这一项**是兜底而非补漏**，写进代码注释里说清楚了。当前所有分页入口都过 `@Validated PageQuery(@Max(100))`，打不穿；`maxLimit` 防的是以后有人绕开 `PageQuery` 直接 `new Page<>(1, size)`。

**验证**：全量 `mvn test` **51 项全绿**（1 项 skip 是 `SignStreakTest` 按日期的 `assumeTrue`，本来就有）。新增 `RedisIdWorkerTest`（4 项）与 `EventReviewBatchTest`（4 项），前者已验证能捕获旧实现（旧代码在"ID 反解回当前时刻"这条上报 `偏差 28800 秒`）。索引 1、2 用 `EXPLAIN` 确认 `type: ref` 且无 filesort。

- **记忆点 1**：**空索引验证要区分"优化器不选"和"索引没用"。** `idx_liked_id` 建好后 `EXPLAIN` 仍是 `type: ALL` + `Using filesort`，`possible_keys` 甚至是 `NULL`——表里只有 3 行，全表扫的代价本来就低于走索引。先用 `FORCE INDEX` 证明索引可用（`type: index`、`Extra: NULL`），再灌 5,000 行 + `ANALYZE TABLE` 证明行数够时优化器会自己选（`rows: 10`、无 filesort），最后把造的数据删干净。**看到"索引没生效"先问表有多大，别急着改索引定义。**
- **记忆点 2**：**pipeline 的下标对位靠的是"返回顺序 = 入队顺序"这一条保证**，不是靠 key 里带 id 去匹配。所以测试必须造出"只给中间那条点赞"的不对称输入——三条全点赞或全不点赞时，串位了也看不出来。
- **记忆点 3**：**测试断言的期望值不能来自被测代码的输入侧。** 我第一版夹具只把作者塞进 `UserHolder`（ThreadLocal），却断言 VO 的 `userName` 等于那个昵称——而 `userName` 是批量查 `tb_user` 得来的，作者没落库就是 null。同理第 4 条"单条与列表一致"当时是**空过**的：两边都是 null，断言全部通过却什么都没测到。修法是作者真的 insert，并补一条 `assertEquals(AUTHOR_NICK, ...)` 把非空钉死。**"两个都是 null 所以相等"是最容易漏的假绿。**
- **记忆点 4**：**`@RestControllerAdvice` 管不到拦截器。** 统一错误协议这类横切约定，要按"请求在哪一层被终止"来盘一遍出口，只在 Controller 层验证会漏掉鉴权失败这条最常见的路径。

### 16. 幽灵关注，以及上一轮自己引入的事务边界回归

又一份 4 项清单，**2 项真实存在、1 项存在但给出的修法会引入新故障、1 项不成立**。

**幽灵关注（真实）**：`follow()` 只挡了"关注自己"和重复关注，`targetUserId` 从不校验存在性，`tb_follow` 也没有指向 `tb_user` 的外键。伪造 id 会一路写进 MySQL 和 Redis Set。补 `userService.getById(targetUserId) == null` 抛 404，放在幂等判断**之后**——重复关注直接返回，不多付一次查库。

**事务边界回归（上一轮自己引入的，非清单项）**：优化记录 15 给 `saveReview` 加 `@Transactional` 是为了让评价插入与 `comments + 1` 同事务，但方法里**原本就有**给粉丝推 Feed 的 ZADD 循环，加注解后这段被一并圈进事务、跑在 commit 之前。事务回滚时评价没落库，Feed 里却留下指向不存在评价的 id。挪进 `afterCommit`，与 `TicketOrderServiceImpl` 释放 Redis 库存的既有写法一致。

**空分类 GEO 懒加载（存在，但没按建议改）**：`loadEventByCategory` 查空后直接 `return`，不写任何东西，于是该分类每次请求都重新抢锁查库一次。清单建议 `opsForValue().set(key, "", CACHE_NULL_TTL)`——**这个 key 不能这么写**：它在 `opsForGeo().add()` 写、`opsForGeo()` 读，是 ZSET；塞进 String 之后 `hasKey` 为 true 让加载逻辑被跳过，GEOSEARCH 打到 String 上直接 WRONGTYPE，该分类的附近查询在 TTL 内全部 500。**拿一条正确但偏慢的路径换一条报错的路径。** 要修得另起一个 marker key，而收益只是"分类存在但零在售演出"这个边缘态下少一次空查询（且锁在，并发进不来，不是击穿风暴），改动风险大于收益，**故意只记不改**。

**不成立的那项**：清单说详情页预约成功后没有去支付的入口、用户要自己找导航。实际 `EventDetailPage.vue:329-331` 的成功浮层里就挂着 `<RouterLink to="/orders">` + "前往票夹查看"按钮，`v-if="reservation"` 控制显隐。**清单只看了 `:216` 那行 toast，没往模板里翻。**

**顺带清掉最后一处列表 N+1（非清单项）**：`toListItemVOList` 原先每条演出一次 `eventCategoryMapper.selectById`，四个列表接口共用。**没有按常规改成 `selectBatchIds`** —— `EventCategoryService.queryCategoryList()` 早就存在、而且自带全量 Redis 缓存（分类列表接口一直在打，通常是热的），直接复用它就等于常态零次查库，还省掉第二套缓存和它的失效逻辑；分类是只读字典表，全仓库没有任何增删改入口，这个复用是安全的。详情页 `loadEventDetailVOFromDb` 那次单查也一并收进来了。`eventCategoryMapper` 随之成为孤儿字段，同步删掉。

*
- **记忆点 1**：**给已有 Redis 写操作的方法加 `@Transactional`，要先盘一遍方法里还有什么。** 注解的作用范围是整个方法体，那些原本自动提交后才执行的外部写操作会被静默拖进事务、跑在 commit 之前。加注解时只想着"让这两个 DB 操作原子"，很容易漏掉第三段本该在事务外的代码。
- **记忆点 2**：**加校验后测试变红，先判断红的是代码还是夹具。** 补上存在性校验后 `FollowCountTest` 两个用例炸了，查下去发现它关注的三个目标 id 从没插进 `tb_user`——`cleanUp` 里却在 `deleteById(target)`，说明写测试时以为建了。**这两个用例过去能过，靠的正是被校验堵上的那个洞**，等于反过来证明了洞真实存在。这是本轮最值得记的一次：改夹具，不动生产代码。
- **记忆点 3**：**给缓存加空值占位前，先确认这个 key 的 Redis 数据类型。** 空占位的惯用写法是 String，但被保护的 key 常常是 ZSET / Hash / GEO，同名写入会把"慢"变成 WRONGTYPE 报错。占位要么另起 key，要么用同类型的空结构。
- **记忆点 4**：**修 N+1 之前先找一遍现成的批量/缓存入口。** 这次的字典表已经有一个带 Redis 缓存的 `queryCategoryList()` 摆在那儿，照惯例写 `selectBatchIds` 反而是多造一套。**"该用什么方案"要在读完同层已有代码之后再定。**
- **记忆点 5**：**`git stash` 是做性能前后对比最省事的办法。** 改动留在工作区，stash 一次跑 before、pop 回来跑 after，同一套用例同一套数据。判断口径要盯"其余各表次数是否完全不变"——只有那样才说明差异来自被改的那一处，而不是测试本身跑了不同的路径。


### 17. 拆分后详情慢 3 倍、秒杀慢 4.5 倍

- **详情**：`EventServiceImpl.queryById` 命中聚合缓存后还调 `orderClient.queryTickets` 经 Feign 查订单库。改为详情页不带票档，票档列表由前端单独请求订单服务。1,376 → 2,511 ~ 3,584 QPS。
- **秒杀**：`TicketReservationServiceImpl.reserveTicket` 先落库、恢复任务再跑 Lua，被拒的请求也进事务排票档行锁。改为 Lua 预扣放在受理最前面，售罄在 Redis 就拒掉，只有放行的请求进事务，事务失败按预约号回补。223 → 728 QPS。
- **Lua 前置带来的漏洞**：Redis 扣成功、MySQL 提交前进程退出，会留下没有预约记录的占用，`initialize_ticket_stock.lua` 只在库存键丢失时重建，修不了。`TicketStockCacheInitializer.initialize` 启动时扫预约 hash，受理超过 60 秒且库里查不到的按预约号回补。
- **出票延迟**：建单只靠每秒一次的 `TicketReservationTaskProcessor` 扫描。受理事务 `afterCommit` 后直接推一次，扫描保留兜底；受理到出票 225 ~ 278 ms。
- **票档信息缓存**：受理路径的票档状态、价格、销售时间读 30 秒 Redis Hash。单独测不出收益，落在噪声里。
- **踩坑**：处理器与服务互相依赖，`@Lazy` + `lombok.config` 本地测试全绿、镜像里启动失败，Dockerfile 只拷模块目录不拷根目录文件。改用 `ObjectProvider`。

## 其余已定位未修项

> 本节原有 5 项已在 P0/P1/P2 修复轮中处理（权限模型、成交价冻结、匿名 UV 刷量、库存预热不恢复资格 Set、验证码限流、fans/followee 不更新），详见优化记录 13。

- **列表类 N+1（2026-08-30 已全部清完）**：订单列表、评价列表、演出列表分类名三处都改成批量。
  - `EventServiceImpl.toListItemVOList()` 原先每条演出一次 `eventCategoryMapper.selectById`，四个列表接口共用。改为复用 `EventCategoryService.queryCategoryList()` 的全量 Redis 缓存（见优化记录 16），实测同一组 7 个用例 `tb_event_category` 查询 **6 次 → 1 次**。
  - `EventReviewServiceImpl.toReviewVO()` 已改为 `toReviewVOList` 批量组装，作者一次 `listByIds`、点赞状态一次 pipeline（见优化记录 15）。




-- order-service 完整初始化结构
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `ticket_order`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
USE `ticket_order`;

CREATE TABLE `tb_credit_account` (
  `user_id` bigint unsigned NOT NULL,
  `credits` int unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='积分账户';

-- ---------- 用户积分变动流水 ----------
CREATE TABLE IF NOT EXISTS `tb_credit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `biz_type` tinyint NOT NULL COMMENT '业务类型: 1签到获取 2购票抵扣 3订单取消退还',
  `biz_id` varchar(64) DEFAULT NULL COMMENT '业务关联单号(订单号/签到日期)',
  `change_amount` int NOT NULL COMMENT '变动积分数(+10, -500等)',
  `balance` int NOT NULL DEFAULT 0 COMMENT '变动后积分余额',
  `description` varchar(128) NOT NULL COMMENT '描述',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_biz` (`user_id`, `biz_type`, `biz_id`) USING BTREE,
  KEY `idx_user_time` (`user_id`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户积分变动流水';

-- ---------- 票档 ----------
CREATE TABLE `tb_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL COMMENT '演出id',
  `event_name` varchar(128) NOT NULL DEFAULT '' COMMENT '创建票档时的演出名称',
  `title` varchar(64) NOT NULL COMMENT '票档名(如 看台A)',
  `price` bigint NOT NULL COMMENT '售价(分)',
  `original_price` bigint DEFAULT 0 COMMENT '原价(分)',
  `type` tinyint DEFAULT 1 COMMENT '票档类型：1普通 2特惠/限额预约',
  `status` tinyint DEFAULT 1 COMMENT '状态：1上架 0下架',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_event` (`event_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='票档';

-- ---------- 票档库存 ----------
CREATE TABLE `tb_ticket_stock` (
  `ticket_id` bigint NOT NULL COMMENT '票档id(主键)',
  `stock` int NOT NULL COMMENT '库存',
  `begin_time` datetime NOT NULL COMMENT '开售时间',
  `end_time` datetime NOT NULL COMMENT '停售时间',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ticket_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='票档库存';

-- ---------- 票订单 ----------
CREATE TABLE `tb_ticket_order` (
  `id` bigint NOT NULL COMMENT '订单id(RedisIdWorker生成)',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `ticket_id` bigint NOT NULL COMMENT '票档id',
  `event_id` bigint NOT NULL COMMENT '演出id(冗余)',
  `price` bigint NOT NULL COMMENT '成交价(分)',
  `used_credits` int NOT NULL DEFAULT 0 COMMENT '下单实际抵扣积分(分)，取消退还以此为准',
  `status` tinyint DEFAULT 0 COMMENT '状态：0待支付 1已出票 2已取消',
  -- 一人一票的数据库兜底：活跃订单(待支付/已出票)标记为 1，已取消置 NULL。
  -- MySQL 的唯一索引不约束 NULL，所以取消后可以重新购买，而活跃订单只能有一张。
  -- 这一层是必要的：Redis 挂过、预热未重建资格 Set、或落库前的并发窗口，
  -- 应用层的 count 检查都挡不住，只有唯一索引能保证不出现两张活跃订单。
  `active_flag` tinyint GENERATED ALWAYS AS (CASE WHEN `status` IN (0,1) THEN 1 ELSE NULL END) VIRTUAL COMMENT '活跃订单标记，仅供唯一索引使用',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_ticket_active` (`user_id`,`ticket_id`,`active_flag`) USING BTREE,
  KEY `idx_event` (`event_id`) USING BTREE,
  KEY `idx_user_create_time` (`user_id`,`create_time`) USING BTREE,
  KEY `idx_user_ticket_status` (`user_id`,`ticket_id`,`status`) USING BTREE,
  KEY `idx_status_create_time` (`status`,`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='票订单';

-- ---------- 购票预约 ----------
CREATE TABLE `tb_ticket_reservation` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `order_id` bigint DEFAULT NULL,
  `request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `use_credits` tinyint NOT NULL DEFAULT 0,
  `price` bigint NOT NULL,
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0处理中 1成功 2失败 3已取消',
  `failure_reason` varchar(255) DEFAULT NULL,
  `release_pending` tinyint NOT NULL DEFAULT 0,
  `active_flag` tinyint GENERATED ALWAYS AS (
    CASE WHEN `status` IN (0,1) OR `release_pending` = 1 THEN 1 ELSE NULL END
  ) VIRTUAL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_order` (`order_id`),
  UNIQUE KEY `uk_reservation_request` (`user_id`,`request_id`),
  UNIQUE KEY `uk_reservation_active` (`user_id`,`ticket_id`,`active_flag`),
  KEY `idx_reservation_user_time` (`user_id`,`create_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- 预约恢复任务 ----------
CREATE TABLE `tb_reservation_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `reservation_id` bigint NOT NULL,
  `type` tinyint NOT NULL COMMENT '0发送订单消息 1释放Redis预约',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0待处理 1完成',
  `attempts` int NOT NULL DEFAULT 0,
  `next_retry_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_error` varchar(255) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_task` (`reservation_id`,`type`),
  KEY `idx_task_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- ---------- 购票候补 ----------
CREATE TABLE `tb_ticket_waitlist` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `use_credits` tinyint NOT NULL DEFAULT 0,
  `price` bigint NOT NULL,
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0排队中 1递补中 2已递补 3已取消 4已失效',
  `reservation_id` bigint DEFAULT NULL,
  `failure_reason` varchar(255) DEFAULT NULL,
  `active_flag` tinyint GENERATED ALWAYS AS (
    CASE WHEN `status` IN (0,1) THEN 1 ELSE NULL END
  ) VIRTUAL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_waitlist_request` (`user_id`,`request_id`),
  UNIQUE KEY `uk_waitlist_active` (`user_id`,`ticket_id`,`active_flag`),
  UNIQUE KEY `uk_waitlist_reservation` (`reservation_id`),
  KEY `idx_waitlist_ticket_status_id` (`ticket_id`,`status`,`id`),
  KEY `idx_waitlist_user_id` (`user_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 票档 + 库存（id 对齐，方便测试）
INSERT INTO tb_ticket (id, event_id, title, price, original_price, type, status) VALUES
(1, 1, '内场VIP', 128000, 158000, 1, 1),
(2, 1, '看台A', 68000, 88000, 1, 1),
(3, 1, '看台B', 38000, 58000, 1, 1),
(4, 2, 'A区', 58000, 68000, 1, 1),
(5, 2, 'B区', 38000, 48000, 1, 1),
(6, 4, '东看台', 8800, 12800, 1, 1),
(7, 4, '西看台', 6800, 9800, 1, 1),
(8, 3, '普通票', 6800, 8800, 1, 1),
(9, 3, '学生票', 3800, 5800, 1, 1),
(10, 5, '单日票', 39900, 49900, 1, 1),
(11, 5, '三日通票', 99900, 119900, 1, 1);

INSERT INTO tb_ticket_stock (ticket_id, stock, begin_time, end_time) VALUES
(1, 100, '2025-01-01 00:00:00', '2030-09-20 19:30:00'),
(2, 200, '2025-01-01 00:00:00', '2030-09-20 19:30:00'),
(3, 300, '2025-01-01 00:00:00', '2030-09-20 19:30:00'),
(4, 150, '2025-01-01 00:00:00', '2030-08-30 19:30:00'),
(5, 150, '2025-01-01 00:00:00', '2030-08-30 19:30:00'),
(6, 500, '2025-01-01 00:00:00', '2030-08-23 19:35:00'),
(7, 500, '2025-01-01 00:00:00', '2030-08-23 19:35:00'),
(8, 200, '2025-01-01 00:00:00', '2030-09-01 10:00:00'),
(9, 100, '2025-01-01 00:00:00', '2030-09-01 10:00:00'),
(10, 300, '2025-01-01 00:00:00', '2030-10-02 14:00:00'),
(11, 100, '2025-01-01 00:00:00', '2030-10-02 14:00:00');

UPDATE tb_ticket SET event_name = CASE event_id
  WHEN 1 THEN '「回声」巡回演唱会·杭州站'
  WHEN 2 THEN '话剧《雷雨》·经典重现'
  WHEN 3 THEN '国际当代艺术双年展'
  WHEN 4 THEN '中超联赛·杭州绿城主场'
  WHEN 5 THEN '西湖国际音乐节'
END;

-- ============================================================
-- ticket-center 演出票务系统 数据库初始化脚本
-- ============================================================

-- 必须放在最前面：本文件以 UTF-8 存储，而 mysql 客户端的默认字符集取决于
-- 环境 locale（容器内 locale 不是 UTF-8 时会退化成 latin1），届时文件里的
-- UTF-8 字节会被当 CP1252 读入并二次编码，所有中文变成「è¥¿æ¹–」这类乱码。
-- 声明写在文件里，导入方式就不再影响结果。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `ticket_center`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
USE `ticket_center`;

SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 用户 ----------
CREATE TABLE `tb_user` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户id',
  `phone` varchar(11) NOT NULL COMMENT '手机号',
  `password` varchar(128) NOT NULL DEFAULT '' COMMENT '加密密码',
  `nick_name` varchar(32) NOT NULL DEFAULT '' COMMENT '昵称',
  `icon` varchar(255) NOT NULL DEFAULT '' COMMENT '头像地址',
  `role` tinyint NOT NULL DEFAULT 0 COMMENT '角色：0普通用户 1管理员',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_phone` (`phone`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户';

-- ---------- 用户详情 ----------
CREATE TABLE `tb_user_info` (
  `user_id` bigint unsigned NOT NULL COMMENT '用户id，与tb_user一对一',
  `city` varchar(64) NOT NULL DEFAULT '' COMMENT '城市',
  `introduce` varchar(128) DEFAULT NULL COMMENT '个人介绍',
  `gender` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT '性别：0男，1女',
  `birthday` date DEFAULT NULL COMMENT '生日',
  `credits` int unsigned NOT NULL DEFAULT 0 COMMENT '积分',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户详情';

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

-- ---------- 用户关注 ----------
CREATE TABLE `tb_follow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint unsigned NOT NULL COMMENT '发起关注的用户id',
  `follow_user_id` bigint unsigned NOT NULL COMMENT '被关注用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_follow_user_target` (`user_id`,`follow_user_id`) USING BTREE,
  KEY `idx_follow_target` (`follow_user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户关注关系';

-- ---------- 演出分类 ----------
CREATE TABLE `tb_event_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL COMMENT '分类名',
  `icon` varchar(255) DEFAULT '' COMMENT '图标',
  `sort` int DEFAULT 0 COMMENT '排序',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演出分类';

-- ---------- 演出 ----------
CREATE TABLE `tb_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(128) NOT NULL COMMENT '演出名称',
  `category_id` bigint NOT NULL COMMENT '分类id',
  `venue` varchar(128) DEFAULT '' COMMENT '场馆',
  `address` varchar(255) DEFAULT '' COMMENT '详细地址',
  `x` double DEFAULT 0 COMMENT '场馆经度',
  `y` double DEFAULT 0 COMMENT '场馆纬度',
  `main_image` varchar(1024) DEFAULT '' COMMENT '主图',
  `images` varchar(2048) DEFAULT '' COMMENT '图集(逗号分隔)',
  `intro` text COMMENT '演出简介',
  `start_time` datetime NOT NULL COMMENT '开演时间',
  `duration_min` int DEFAULT 120 COMMENT '时长(分钟)',
  `hot` int unsigned DEFAULT 0 COMMENT '想看人数(UV)',
  `comments` int unsigned DEFAULT 0 COMMENT '评价数',
  `status` tinyint DEFAULT 1 COMMENT '状态：1上架 0下架',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  -- 列顺序对齐 queryHotEvents：WHERE status=1 ORDER BY hot DESC, id DESC。
  -- hot/id 必须写 DESC：升序索引只能倒读单列，两列都倒序就要 filesort，而 MySQL 8.0+ 支持真降序索引
  KEY `idx_status_hot_id` (`status`,`hot` DESC,`id` DESC) USING BTREE,
  -- 对齐 queryEventByCategory：WHERE category_id=? AND status=1 ORDER BY hot DESC, id DESC。
  -- 这条的最左前缀已覆盖原 idx_category(category_id)，故删掉那条单列索引
  KEY `idx_category_status_hot_id` (`category_id`,`status`,`hot` DESC,`id` DESC) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演出';

-- ---------- 票档 ----------
CREATE TABLE `tb_ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL COMMENT '演出id',
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

-- ---------- 演出评价 ----------
CREATE TABLE `tb_event_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` bigint NOT NULL COMMENT '演出id',
  `user_id` bigint unsigned NOT NULL COMMENT '用户id',
  `title` varchar(255) NOT NULL COMMENT '标题',
  `images` varchar(2048) NOT NULL DEFAULT '' COMMENT '图片',
  `content` varchar(2048) NOT NULL COMMENT '内容',
  `liked` int unsigned DEFAULT 0 COMMENT '点赞数',
  `comments` int unsigned DEFAULT 0 COMMENT '评论数',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_event` (`event_id`) USING BTREE,
  KEY `idx_user_create_id` (`user_id`,`create_time` DESC,`id` DESC) USING BTREE,
  -- 对齐 queryHotReview：无 WHERE，ORDER BY liked DESC, id DESC。
  -- 无过滤条件时优化器可以直接顺序扫这条索引取前 N 行，不必全表读进来再 filesort
  KEY `idx_liked_id` (`liked` DESC,`id` DESC) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演出评价';

-- ---------- 动态评论 ----------
CREATE TABLE `tb_event_review_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `review_id` bigint NOT NULL COMMENT '动态id',
  `user_id` bigint unsigned NOT NULL COMMENT '评论用户id',
  `content` varchar(500) NOT NULL COMMENT '评论内容',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_review_create_id` (`review_id`,`create_time` DESC,`id` DESC) USING BTREE,
  CONSTRAINT `fk_review_comment_review` FOREIGN KEY (`review_id`)
    REFERENCES `tb_event_review` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='动态评论';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 演示数据
-- ============================================================

INSERT INTO tb_event_category (id, name, icon, sort) VALUES
(1, '演唱会', '/imgs/types/yzch.png', 1),
(2, '话剧歌剧', '/imgs/types/hj.png', 2),
(3, '展览', '/imgs/types/zl.png', 3),
(4, '体育赛事', '/imgs/types/ty.png', 4),
(5, '音乐节', '/imgs/types/yyj.png', 5);

INSERT INTO tb_event (id, name, category_id, venue, address, x, y, main_image, intro, start_time, duration_min, hot, status) VALUES
(1, '「回声」巡回演唱会·杭州站', 1, '杭州奥体中心体育场', '杭州市滨江区飞虹路3号', 120.2283, 30.2311, '/imgs/events/event1.jpg', '实力唱将年度巡回，万人合唱之夜。', '2030-09-20 19:30:00', 150, 12860, 1),
(2, '话剧《雷雨》·经典重现', 2, '杭州大剧院', '杭州市江干区之江东路66号', 120.2093, 30.2492, '/imgs/events/event2.jpg', '曹禺经典，全新阵容演绎百年名作。', '2030-08-30 19:30:00', 120, 3420, 1),
(3, '国际当代艺术双年展', 3, '浙江展览馆', '杭州市拱墅区环城北路47号', 120.1598, 30.2765, '/imgs/events/event3.jpg', '全球 30+ 艺术家联展，沉浸式光影艺术。', '2030-09-01 10:00:00', 240, 5120, 1),
(4, '中超联赛·杭州绿城主场', 4, '黄龙体育中心', '杭州市西湖区黄龙路1号', 120.1370, 30.2653, '/imgs/events/event4.jpg', '主场迎战，现场助威，激情一夏。', '2030-08-23 19:35:00', 120, 8920, 1),
(5, '西湖国际音乐节', 5, '太子湾公园', '杭州市西湖区南山路5-1号', 120.1465, 30.2339, '/imgs/events/event5.jpg', '三天三夜，独立音乐与民谣的乌托邦。', '2030-10-02 14:00:00', 480, 15630, 1);

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

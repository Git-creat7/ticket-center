-- 热门排序索引：ticket.sql 已含，仅老库需执行。
-- ticket.sql 只在数据卷为空时由 initdb 跑一次，已在跑的库要手动 apply。
USE ticket_center;

ALTER TABLE tb_event
  ADD KEY idx_status_hot_id (status, hot DESC, id DESC),
  ADD KEY idx_category_status_hot_id (category_id, status, hot DESC, id DESC),
  DROP KEY idx_category;

ALTER TABLE tb_event_review
  ADD KEY idx_liked_id (liked DESC, id DESC);

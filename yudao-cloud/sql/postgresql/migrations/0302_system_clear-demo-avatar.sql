-- Migration: 0302 清理后台用户指向芋道演示图床的头像
-- Purpose: 种子数据中管理员等后台用户的 avatar 指向芋道演示站图床 test.yudao.iocoder.cn。该图床已返回 401 拒绝访问，
--          内网部署也无法访问，前端头像显示为破图。本迁移把这类头像清空为列默认值（空字符串），前端遇到空头像时
--          展示项目内置的默认头像。只处理 system_users.avatar 中以 http(s)://test.yudao.iocoder.cn/ 开头的值，
--          用户上传到平台文件服务的头像、其他字段及其他表均不受影响。
-- Depends on: 0301_supply_drop-app-public-flag.sql（编号前驱，无数据依赖）；system_users 表已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；已清空的行不再匹配条件，重复执行不产生变更。
-- Rollback: 会丢弃原演示图床地址；该地址已无法访问，不建议恢复。确需恢复时，执行前先备份
--           SELECT id, avatar FROM system_users WHERE avatar ~* '^https?://test\.yudao\.iocoder\.cn/'，回滚时按备份回填。
--           头像随登录用户信息下发，已登录用户刷新页面或重新登录后生效。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';

-- 演示图床头像清空为列默认值空字符串，前端随即回退到内置默认头像
UPDATE system_users
SET avatar = '',
    update_time = CURRENT_TIMESTAMP
WHERE avatar ~* '^https?://test\.yudao\.iocoder\.cn/';

COMMIT;

-- Migration: 0159 精简 DataForge 表注释。
-- Purpose: 将较长或格式不统一的表注释调整为简短的“模块-对象”名称。
-- Depends on: 0158_dataforge_inline-api-retirement.sql 已执行，或平台已达到对应状态；以下十张表已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；重复执行仅设置相同的表注释。
-- Rollback: 不修改表结构和业务数据；如需恢复旧注释，使用执行前备份中的表注释重新执行 COMMENT ON TABLE。

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

COMMENT ON TABLE "datahub-center".dh_dataforge_access IS '数据同步-任务';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_increase_log IS '数据同步-增量水位';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_task IS '数据同步-执行记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_task_detail IS '数据同步-执行明细';
COMMENT ON TABLE "datahub-center".dh_dataforge_database IS '数据源-数据库';
COMMENT ON TABLE "datahub-center".dh_dataforge_database_type IS '数据源-数据库类型';
COMMENT ON TABLE "datahub-center".dh_dataforge_datasource_driver IS '数据源-驱动';
COMMENT ON TABLE "datahub-center".dh_dataforge_database_sample IS '数据库监控-采样记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_database_alarm_rule IS '数据库监控-告警规则';
COMMENT ON TABLE "datahub-center".dh_dataforge_database_alarm_record IS '数据库监控-告警记录';
COMMIT;

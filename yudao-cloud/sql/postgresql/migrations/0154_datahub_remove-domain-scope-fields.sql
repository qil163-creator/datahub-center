-- Migration: 0154 删除数据域及主数据域的范围说明字段
-- Purpose: 删除两域的责任部门、包含范围、排除范围列，与前后端域管理字段同步。
-- Depends on: 0153_governance_master-domain-parity.sql 已执行；两域表已建立。
-- Target: 已有 PostgreSQL 平台库，结构为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；仅删除仍存在的列，重复执行不修改剩余业务数据。
-- Rollback: 事务失败整笔回滚；提交后须从执行前备份恢复三列及原值，脚本不能恢复已删除内容。
-- 不可逆变更：永久删除两表这三列的业务值。执行前备份两表，先部署对应新版本并停止旧版本写入。
-- 删除列会短时锁表；锁等待超过十秒或执行超过两分钟则回滚，不级联删除任何依赖对象。
-- 保留负责人、电话、备注、租户、审计记录、模型归属及现有约束和索引。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_modeling_data_domain
    DROP COLUMN IF EXISTS owner_department,
    DROP COLUMN IF EXISTS scope_includes,
    DROP COLUMN IF EXISTS scope_excludes;

ALTER TABLE dh_governance_master_domain
    DROP COLUMN IF EXISTS owner_department,
    DROP COLUMN IF EXISTS scope_includes,
    DROP COLUMN IF EXISTS scope_excludes;

COMMIT;

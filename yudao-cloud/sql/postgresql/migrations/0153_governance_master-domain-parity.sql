-- Migration: 0153 主数据域业务字段对齐
-- Purpose: 主数据域独立存储，补齐与数据域一致的负责人、备注、责任部门及纳入和排除范围字段。
-- Depends on: 0152_supply_api-log-operation-tabs.sql 已执行；0150 已建立主数据域表及模型归属约束。
-- Target: 已有 PostgreSQL 平台库，固定结构为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；新增列按存在性处理，重放仅更新字段注释，不覆盖已有业务值。
-- Rollback: 失败整笔回滚；提交后回退应用可保留新增可空列。删除新增列前须备份对应业务值。
-- 加列会短时锁表；等待超过十秒或执行超过两分钟则回滚。不复制数据域记录，不指定历史域负责人。
-- 保留现有编码长度、图标、排序、历史目录编号及模型引用；不修改租户隔离、唯一约束和菜单权限。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_governance_master_domain
    ADD COLUMN IF NOT EXISTS leader_user_id bigint,
    ADD COLUMN IF NOT EXISTS leader_phone varchar(20),
    ADD COLUMN IF NOT EXISTS remark varchar(500),
    ADD COLUMN IF NOT EXISTS owner_department varchar(128),
    ADD COLUMN IF NOT EXISTS scope_includes varchar(2000),
    ADD COLUMN IF NOT EXISTS scope_excludes varchar(2000);

COMMENT ON COLUMN dh_governance_master_domain.leader_user_id IS '负责人用户编号';
COMMENT ON COLUMN dh_governance_master_domain.leader_phone IS '负责人电话';
COMMENT ON COLUMN dh_governance_master_domain.remark IS '备注';
COMMENT ON COLUMN dh_governance_master_domain.owner_department IS '责任部门说明，不作为授权依据';
COMMENT ON COLUMN dh_governance_master_domain.scope_includes IS '纳入本域的数据范围';
COMMENT ON COLUMN dh_governance_master_domain.scope_excludes IS '明确排除的数据内容';

COMMIT;

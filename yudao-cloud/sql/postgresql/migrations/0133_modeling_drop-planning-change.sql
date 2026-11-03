-- Migration: 0133 删除建模规划引用与影响审计
-- Purpose: 下线建模「引用与影响」功能：移除业务分类、数据域、主题域、数仓分层上的规划审计触发器、审计函数及 dh_modeling_planning_change 表与序列。
-- Depends on: 0131_modeling_simplify-design-deploy.sql 对应平台状态（规划审计触发器挂在四张规划表上）；0132_dataforge_database-password-ciphertext.sql 已执行。
-- Target: 已有PostgreSQL平台库；新库使用包含本迁移的datahub-center.sql。
-- Repeatable: 是；全部使用 IF EXISTS，重复执行无副作用。
-- Rollback: 失败整笔回滚；提交后审计记录不可恢复，如需保留请先备份 dh_modeling_planning_change。应用侧须同时升级到不再调用 /modeling/planning 的版本。
-- 删除前序必须是触发器→函数→表→序列，否则四张规划表的写入会因审计函数引用不存在的表而失败。
-- 保留 modeling_ownership_reference_guard、trg_modeling_layer_change 等引用校验触发器，不在本迁移范围内。
BEGIN;
SET LOCAL lock_timeout='10s';

DROP TRIGGER IF EXISTS trg_modeling_planning_audit ON "datahub-center".dh_modeling_biz_category;
DROP TRIGGER IF EXISTS trg_modeling_planning_audit ON "datahub-center".dh_modeling_data_domain;
DROP TRIGGER IF EXISTS trg_modeling_planning_audit ON "datahub-center".dh_modeling_subject_domain;
DROP TRIGGER IF EXISTS trg_modeling_planning_audit ON "datahub-center".dh_modeling_dw_layer;
DROP FUNCTION IF EXISTS "datahub-center".modeling_audit_planning_change();
DROP TABLE IF EXISTS "datahub-center".dh_modeling_planning_change;
DROP SEQUENCE IF EXISTS "datahub-center".dh_modeling_planning_change_seq;

COMMIT;
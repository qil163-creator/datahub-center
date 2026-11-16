-- Migration: 0294 质量任务实例无结论时通过率与评分允许为空
-- Purpose: 质量执行器在实例没有合格结论时（存在执行出错的规则、实例失败，或全部规则都被跳过、检测总数为 0）把
--          dh_governance_quality_task_instance.pass_rate 与 score 写为空，前端与报表也按“空表示无结论、显示为 -”读取；
--          但两列在基线中是 NOT NULL，收尾更新因非空约束失败，实例会一直停在“运行中”，任务也无法再次执行。
--          表行数波动检测（0293）首次执行没有历史基准、结果为“跳过”，会必然触发该问题。本迁移去掉两列的非空约束，
--          保留默认值 0，已有数据不变。
-- Depends on: 0293_governance_quality-row-count-fluctuation.sql（编号前驱）；dh_governance_quality_task_instance 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；DROP NOT NULL 对已可空的列无副作用，注释可重复执行。单事务执行；只改列元数据，不扫描、不重写表。
--             已经因该问题停在“运行中”的实例不会被本迁移自动收尾，需在实例监控中核查后按现有流程处置。
-- Rollback: 回退前须先把空值改回数值（例如 UPDATE ... SET pass_rate = 0 WHERE pass_rate IS NULL，score 同理），
--           再执行 ALTER COLUMN pass_rate SET NOT NULL、ALTER COLUMN score SET NOT NULL；回退后无结论的实例会重新无法收尾。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_governance_quality_task_instance
    ALTER COLUMN pass_rate DROP NOT NULL,
    ALTER COLUMN score DROP NOT NULL;

COMMENT ON COLUMN dh_governance_quality_task_instance.pass_rate IS '通过率（%）；实例没有合格结论（执行出错、失败或全部跳过）时为空';
COMMENT ON COLUMN dh_governance_quality_task_instance.score IS '质量评分；实例没有合格结论（执行出错、失败或全部跳过）时为空';

COMMIT;

-- Migration: 0172 规范建模业务表注释。
-- Purpose: 将概念模型、指标模型及部署尝试的表注释统一为简洁的“数据建模-业务对象”格式。
-- Depends on: 0171_modeling_concept-indicator-models.sql 已执行，三张目标表存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库使用同步后的全量基线。
-- Repeatable: 是；重复设置相同注释，不改变表结构或业务数据。
-- Rollback: 失败事务自动回滚；已提交后可执行文件末尾注明的回退语句恢复 0171 状态的注释，无不可逆操作。

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '30s';

COMMENT ON TABLE "datahub-center".dh_modeling_concept_model IS '数据建模-概念模型';
COMMENT ON TABLE "datahub-center".dh_modeling_indicator_model IS '数据建模-指标模型';
COMMENT ON TABLE "datahub-center".dh_modeling_deployment_attempt IS '数据建模-部署尝试';

COMMIT;

-- 回退至 0171 状态时，在一个事务中执行以下三条语句。
-- COMMENT ON TABLE "datahub-center".dh_modeling_concept_model IS '概念模型及实体关系设计，单租户业务对象';
-- COMMENT ON TABLE "datahub-center".dh_modeling_indicator_model IS '原子指标和派生指标的持久业务定义，不执行自由表达式';
-- COMMENT ON TABLE "datahub-center".dh_modeling_deployment_attempt IS '数据建模-冻结部署尝试、物理目标互斥与未知结果对账';

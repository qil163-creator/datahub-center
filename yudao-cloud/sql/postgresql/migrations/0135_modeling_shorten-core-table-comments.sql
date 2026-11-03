-- Migration: 0135 缩短建模核心表中文注释
-- Purpose: 将逻辑/物理模型四张核心表 COMMENT 统一为「数据建模--xxx」短注释，对齐业务域/数据域等表风格。
-- Depends on: 0134_modeling_drop-migration-evidence.sql（可与其它环境并行；仅改注释，无结构变更）
-- Target: schema "datahub-center"（PostgreSQL）
-- Repeatable: 是；COMMENT ON TABLE 可重复执行。
-- Rollback: 可手工改回旧注释（见下方说明），无自动回滚脚本。
--
-- 职责备选（不上表注释，仅说明）:
--   逻辑模型 = 设计主表；逻辑模型版本 = 发布快照；
--   物理模型 = 部署绑定；物理模型版本 = 部署候选/历史只读归档。
--
-- 旧注释对照（回滚参考）:
--   dh_modeling_logic_model          → 数据建模-唯一表模型设计，列表、字段表单及 ER 画布共用此定义
--   dh_modeling_logic_model_version  → 数据建模-统一设计版本，发布版本冻结业务定义与关系
--   dh_modeling_physical_model       → 数据建模-统一模型的部署绑定，原物理编号保持不变
--   dh_modeling_physical_model_version → 数据建模-部署候选及历史物理版本；历史独立草稿只读归档

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

COMMENT ON TABLE "datahub-center".dh_modeling_logic_model IS '数据建模--逻辑模型';
COMMENT ON TABLE "datahub-center".dh_modeling_logic_model_version IS '数据建模--逻辑模型版本';
COMMENT ON TABLE "datahub-center".dh_modeling_physical_model IS '数据建模--物理模型';
COMMENT ON TABLE "datahub-center".dh_modeling_physical_model_version IS '数据建模--物理模型版本';

COMMIT;
-- Migration: 0138 modeling logic model profile
-- Purpose: 逻辑模型补齐 qData 建表单头：完整表名大小写、标准登记、责任人、启用标记和备注。设计生命周期 status 保持 REVISING/PUBLISHED/DISCARDED，不复用为启用/禁用。
-- Depends on: 0137_modeling_logic-physical-biz-category.sql
-- Target: existing PostgreSQL database, schema datahub-center
-- Repeatable: yes; each column uses ADD COLUMN IF NOT EXISTS
-- Rollback: DROP the seven columns below. 已填写的标准、责任人和备注会丢失，且不能从设计快照恢复。

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS table_case int2 NOT NULL DEFAULT 1;
ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS document_type varchar(32);
ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS document_id int8;
ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS contact_user_id int8;
ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS contact_number varchar(32);
ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS enabled int2 NOT NULL DEFAULT 0;
ALTER TABLE dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS remark varchar(500);

COMMENT ON COLUMN dh_modeling_logic_model.table_case IS '完整表名大小写：1 大写，2 小写';
COMMENT ON COLUMN dh_modeling_logic_model.document_type IS '标准类型，对应标准文件登记的分类';
COMMENT ON COLUMN dh_modeling_logic_model.document_id IS '标准登记编号';
COMMENT ON COLUMN dh_modeling_logic_model.contact_user_id IS '责任人用户编号';
COMMENT ON COLUMN dh_modeling_logic_model.contact_number IS '责任人联系电话';
COMMENT ON COLUMN dh_modeling_logic_model.enabled IS '是否启用：0 禁用，1 启用。与设计状态 status 分开';
COMMENT ON COLUMN dh_modeling_logic_model.remark IS '备注';

COMMIT;

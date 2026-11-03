-- P0-2: 逻辑/物理模型业务分类归属
-- 权威落在 dh_modeling_logic_model.biz_category_id；物理表同步同列（与契约一致）
-- 不恢复 biz_theme；version 表不加独立列（快照写 bizCategoryId）

ALTER TABLE "datahub-center".dh_modeling_logic_model
    ADD COLUMN IF NOT EXISTS biz_category_id int8 NULL;
COMMENT ON COLUMN "datahub-center".dh_modeling_logic_model.biz_category_id IS '业务分类编号（权威归属；非 SOURCE 发布必填）';

CREATE INDEX IF NOT EXISTS idx_dh_modeling_logic_model_biz_category
    ON "datahub-center".dh_modeling_logic_model (tenant_id, biz_category_id)
    WHERE deleted = 0 AND biz_category_id IS NOT NULL;

ALTER TABLE "datahub-center".dh_modeling_physical_model
    ADD COLUMN IF NOT EXISTS biz_category_id int8 NULL;
COMMENT ON COLUMN "datahub-center".dh_modeling_physical_model.biz_category_id IS '业务分类编号（自逻辑设计同步）';

-- Migration: 0141 modeling drop legacy publish
-- Purpose: 删除旧「模型发布/部署」表、物理模型部署字段、旧菜单权限，并插入新物化发布菜单
-- Depends on: 0140_modeling_model-materialized.sql（新页验收通过后再执行）
-- Target: schema datahub-center；不要在生产库直接执行；执行前 LI 先备份
-- Repeatable: yes; DROP IF EXISTS / DELETE 匹配 0 行可重跑
-- Rollback: 不能恢复已删部署历史；需从备份还原

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

DROP TABLE IF EXISTS "datahub-center"."dh_modeling_deploy_record";
DROP SEQUENCE IF EXISTS "datahub-center"."dh_modeling_deploy_record_seq";

ALTER TABLE "datahub-center"."dh_modeling_physical_model"
    DROP COLUMN IF EXISTS "deploy_status";
ALTER TABLE "datahub-center"."dh_modeling_physical_model"
    DROP COLUMN IF EXISTS "deployed_version_id";
ALTER TABLE "datahub-center"."dh_modeling_physical_model"
    DROP COLUMN IF EXISTS "deploy_time";

DELETE FROM system_role_menu
WHERE menu_id IN (
    SELECT id FROM system_menu
    WHERE permission IN (
        'modeling:model:deploy',
        'modeling:model:deploy-reconcile',
        'modeling:model:deploy-plan'
    )
    OR (type = 2 AND status = 0 AND trim(both '/' from component) = 'modeling/modelpublish/index')
);

DELETE FROM system_menu
WHERE permission IN (
    'modeling:model:deploy',
    'modeling:model:deploy-reconcile',
    'modeling:model:deploy-plan'
)
OR (type = 2 AND trim(both '/' from component) = 'modeling/modelpublish/index');

DO $$
DECLARE
  parent_id bigint;
  menu_id bigint;
BEGIN
  SELECT id INTO parent_id FROM system_menu
   WHERE deleted = 0 AND type = 1
     AND (path IN ('modeling', '/modeling') OR name IN ('数据建模', '建模管理'))
   ORDER BY id LIMIT 1;
  IF parent_id IS NULL THEN
    RAISE NOTICE 'modeling parent menu not found; skip menu insert';
    RETURN;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM system_menu
     WHERE deleted = 0
       AND trim(both '/' from component) = 'modeling/model-materialized/index'
  ) THEN
    INSERT INTO system_menu (
      name, permission, type, sort, parent_id,
      path, icon, component, component_name, status,
      visible, keep_alive, always_show,
      creator, create_time, updater, update_time, deleted
    ) VALUES (
      '发布模型', '', 2, 50, parent_id,
      'model-materialized', 'lucide:rocket', 'modeling/model-materialized/index', 'ModelingModelMaterialized', 0,
      true, true, true,
      'admin', CURRENT_TIMESTAMP, 'admin', CURRENT_TIMESTAMP, 0
    ) RETURNING id INTO menu_id;

    INSERT INTO system_menu (
      name, permission, type, sort, parent_id,
      path, icon, component, status, visible,
      creator, create_time, updater, update_time, deleted
    ) VALUES
      ('物化查询', 'modeling:model-materialized:query', 3, 1, menu_id, '', '', '', 0, true, 'admin', CURRENT_TIMESTAMP, 'admin', CURRENT_TIMESTAMP, 0),
      ('物化发布', 'modeling:model-materialized:publish', 3, 2, menu_id, '', '', '', 0, true, 'admin', CURRENT_TIMESTAMP, 'admin', CURRENT_TIMESTAMP, 0);
  END IF;
END $$;

COMMIT;

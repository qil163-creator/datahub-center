-- Migration: 0143 modeling drop physical menu
-- Purpose: 清理物理模型菜单与权限；删除前后端物理模型代码后的配套。不 DROP 物理表（留给 0144）
-- Depends on: 0142_modeling_logic-model-column.sql
-- Target: schema datahub-center（不要对生产库直接执行；执行前 LI 先备份）
-- Repeatable: yes; DELETE 匹配 0 行可重跑
-- Rollback: 需从备份恢复 system_menu / system_role_menu

BEGIN;
SET LOCAL search_path TO "datahub-center", public;

-- 角色菜单：物理模型菜单及其按钮
DELETE FROM system_role_menu
WHERE menu_id IN (
    SELECT id FROM system_menu
    WHERE deleted = 0
      AND (
        trim(both '/' from coalesce(component, '')) = 'modeling/physicalmodel/index'
        OR path IN ('physical-model', '/modeling/physical-model')
        OR component_name = 'ModelingPhysicalModel'
        OR (type = 2 AND name = '物理模型' AND path = 'physical-model')
      )
);

-- 子权限（挂在物理模型菜单下的按钮）先删子再删父
DELETE FROM system_menu
WHERE parent_id IN (
    SELECT id FROM system_menu
    WHERE deleted = 0
      AND (
        trim(both '/' from coalesce(component, '')) = 'modeling/physicalmodel/index'
        OR component_name = 'ModelingPhysicalModel'
        OR (type = 2 AND name = '物理模型' AND path = 'physical-model')
      )
);

DELETE FROM system_menu
WHERE deleted = 0
  AND (
    trim(both '/' from coalesce(component, '')) = 'modeling/physicalmodel/index'
    OR path IN ('physical-model')
    OR component_name = 'ModelingPhysicalModel'
    OR (type = 2 AND name = '物理模型' AND path = 'physical-model')
  );

-- 不 DROP dh_modeling_physical_model / dh_modeling_physical_model_version（P3/0144）

COMMIT;

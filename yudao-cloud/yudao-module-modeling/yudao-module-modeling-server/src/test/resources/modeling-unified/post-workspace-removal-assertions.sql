-- 仅验证历史 0126 完成、尚未执行 0127 的只读结构，不适用于当前新库基线。
-- 引用回执退役断言保留为 0126 的历史契约；当前引用控制清理由后续迁移验证。
BEGIN;
SET LOCAL search_path="datahub-center",pg_catalog;
DO $assertions$
DECLARE old_table text;
BEGIN
 FOREACH old_table IN ARRAY ARRAY['dh_modeling_collaboration','dh_modeling_collaborator','dh_modeling_approval',
  'dh_modeling_design_report','dh_modeling_inbox','dh_modeling_object_change','dh_modeling_reference_receipt'] LOOP
  IF to_regclass(old_table) IS NOT NULL THEN RAISE EXCEPTION '退役表仍存在：%',old_table; END IF;
 END LOOP;
 IF NOT EXISTS(SELECT 1 FROM dh_modeling_unification_state WHERE migration_key='WORKSPACE_REMOVAL_V1' AND phase='CLEANED') THEN
  RAISE EXCEPTION '退役账本未完成';
 END IF;
 IF to_regclass('dh_modeling_unification_map') IS NULL OR to_regclass('dh_modeling_workspace_removal_archive') IS NULL
    OR to_regclass('dh_modeling_business_object_external_ref') IS NULL THEN RAISE EXCEPTION '迁移证据或实际引用丢失'; END IF;
 IF EXISTS(SELECT 1 FROM system_menu WHERE permission IN ('modeling:workspace:approval','modeling:workspace:collaboration','modeling:workspace:inbox')) THEN
  RAISE EXCEPTION '退役权限残留'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid='dh_modeling_logic_model'::regclass AND attname='layout_revision'
    AND atttypid='bigint'::regtype AND attnotnull AND atthasdef AND NOT attisdropped) THEN RAISE EXCEPTION '布局修订列缺失'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_index WHERE indexrelid='uq_modeling_active_deploy_target'::regclass AND indisunique AND indisvalid) THEN
  RAISE EXCEPTION '活动目标唯一索引缺失'; END IF;
 IF EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='modeling_approval_freeze' AND NOT tgisinternal
    AND tgrelid IN ('dh_modeling_logic_model'::regclass,'dh_modeling_physical_model'::regclass)) THEN
  RAISE EXCEPTION '旧审批冻结残留'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='modeling_published_design_guard' AND tgrelid='dh_modeling_logic_model_version'::regclass)
    OR NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='modeling_unified_version_guard' AND tgrelid='dh_modeling_physical_model_version'::regclass) THEN
  RAISE EXCEPTION '设计或部署快照不可变保护丢失'; END IF;
 IF to_regprocedure('dh_modeling_confirm_unification(bigint,bigint,jsonb)') IS NOT NULL
    OR to_regprocedure('dh_modeling_unification_acl(bigint,text,bigint,text)') IS NOT NULL THEN
  RAISE EXCEPTION '已结束审查入口残留'; END IF;
END $assertions$;
ROLLBACK;

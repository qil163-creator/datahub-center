SET search_path="datahub-center",pg_catalog;
-- 仅在专用测试副本构造明确有效链接与受限授权，不作为生产数据修复。
UPDATE dh_modeling_physical_model SET logic_model_id=3 WHERE id=1;
INSERT INTO dh_modeling_collaboration(tenant_id,kind,model_id,owner_user_id,visibility,approval_required,process_definition_key)
VALUES(1,'PHYSICAL',2,100,'RESTRICTED',true,'fixture_existing_process');
INSERT INTO dh_modeling_collaborator(tenant_id,kind,model_id,principal_type,principal_id,actions)
VALUES(1,'PHYSICAL',2,'USER',200,'READ'),(1,'PHYSICAL',2,'DEPT',300,'EDIT,DEPLOY');

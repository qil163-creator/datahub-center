SET search_path="datahub-center",pg_catalog;
DO $check$
DECLARE p record; l record;
BEGIN
 SELECT * INTO p FROM dh_modeling_physical_model WHERE id=2;
 SELECT lm.* INTO l FROM dh_modeling_logic_model lm JOIN dh_modeling_unification_map mp ON mp.logic_model_id=lm.id WHERE mp.physical_model_id=2;
 IF dh_modeling_unification_acl(1,'PHYSICAL',2,p.creator) IS DISTINCT FROM dh_modeling_unification_acl(1,'LOGIC',l.id,l.creator) THEN RAISE EXCEPTION 'ACL继承不等价'; END IF;
 IF l.status<>'REVISING' THEN RAISE EXCEPTION '新逻辑草稿被错误发布'; END IF;
 IF (SELECT count(*) FROM dh_modeling_logic_model WHERE deleted=0)<>4 THEN RAISE EXCEPTION '重放产生重复设计'; END IF;
END $check$;
SELECT dh_modeling_confirm_unification(tenant_id,physical_model_id,jsonb_build_object(
'decision','KEEP_LOGIC_ARCHIVE_PHYSICAL','aclDecision','KEEP_INDEPENDENT_ACL',
'reviewer','隔离测试审查人','reason','仅用于隔离验证，确认保留逻辑设计并归档历史差异',
'physicalDigest',md5(original_head::text||original_versions::text),
'designDigest',md5(canonical_head::text||canonical_versions::text))) FROM dh_modeling_unification_map;

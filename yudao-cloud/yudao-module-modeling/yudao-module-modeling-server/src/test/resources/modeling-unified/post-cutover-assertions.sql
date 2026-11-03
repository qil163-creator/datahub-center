BEGIN;
SET LOCAL search_path="datahub-center",pg_catalog;
DO $check$
DECLARE mp record; actual jsonb; rejected boolean; source_before bigint;
BEGIN
 IF (SELECT count(*) FROM dh_modeling_physical_model)<>3 OR (SELECT count(*) FROM dh_modeling_physical_model_version)<>4 THEN RAISE EXCEPTION '物理历史记录数量被改变'; END IF;
 FOR mp IN SELECT * FROM dh_modeling_unification_map LOOP
   SELECT jsonb_agg(to_jsonb(v) ORDER BY v.id) INTO actual FROM dh_modeling_physical_model_version v WHERE v.model_id=mp.physical_model_id AND v.tenant_id=mp.tenant_id;
   IF actual IS DISTINCT FROM mp.original_versions THEN RAISE EXCEPTION '物理历史证据被改变'; END IF;
 END LOOP;
 IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_modeling_physical_model'
   AND column_name IN ('name','code','description','layer_id','data_domain_id')) THEN RAISE EXCEPTION '重复业务列未删除'; END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_modeling_physical_model' AND column_name='source_logic_version_id') THEN RAISE EXCEPTION '初始来源证据被删除'; END IF;
 rejected:=false;
 BEGIN UPDATE dh_modeling_physical_model_version SET fields='[]' WHERE id=1;
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%只读归档%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '历史发布快照可篡改'; END IF;
 rejected:=false;
 BEGIN UPDATE dh_modeling_physical_model_version SET fields='[{"fieldName":"fixture"}]' WHERE id=2;
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%只读归档%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '历史独立草稿仍可写'; END IF;
 rejected:=false;
 BEGIN INSERT INTO dh_modeling_physical_model_version(model_id,version,status,fields,tenant_id) VALUES(2,2,'REVISING','[]',1);
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%精确统一设计版本%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '新物理候选允许缺来源'; END IF;
 rejected:=false;
 BEGIN INSERT INTO dh_modeling_logic_relation(source_model_id,target_model_id,source_field,target_field,tenant_id,field_pairs)
  VALUES(3,5,'id','id',2,'[{"sourceFieldUid":"fixture1","targetFieldUid":"fixture2"}]');
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%同租户%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '跨租户关系未拒绝'; END IF;
 rejected:=false;
 BEGIN INSERT INTO dh_modeling_logic_model(name,code,tenant_id) VALUES('学生','fixture_duplicate_name',1);
 EXCEPTION WHEN unique_violation THEN rejected:=true; END;
 IF NOT rejected THEN RAISE EXCEPTION '模型名称唯一约束未生效'; END IF;
 -- 删除没有引用的数据域，验证原动态规划守卫不再访问已移除的物理列；事务随后回滚。
 DELETE FROM dh_modeling_data_domain WHERE id=22;
END $check$;
ROLLBACK;

BEGIN;
SET LOCAL search_path="datahub-center",pg_catalog;
DO $check$
DECLARE rejected boolean;
BEGIN
 rejected:=false;
 BEGIN UPDATE dh_modeling_physical_model SET logic_model_id=5 WHERE id=1;
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%身份%不可修改%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '部署绑定可被重新挂载到其他设计'; END IF;
 rejected:=false;
 BEGIN UPDATE dh_modeling_logic_model_version SET fields='[]' WHERE id=6;
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%已发布统一设计内容不可修改%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '发布设计快照可篡改'; END IF;
 rejected:=false;
 BEGIN UPDATE dh_modeling_unification_map SET original_head='{}' WHERE physical_model_id=1;
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%备份不可修改%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '迁移原始证据可篡改'; END IF;
 rejected:=false;
 BEGIN UPDATE dh_modeling_unification_map SET review_status='PENDING' WHERE physical_model_id=1;
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%审查证据不可修改%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '确认后状态可重置'; END IF;
 rejected:=false;
 BEGIN DELETE FROM dh_modeling_unification_map WHERE physical_model_id=1;
 EXCEPTION WHEN raise_exception THEN rejected:=SQLERRM LIKE '%证据不可删除%'; END;
 IF NOT rejected THEN RAISE EXCEPTION '迁移原始证据可删'; END IF;
END $check$;
ROLLBACK;
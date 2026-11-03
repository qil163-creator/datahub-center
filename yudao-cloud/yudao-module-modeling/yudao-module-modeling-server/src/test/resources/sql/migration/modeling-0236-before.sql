-- 仅供隔离 PostgreSQL 迁移验证：构造当前定义、已移除字段、历史部署及稳定字段引用，不执行外部 DDL。
BEGIN;
SET search_path TO "datahub-center", pg_catalog;
CREATE TABLE public.verify_modeling_0236 AS
SELECT m.id AS model_id, v.id AS version_id, m.entity_id, m.data_domain_id, m.subject_domain_id,
    m.data_timeliness, (SELECT field_uid FROM dh_modeling_logic_model_column c
        WHERE c.model_id=m.id AND c.deleted=0 ORDER BY c.id LIMIT 1) AS field_uid,
    (SELECT field_uid FROM dh_modeling_logic_model_column c
        WHERE c.model_id=m.id AND c.deleted=0 ORDER BY c.id DESC LIMIT 1) AS missing_uid
FROM dh_modeling_logic_model m JOIN dh_modeling_logic_model_version v
    ON v.model_id=m.id AND v.version=m.latest_version AND v.deleted=0
JOIN dh_modeling_business_entity e ON e.id=m.entity_id AND e.deleted=0 AND e.status=0
WHERE m.deleted=0 AND m.table_type IN ('DIMENSION','DETAIL') AND m.data_timeliness IN ('1','2','3')
    AND m.data_domain_id=e.data_domain_id AND m.subject_domain_id=e.subject_domain_id
    AND (SELECT count(*) FROM dh_modeling_logic_model_column c WHERE c.model_id=m.id AND c.deleted=0)>1
ORDER BY m.id LIMIT 1;
DO $fixture$
BEGIN
    IF (SELECT count(*) FROM public.verify_modeling_0236)<>1 THEN RAISE EXCEPTION '缺少可用测试模型'; END IF;
END $fixture$;
UPDATE dh_modeling_logic_model m SET status='PUBLISHED',enabled=1,revision=7
FROM public.verify_modeling_0236 c WHERE m.id=c.model_id;
UPDATE dh_modeling_logic_model_version v SET status='PUBLISHED',
    type_config='{"dimension":{"fixture":"current"}}',published_by=777,published_time='2026-01-02 03:04:05',
    model_snapshot=jsonb_build_object('schemaVersion',1,'modelId',m.id,'entityId',m.entity_id,
        'dataDomainId',m.data_domain_id,'subjectDomainId',m.subject_domain_id,
        'dataTimeliness',m.data_timeliness,'tableType',m.table_type)
FROM public.verify_modeling_0236 c JOIN dh_modeling_logic_model m ON m.id=c.model_id WHERE v.id=c.version_id;
-- 不被模型指针采用的其他快照不能覆盖当前口径；迁移不能用最大版本号取代明确指针。
INSERT INTO dh_modeling_logic_model_version(model_id,version,status,fields,type_config)
SELECT model_id,101,'REVISING','[]','{"wrong":"not-current"}' FROM public.verify_modeling_0236;
UPDATE dh_modeling_logic_model_column f SET cn_name='当前字段名称不得被旧快照覆盖'
FROM public.verify_modeling_0236 c WHERE f.model_id=c.model_id AND f.field_uid=c.field_uid AND f.deleted=0;
ALTER TABLE public.verify_modeling_0236 ADD COLUMN field_before jsonb;
UPDATE public.verify_modeling_0236 c SET field_before=(SELECT to_jsonb(f) FROM dh_modeling_logic_model_column f
    WHERE f.model_id=c.model_id AND f.field_uid=c.field_uid AND f.deleted=0);
DELETE FROM dh_modeling_logic_model_column f USING public.verify_modeling_0236 c
WHERE f.model_id=c.model_id AND f.field_uid=c.missing_uid;

INSERT INTO dh_modeling_indicator_model(id,code,name,indicator_type,data_domain_id,subject_domain_id,
    entity_id,data_freshness,source_model_id,source_version_id,value_field_uid,aggregation,status)
SELECT -236001,'FIXTURE_CURRENT_ATOMIC','迁移原子指标','ATOMIC',data_domain_id,subject_domain_id,
    entity_id,data_timeliness,model_id,version_id,field_uid,'COUNT','ACTIVE' FROM public.verify_modeling_0236;
INSERT INTO dh_modeling_business_condition(id,code,name,data_domain_id,subject_domain_id,entity_id,
    data_freshness,source_model_id,source_version_id,expression,status)
SELECT -236001,'FIXTURE_CURRENT_CONDITION','迁移业务限定',data_domain_id,subject_domain_id,entity_id,
    data_timeliness,model_id,version_id,'1=1','ACTIVE' FROM public.verify_modeling_0236;
INSERT INTO dh_modeling_model_materialized(id,model_id,datasource_id,publish_mode,status,version_id,attempt_id,
    create_time,update_time,creator,updater)
SELECT -236001,model_id,-236001,'REBUILD','UNKNOWN',version_id,-236001,
    timestamp '2026-01-03 04:05:06',timestamp '2026-01-03 04:05:06','fixture-original','fixture-original' FROM public.verify_modeling_0236
UNION ALL SELECT -236002,model_id,-236002,'INCREMENT','SUCCESS',version_id,NULL,
    timestamp '2026-01-03 04:05:06',timestamp '2026-01-03 04:05:06','fixture-original','fixture-original' FROM public.verify_modeling_0236;
INSERT INTO dh_modeling_deployment_attempt(id,materialized_id,version_id,datasource_id,target_key,
    schema_name,table_name,configuration_revision,publish_mode,status,phase,lease_owner,lease_until,
    before_observation_json,ddl_sql,fields_json,model_snapshot_json,creator,create_time,updater,update_time)
SELECT -236001,-236001,version_id,-236001,'fixture-physical-target','fixture','frozen_table','fixture-connection',
    'REBUILD','UNKNOWN','EXECUTING','fixture-lease','2026-01-03 04:05:06','{"tableExists":false}',
    'SELECT 1','[{"fieldUid":"frozen-input","fieldName":"frozen_column","dataType":"VARCHAR"}]',
    jsonb_build_object('modelId',model_id,'code','FROZEN_NAME','name','原始任务输入','originalEvidence',true),
    'fixture-original','2026-01-03 04:05:06','fixture-original','2026-01-03 04:05:06'
FROM public.verify_modeling_0236;
ALTER TABLE public.verify_modeling_0236 ADD COLUMN original_attempt jsonb;
ALTER TABLE public.verify_modeling_0236 ADD COLUMN original_materialized jsonb;
UPDATE public.verify_modeling_0236 SET original_attempt=(SELECT to_jsonb(a) FROM dh_modeling_deployment_attempt a WHERE id=-236001),
    original_materialized=(SELECT to_jsonb(m) FROM dh_modeling_model_materialized m WHERE id=-236002);

-- 已软删除模型的对应版本也已软删除，仍须迁入其精确口径，不复活字段。
INSERT INTO dh_modeling_logic_model(id,name,code,status,deleted) VALUES(-236001,'已删除测试模型','FIXTURE_DELETED','REVISING',1);
INSERT INTO dh_modeling_logic_model_version(model_id,version,status,fields,type_config,deleted)
VALUES(-236001,1,'REVISING','[]','{"deleted":"preserved"}',1);
COMMIT;

-- Migration: 0191 标准数据元与代码表工作台。
-- Purpose: 增加共享标准属性、数据元规则实例、原始码值映射、发布冻结快照及引用并发约束；移动现有菜单到数据建模。
-- Depends on: 0190_governance_quality-rule-catalog.sql；标准、码值、分类及菜单基础表已就绪。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；结构按存在性补齐，函数与触发器替换，菜单保留已有身份和授权。
-- Rollback: 失败时事务回滚；提交后保留新表和快照数据，可将菜单移回旧目录。删除新增列或表会丢失新配置，须从备份恢复。
-- 风险：新增唯一索引会拒绝已有重复名称，短暂结构锁期间暂停标准编辑。草稿配置不会写回模板或既有发布快照。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
DO $types$
DECLARE item record;
BEGIN
 FOR item IN SELECT * FROM (VALUES ('category_id','bigint'),('document_id','bigint'),('person_charge','bigint'),('description','character varying(256)')) AS columns(name,type_name) LOOP
  IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='dh_governance_standard_element'::regclass AND attname=item.name AND format_type(atttypid,atttypmod)<>item.type_name) THEN
   EXECUTE format('ALTER TABLE dh_governance_standard_element ALTER COLUMN %I TYPE %s',item.name,item.type_name);
  END IF;
 END LOOP;
END $types$;
ALTER TABLE dh_governance_standard_element ADD COLUMN IF NOT EXISTS standard_type varchar(64);
ALTER TABLE dh_governance_standard_element ADD COLUMN IF NOT EXISTS contact_number varchar(64);
ALTER TABLE dh_governance_standard_element ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0;
ALTER TABLE dh_governance_standard_element_version ADD COLUMN IF NOT EXISTS rule_bindings_snapshot jsonb;
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS category_id bigint;
COMMENT ON COLUMN dh_governance_standard_code_set.category_id IS '共享标准分类编号';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS column_type varchar(64);
COMMENT ON COLUMN dh_governance_standard_code_set.column_type IS '字段类型';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS data_length integer;
COMMENT ON COLUMN dh_governance_standard_code_set.data_length IS '数据长度';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS data_scale integer;
COMMENT ON COLUMN dh_governance_standard_code_set.data_scale IS '小数位数';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS standard_type varchar(64);
COMMENT ON COLUMN dh_governance_standard_code_set.standard_type IS '标准类型';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS document_id bigint;
COMMENT ON COLUMN dh_governance_standard_code_set.document_id IS '标准文件编号';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS person_charge bigint;
COMMENT ON COLUMN dh_governance_standard_code_set.person_charge IS '负责人编号';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS contact_number varchar(64);
COMMENT ON COLUMN dh_governance_standard_code_set.contact_number IS '负责人联系电话';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS description varchar(256);
COMMENT ON COLUMN dh_governance_standard_code_set.description IS '代码表描述';
ALTER TABLE dh_governance_standard_code_set ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0;
COMMENT ON COLUMN dh_governance_standard_code_set.revision IS '编辑并发版本';
COMMENT ON COLUMN dh_governance_standard_element.category_id IS '共享标准分类编号';
COMMENT ON COLUMN dh_governance_standard_element.document_id IS '标准文件编号';
COMMENT ON COLUMN dh_governance_standard_element.person_charge IS '负责人编号';
COMMENT ON COLUMN dh_governance_standard_element.description IS '数据元描述';
COMMENT ON COLUMN dh_governance_standard_element.standard_type IS '标准类型';
COMMENT ON COLUMN dh_governance_standard_element.contact_number IS '负责人联系电话';
COMMENT ON COLUMN dh_governance_standard_element.revision IS '编辑并发版本';
COMMENT ON COLUMN dh_governance_standard_element_version.rule_bindings_snapshot IS '发布时冻结的清洗与稽查规则实例，不随草稿更新';
CREATE UNIQUE INDEX IF NOT EXISTS uk_standard_element_live_name ON dh_governance_standard_element(name) WHERE deleted = 0 AND name IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_standard_element_live_eng_name ON dh_governance_standard_element(eng_name) WHERE deleted = 0 AND eng_name IS NOT NULL;

CREATE SEQUENCE IF NOT EXISTS dh_governance_standard_element_rule_seq;
CREATE TABLE IF NOT EXISTS dh_governance_standard_element_rule (
 id bigint PRIMARY KEY DEFAULT nextval('dh_governance_standard_element_rule_seq'),
 element_id integer NOT NULL REFERENCES dh_governance_standard_element(id),
 kind varchar(8) NOT NULL CHECK (kind IN ('CLEAN','AUDIT')),
 name varchar(100) NOT NULL, template_id bigint NOT NULL,
 status smallint NOT NULL DEFAULT 0 CHECK (status IN (0,1)),
 description varchar(256), where_condition varchar(256), config_json jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(config_json) = 'object'),
 severity varchar(8) CHECK (severity IS NULL OR severity IN ('HIGH','MID','LOW')),
 error_example varchar(256), repair_suggestion varchar(256), revision bigint NOT NULL DEFAULT 0,
 creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_standard_element_rule_live_name ON dh_governance_standard_element_rule(element_id,kind,name) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_standard_element_rule_owner ON dh_governance_standard_element_rule(element_id,kind) WHERE deleted = 0;
CREATE SEQUENCE IF NOT EXISTS dh_governance_standard_code_value_mapping_seq;
CREATE TABLE IF NOT EXISTS dh_governance_standard_code_value_mapping (
 id bigint PRIMARY KEY DEFAULT nextval('dh_governance_standard_code_value_mapping_seq'),
 code_set_id integer NOT NULL REFERENCES dh_governance_standard_code_set(id),
 original_value varchar(100) NOT NULL, code_item_id integer NOT NULL REFERENCES dh_governance_standard_code_item(id),
 revision bigint NOT NULL DEFAULT 0,
 creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_standard_code_value_mapping_live ON dh_governance_standard_code_value_mapping(code_set_id,original_value) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_standard_code_value_mapping_item ON dh_governance_standard_code_value_mapping(code_item_id) WHERE deleted = 0;
COMMENT ON TABLE dh_governance_standard_element_rule IS '数据元独立规则实例';
COMMENT ON COLUMN dh_governance_standard_element_rule.id IS '规则实例编号';
COMMENT ON COLUMN dh_governance_standard_element_rule.element_id IS '所属数据元编号';
COMMENT ON COLUMN dh_governance_standard_element_rule.kind IS '清洗或稽查类型';
COMMENT ON COLUMN dh_governance_standard_element_rule.name IS '规则实例名称';
COMMENT ON COLUMN dh_governance_standard_element_rule.template_id IS '对应类型的模板编号';
COMMENT ON COLUMN dh_governance_standard_element_rule.status IS '启用状态：0启用1停用';
COMMENT ON COLUMN dh_governance_standard_element_rule.description IS '规则描述';
COMMENT ON COLUMN dh_governance_standard_element_rule.where_condition IS '可复用过滤条件，执行绑定时再次验证';
COMMENT ON COLUMN dh_governance_standard_element_rule.config_json IS '实例规则参数JSON对象';
COMMENT ON COLUMN dh_governance_standard_element_rule.severity IS '严重程度';
COMMENT ON COLUMN dh_governance_standard_element_rule.error_example IS '错误示例';
COMMENT ON COLUMN dh_governance_standard_element_rule.repair_suggestion IS '修复建议';
COMMENT ON COLUMN dh_governance_standard_element_rule.revision IS '编辑并发版本';
COMMENT ON COLUMN dh_governance_standard_element_rule.creator IS '创建者';
COMMENT ON COLUMN dh_governance_standard_element_rule.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_standard_element_rule.updater IS '更新者';
COMMENT ON COLUMN dh_governance_standard_element_rule.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_standard_element_rule.deleted IS '逻辑删除：0有效1删除';
COMMENT ON TABLE dh_governance_standard_code_value_mapping IS '代码表原始值映射';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.id IS '映射编号';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.code_set_id IS '所属代码表编号';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.original_value IS '原始值';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.code_item_id IS '所属代码表内的目标码值编号';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.revision IS '编辑并发版本';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.creator IS '创建者';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.create_time IS '创建时间';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.updater IS '更新者';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.update_time IS '更新时间';
COMMENT ON COLUMN dh_governance_standard_code_value_mapping.deleted IS '逻辑删除：0有效1删除';

-- 子配置写入先锁父对象，父对象删除与配置写入使用同一行锁边界。
CREATE OR REPLACE FUNCTION standard_workbench_child_guard() RETURNS trigger LANGUAGE plpgsql AS $guard$
BEGIN
 IF NEW.deleted <> 0 THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME = 'dh_governance_standard_element_rule' THEN
  PERFORM 1 FROM dh_governance_standard_element WHERE id = NEW.element_id AND deleted = 0 AND lifecycle_status NOT IN ('REVIEWING','DEPRECATED') FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '数据元不存在或当前生命周期不可编辑'; END IF;
  IF NEW.kind = 'AUDIT' THEN
   PERFORM 1 FROM dh_governance_audit_rule WHERE id = NEW.template_id AND deleted = 0;
  ELSE
   PERFORM 1 FROM dh_governance_clean_rule WHERE id = NEW.template_id AND deleted = 0;
  END IF;
  IF NOT FOUND THEN RAISE EXCEPTION '关联规则模板不存在'; END IF;
 ELSE
  PERFORM 1 FROM dh_governance_standard_code_set WHERE id = NEW.code_set_id AND deleted = 0 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '代码表不存在'; END IF;
  PERFORM 1 FROM dh_governance_standard_code_item WHERE id = NEW.code_item_id AND code_set_id = NEW.code_set_id AND deleted = 0 AND status = 0;
  IF NOT FOUND THEN RAISE EXCEPTION '映射码值不属于该代码表或已停用'; END IF;
 END IF;
 RETURN NEW;
END $guard$;
DROP TRIGGER IF EXISTS standard_element_rule_guard ON dh_governance_standard_element_rule;
CREATE TRIGGER standard_element_rule_guard BEFORE INSERT OR UPDATE ON dh_governance_standard_element_rule FOR EACH ROW EXECUTE FUNCTION standard_workbench_child_guard();
DROP TRIGGER IF EXISTS standard_code_value_mapping_guard ON dh_governance_standard_code_value_mapping;
CREATE TRIGGER standard_code_value_mapping_guard BEFORE INSERT OR UPDATE ON dh_governance_standard_code_value_mapping FOR EACH ROW EXECUTE FUNCTION standard_workbench_child_guard();

-- 新引用验证在父行锁内完成，解决存在性检查与软删除之间的竞态。
CREATE OR REPLACE FUNCTION standard_workbench_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $reference$
BEGIN
 IF NEW.deleted <> 0 THEN RETURN NEW; END IF;
 IF NEW.category_id IS NOT NULL THEN
  PERFORM 1 FROM dh_governance_category_datameta WHERE id = NEW.category_id AND deleted = 0 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '共享标准分类不存在'; END IF;
 END IF;
 IF NEW.document_id IS NOT NULL THEN
  PERFORM 1 FROM dh_governance_standard_doc WHERE id = NEW.document_id AND deleted = 0 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '标准文件不存在'; END IF;
 END IF;
 IF TG_TABLE_NAME = 'dh_governance_standard_element' THEN
 IF NEW.code_set_id IS NOT NULL THEN
  PERFORM 1 FROM dh_governance_standard_code_set WHERE id = NEW.code_set_id AND deleted = 0 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION '关联代码表不存在'; END IF;
 END IF;
 END IF;
 RETURN NEW;
END $reference$;
DROP TRIGGER IF EXISTS standard_element_reference_guard ON dh_governance_standard_element;
CREATE TRIGGER standard_element_reference_guard BEFORE INSERT OR UPDATE OF category_id,document_id,code_set_id,deleted ON dh_governance_standard_element FOR EACH ROW EXECUTE FUNCTION standard_workbench_reference_guard();
DROP TRIGGER IF EXISTS standard_code_set_reference_guard ON dh_governance_standard_code_set;
CREATE TRIGGER standard_code_set_reference_guard BEFORE INSERT OR UPDATE OF category_id,document_id,deleted ON dh_governance_standard_code_set FOR EACH ROW EXECUTE FUNCTION standard_workbench_reference_guard();

CREATE OR REPLACE FUNCTION standard_workbench_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $delete$
BEGIN
 IF NEW.deleted = 0 OR OLD.deleted <> 0 THEN RETURN NEW; END IF;
 IF TG_TABLE_NAME = 'dh_governance_standard_code_item' THEN
  IF EXISTS (SELECT 1 FROM dh_governance_standard_code_value_mapping WHERE code_item_id = OLD.id AND deleted = 0) THEN RAISE EXCEPTION '码值仍被原始值映射引用'; END IF;
 ELSIF TG_TABLE_NAME = 'dh_governance_standard_code_set' THEN
  IF EXISTS (SELECT 1 FROM dh_governance_standard_element WHERE code_set_id = OLD.id AND deleted = 0)
   OR EXISTS (SELECT 1 FROM dh_governance_standard_code_value_mapping WHERE code_set_id = OLD.id AND deleted = 0) THEN RAISE EXCEPTION '代码表仍被引用'; END IF;
 ELSIF TG_TABLE_NAME = 'dh_governance_standard_element' THEN
  IF EXISTS (SELECT 1 FROM dh_governance_standard_element_rule WHERE element_id = OLD.id AND deleted = 0) THEN RAISE EXCEPTION '数据元仍有关联规则实例'; END IF;
 ELSIF TG_TABLE_NAME = 'dh_governance_category_datameta' THEN
  IF EXISTS (SELECT 1 FROM dh_governance_standard_element WHERE category_id = OLD.id AND deleted = 0)
   OR EXISTS (SELECT 1 FROM dh_governance_standard_code_set WHERE category_id = OLD.id AND deleted = 0) THEN RAISE EXCEPTION '分类仍被标准引用'; END IF;
 ELSIF TG_TABLE_NAME = 'dh_governance_standard_doc' THEN
  IF EXISTS (SELECT 1 FROM dh_governance_standard_element WHERE document_id = OLD.id AND deleted = 0)
   OR EXISTS (SELECT 1 FROM dh_governance_standard_code_set WHERE document_id = OLD.id AND deleted = 0) THEN RAISE EXCEPTION '标准文件仍被引用'; END IF;
 END IF;
 RETURN NEW;
END $delete$;
DROP TRIGGER IF EXISTS workbench_delete_guard ON dh_governance_standard_code_item;
CREATE TRIGGER workbench_delete_guard BEFORE UPDATE OF deleted ON dh_governance_standard_code_item FOR EACH ROW EXECUTE FUNCTION standard_workbench_delete_guard();
DROP TRIGGER IF EXISTS workbench_delete_guard ON dh_governance_standard_code_set;
CREATE TRIGGER workbench_delete_guard BEFORE UPDATE OF deleted ON dh_governance_standard_code_set FOR EACH ROW EXECUTE FUNCTION standard_workbench_delete_guard();
DROP TRIGGER IF EXISTS workbench_delete_guard ON dh_governance_standard_element;
CREATE TRIGGER workbench_delete_guard BEFORE UPDATE OF deleted ON dh_governance_standard_element FOR EACH ROW EXECUTE FUNCTION standard_workbench_delete_guard();
DROP TRIGGER IF EXISTS workbench_delete_guard ON dh_governance_category_datameta;
CREATE TRIGGER workbench_delete_guard BEFORE UPDATE OF deleted ON dh_governance_category_datameta FOR EACH ROW EXECUTE FUNCTION standard_workbench_delete_guard();
DROP TRIGGER IF EXISTS workbench_delete_guard ON dh_governance_standard_doc;
CREATE TRIGGER workbench_delete_guard BEFORE UPDATE OF deleted ON dh_governance_standard_doc FOR EACH ROW EXECUTE FUNCTION standard_workbench_delete_guard();

-- 所有标准父对象变更都递增编辑版本，包括原有绑定及生命周期专用命令。
CREATE OR REPLACE FUNCTION standard_workbench_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $revision$
BEGIN
 NEW.revision := OLD.revision + 1;
 RETURN NEW;
END $revision$;
DROP TRIGGER IF EXISTS workbench_revision_guard ON dh_governance_standard_element;
CREATE TRIGGER workbench_revision_guard BEFORE UPDATE ON dh_governance_standard_element FOR EACH ROW EXECUTE FUNCTION standard_workbench_revision_guard();
DROP TRIGGER IF EXISTS workbench_revision_guard ON dh_governance_standard_code_set;
CREATE TRIGGER workbench_revision_guard BEFORE UPDATE ON dh_governance_standard_code_set FOR EACH ROW EXECUTE FUNCTION standard_workbench_revision_guard();

-- 将已实现且源站数据元启用的两个模板接入现有注册表，不改变规则编号或配置。
DO $templates$
DECLARE item record; matches integer;
BEGIN
 FOR item IN SELECT * FROM (VALUES ('112','FOREIGN_KEY_VALIDATION'),('118','MUTUAL_EXCLUSION_VALIDATION')) AS templates(code,strategy_key) LOOP
  SELECT count(*) INTO matches FROM dh_governance_audit_rule WHERE code=item.code AND deleted=0;
  IF matches<>1 OR EXISTS (SELECT 1 FROM dh_governance_audit_rule WHERE code=item.code AND deleted=0 AND strategy_key IS NOT NULL AND strategy_key<>item.strategy_key) THEN
   RAISE EXCEPTION '0191 稽查规则模板归属冲突: %',item.code;
  END IF;
  UPDATE dh_governance_audit_rule SET strategy_key=item.strategy_key,status=0,updater='migration:0191' WHERE code=item.code AND deleted=0;
 END LOOP;
END $templates$;

-- 移动既有页面和其权限子节点，保留页面ID及已有角色授权。
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE folder_id bigint; menu_high bigint; role_high bigint; inserted integer; matches integer;
BEGIN
 IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6126 AND path = '/model' AND deleted = 0)
 OR NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6209 AND component = 'governance/standardelement/index' AND deleted = 0)
 OR NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 6203 AND component = 'governance/standardcodeset/index' AND deleted = 0) THEN
  RAISE EXCEPTION '0191 标准菜单前置状态缺失';
 END IF;
 SELECT count(*), min(id) INTO matches, folder_id FROM system_menu WHERE parent_id = 6126 AND path = 'standard-data-element' AND deleted = 0;
 IF matches > 1 THEN RAISE EXCEPTION '0191 标准目录菜单冲突'; END IF;
 IF matches = 0 THEN
  SELECT greatest(coalesce(max(id),0), (SELECT last_value FROM system_menu_seq), (SELECT last_value FROM system_menu_id_seq)) + 1 INTO folder_id FROM system_menu;
  INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
  VALUES(folder_id,'标准数据元','',1,30,6126,'standard-data-element','carbon:data-class',NULL,NULL,0,true,true,true,'migration:0191','migration:0191',0);
 ELSIF NOT EXISTS (SELECT 1 FROM system_menu WHERE id=folder_id AND type=1 AND permission='') THEN RAISE EXCEPTION '0191 标准目录菜单归属冲突';
 END IF;
 UPDATE system_menu SET parent_id=folder_id,name='数据元',path='data-element',sort=1,updater='migration:0191',update_time=CURRENT_TIMESTAMP WHERE id=6209;
 UPDATE system_menu SET parent_id=folder_id,name='代码表',path='code-table',sort=2,updater='migration:0191',update_time=CURRENT_TIMESTAMP WHERE id=6203;
 SELECT greatest(coalesce(max(id),0), (SELECT last_value FROM system_menu_seq), (SELECT last_value FROM system_menu_id_seq)) INTO menu_high FROM system_menu;
 PERFORM setval('system_menu_seq',menu_high); PERFORM setval('system_menu_id_seq',menu_high);
 SELECT greatest(coalesce(max(id),0), (SELECT last_value FROM system_role_menu_seq)) INTO role_high FROM system_role_menu;
 INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater,deleted)
 SELECT role_high + row_number() OVER (ORDER BY p.role_id,p.tenant_id,p.menu_id), p.role_id,p.menu_id,p.tenant_id,'migration:0191','migration:0191',0
 FROM (SELECT DISTINCT old.role_id,old.tenant_id, destination.id AS menu_id
  FROM system_role_menu old JOIN system_menu leaf ON leaf.id=old.menu_id CROSS JOIN unnest(ARRAY[folder_id,6126::bigint]) destination(id)
  WHERE old.deleted=0 AND leaf.deleted=0 AND (leaf.id IN (6209,6203) OR leaf.parent_id IN (6209,6203))
  AND NOT EXISTS (SELECT 1 FROM system_role_menu current WHERE current.role_id=old.role_id AND current.tenant_id=old.tenant_id AND current.menu_id=destination.id AND current.deleted=0)) p;
 GET DIAGNOSTICS inserted = ROW_COUNT;
 IF inserted > 0 THEN PERFORM setval('system_role_menu_seq',role_high+inserted); END IF;
END $menus$;
COMMIT;

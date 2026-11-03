-- Migration: 0227 资产敏感清单及来源记录迁入。
-- Purpose: 独立保存可重复、可启停的资产字段分类记录，迁入二十五条真实来源记录及其展示快照，补齐页面权限。
-- Depends on: 0221_asset_qdata-map-datasets.sql 的五十四个真实资产与字段快照；0226_governance_qdata-sensitive-classifications.sql 的目录与分类定义；现有资产、系统菜单角色及市场审计表。不依赖并发任务的 0225。
-- Target: 已有 PostgreSQL 平台库；新库使用已同步本迁移的完整基线。
-- Repeatable: 是；来源编号包含已软删除记录，存在即跳过，不覆盖本地字段、状态、修订、审计或人工菜单权限。
-- Rollback: 单事务失败完整回滚；提交后须备份并确认业务引用后人工移除本迁移新增对象及菜单，清单和操作历史不可恢复，禁止自动破坏性回滚。
-- 来源：2026-10-22 授权只读取得的二十五条清单。来源昵称仅展示，本地操作者不使用来源账号或用户编号；规则名称不是可执行规则配置，不承诺已经对数据出口实施脱敏。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE SEQUENCE IF NOT EXISTS dh_asset_sensitive_list_seq;
CREATE TABLE IF NOT EXISTS dh_asset_sensitive_list (
 id bigint PRIMARY KEY DEFAULT nextval('dh_asset_sensitive_list_seq'),
 source_record_id varchar(64),
 asset_id bigint NOT NULL REFERENCES dh_asset_data_asset(id),
 column_id bigint NOT NULL CHECK(column_id>0),
 column_name varchar(255) NOT NULL,
 category_id bigint NOT NULL CHECK(category_id>0),
 classification_name varchar(255) NOT NULL,
 level_name varchar(128),
 level_color varchar(32),
 rule_name varchar(255),
 description varchar(256) NOT NULL DEFAULT '',
 status integer NOT NULL DEFAULT 1 CHECK(status IN(0,1)),
 revision integer NOT NULL DEFAULT 0 CHECK(revision>=0),
 source_creator_name varchar(128),
 source_updater_name varchar(128),
 creator varchar(64) NOT NULL DEFAULT '',
 updater varchar(64) NOT NULL DEFAULT '',
 create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN(0,1))
);
COMMENT ON SEQUENCE dh_asset_sensitive_list_seq IS '资产敏感清单本地主键序列';
COMMENT ON TABLE dh_asset_sensitive_list IS '资产字段分类清单；允许重复记录，启停为本地清单状态，不等于出口脱敏策略已生效';
COMMENT ON COLUMN dh_asset_sensitive_list.id IS '本地清单记录编号';
COMMENT ON COLUMN dh_asset_sensitive_list.source_record_id IS '来源清单编号，仅展示和迁入幂等；包含软删除记录，不作为本地授权身份';
COMMENT ON COLUMN dh_asset_sensitive_list.asset_id IS '本地资产编号，外键关联真实资产';
COMMENT ON COLUMN dh_asset_sensitive_list.column_id IS '当前资产字段编号；迁入字段保留来源字段编号并受本地资产范围约束';
COMMENT ON COLUMN dh_asset_sensitive_list.column_name IS '保存时核验的真实字段名称快照';
COMMENT ON COLUMN dh_asset_sensitive_list.category_id IS '治理服务提供的分类编号，跨服务通过正式分类接口校验';
COMMENT ON COLUMN dh_asset_sensitive_list.classification_name IS '保存时分类名称展示快照';
COMMENT ON COLUMN dh_asset_sensitive_list.level_name IS '保存时安全分级展示名称快照';
COMMENT ON COLUMN dh_asset_sensitive_list.level_color IS '保存时分级展示颜色快照';
COMMENT ON COLUMN dh_asset_sensitive_list.rule_name IS '来源或治理规则名称展示快照，不包含执行配置';
COMMENT ON COLUMN dh_asset_sensitive_list.description IS '清单说明，最多二百五十六字符';
COMMENT ON COLUMN dh_asset_sensitive_list.status IS '清单状态：零启用，一禁用；启用记录禁止直接修改和删除';
COMMENT ON COLUMN dh_asset_sensitive_list.revision IS '本地并发控制修订号，每次变更递增';
COMMENT ON COLUMN dh_asset_sensitive_list.source_creator_name IS '来源创建昵称，仅展示，不参与本地身份或授权';
COMMENT ON COLUMN dh_asset_sensitive_list.source_updater_name IS '来源更新昵称，仅展示，本地更新后清空以显示真实本地操作者';
COMMENT ON COLUMN dh_asset_sensitive_list.creator IS '本地创建操作者编号或迁入标识';
COMMENT ON COLUMN dh_asset_sensitive_list.updater IS '本地更新操作者编号或迁入标识';
COMMENT ON COLUMN dh_asset_sensitive_list.create_time IS '来源创建时间或本地创建时间';
COMMENT ON COLUMN dh_asset_sensitive_list.update_time IS '来源更新时间或本地更新时间';
COMMENT ON COLUMN dh_asset_sensitive_list.deleted IS '逻辑删除：零有效，一删除';
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_sensitive_source_record ON dh_asset_sensitive_list(source_record_id) WHERE source_record_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_asset_sensitive_asset ON dh_asset_sensitive_list(asset_id,status,id) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_asset_sensitive_category ON dh_asset_sensitive_list(category_id,status,id) WHERE deleted=0;
SELECT pg_advisory_xact_lock(1500,33);
LOCK TABLE dh_asset_sensitive_list IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE qdata_sensitive_list_seed(
 source_id bigint PRIMARY KEY, source_asset_id text, column_id bigint, column_name text, short_name text,
 directory_code text, classification_name text, level_name text, rule_name text, description text,
 status integer, creator_name text, updater_name text, create_time timestamp, update_time timestamp
) ON COMMIT DROP;
INSERT INTO qdata_sensitive_list_seed VALUES
(1,'1',8,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:09:17','2026-08-04 19:09:46'),
(2,'2',24,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:09:41','2026-08-04 19:09:44'),
(3,'3',41,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:10:10','2026-08-04 19:10:10'),
(5,'5',77,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:10:51','2026-08-04 19:10:51'),
(6,'7',125,'LGTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',1,'吴同','安信智慧(山东)大数据','2026-08-04 19:11:18','2026-10-22 22:43:24'),
(7,'7',126,'LTTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:12:06','2026-08-04 19:12:10'),
(8,'7',111,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:12:29','2026-08-04 19:12:29'),
(9,'8',144,'LGTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:12:49','2026-08-04 19:12:49'),
(10,'8',145,'LTTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:13:10','2026-08-04 19:13:10'),
(11,'8',130,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:13:30','2026-08-04 19:13:30'),
(12,'9',163,'LGTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:16:48','2026-08-04 19:16:48'),
(13,'9',164,'LTTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:17:08','2026-08-04 19:17:08'),
(14,'9',149,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:17:26','2026-08-04 19:17:26'),
(15,'10',177,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:17:47','2026-08-04 19:17:47'),
(16,'9',163,'LGTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:18:07','2026-08-04 19:18:07'),
(17,'9',164,'LTTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:18:36','2026-08-04 19:18:36'),
(18,'9',149,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:19:03','2026-08-04 19:19:03'),
(20,'14',253,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:22:11','2026-08-04 19:22:11'),
(21,'15',264,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:23:00','2026-08-04 19:23:00'),
(22,'16',283,'LGTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:24:38','2026-08-04 19:24:38'),
(23,'16',269,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:25:05','2026-08-04 19:25:05'),
(24,'16',284,'LTTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:25:27','2026-08-04 19:25:27'),
(25,'16',283,'LGTD','STATION_EXACT_LOCATION','QDATA_SENSITIVE_A05A01','测站精确位置','L3','测站精确位置展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:25:50','2026-08-04 19:25:50'),
(26,'12',216,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','吴同','2026-08-04 19:28:22','2026-08-04 19:28:22'),
(27,'12',216,'STCD','STATION_CODE','QDATA_SENSITIVE_A05A01','测站编码','L2','测站编码展示脱敏规则','',0,'吴同','云南文梵科技','2026-08-04 19:28:44','2026-10-22 18:23:08');
DO $migration$
DECLARE item record; asset_value bigint; category_value bigint; matches integer; color_value text;
BEGIN
 FOR item IN SELECT * FROM qdata_sensitive_list_seed ORDER BY source_id LOOP
   -- 用户后续编辑或软删除的来源记录不会被重建或覆盖。
   IF EXISTS(SELECT 1 FROM dh_asset_sensitive_list WHERE source_record_id=item.source_id::text) THEN CONTINUE; END IF;
   SELECT count(*),min(a.id) INTO matches,asset_value FROM dh_asset_import_dataset d
     JOIN dh_asset_data_asset a ON a.id=d.asset_id AND a.deleted=0
     WHERE d.source_asset_id=item.source_asset_id AND d.deleted=0;
   IF matches<>1 THEN RAISE EXCEPTION '清单来源资产映射必须唯一：%',item.source_asset_id; END IF;
   IF NOT EXISTS(SELECT 1 FROM dh_asset_import_dataset d, jsonb_array_elements(d.columns_json) c
      WHERE d.asset_id=asset_value AND d.deleted=0 AND c->>'id'=item.column_id::text
        AND c->>'columnName'=item.column_name) THEN
     RAISE EXCEPTION '清单字段不在真实迁入资产字段中：%',item.source_id;
   END IF;
   SELECT count(*),min(c.id),min(l.color) INTO matches,category_value,color_value
     FROM dh_governance_safety_category c
     JOIN dh_governance_category_data d ON d.id=c.category_id AND d.deleted=0
     JOIN dh_governance_safety_level l ON l.id=c.data_level_id AND l.deleted=0
     WHERE c.short_name=item.short_name AND c.deleted=0 AND d.category_code=item.directory_code;
   IF matches<>1 THEN RAISE EXCEPTION '清单分类映射必须唯一：%',item.source_id; END IF;
   INSERT INTO dh_asset_sensitive_list(source_record_id,asset_id,column_id,column_name,category_id,
     classification_name,level_name,level_color,rule_name,description,status,revision,
     source_creator_name,source_updater_name,creator,updater,create_time,update_time)
   VALUES(item.source_id::text,asset_value,item.column_id,item.column_name,category_value,
     item.classification_name,item.level_name,color_value,item.rule_name,item.description,item.status,0,
     item.creator_name,item.updater_name,'migration:0227','migration:0227',item.create_time,item.update_time);
 END LOOP;
 IF (SELECT count(*) FROM dh_asset_sensitive_list t JOIN qdata_sensitive_list_seed s ON t.source_record_id=s.source_id::text)<>25 THEN
   RAISE EXCEPTION '敏感清单未完整迁入';
 END IF;
END;
$migration$;

-- 菜单只为既有超级管理员授予初始可见性，不扩大其他角色的维护范围。
LOCK TABLE system_menu,system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE root_id bigint; page_id bigint; child_id bigint; matches integer; next_id bigint; role_value record; item record;
BEGIN
 SELECT count(*),min(id) INTO matches,root_id FROM system_menu
   WHERE type=1 AND parent_id=0 AND trim(both '/' from path)='asset' AND deleted=0;
 IF matches<>1 THEN RAISE EXCEPTION '敏感清单需要唯一有效的资产导航根'; END IF;
 SELECT count(*),min(id) INTO matches,page_id FROM system_menu WHERE component='asset/sensitive/index';
 IF matches>1 OR EXISTS(SELECT 1 FROM system_menu WHERE id=page_id AND
    (deleted<>0 OR type<>2 OR parent_id<>root_id OR permission<>'asset:sensitive-list:query')) THEN
   RAISE EXCEPTION '敏感清单菜单身份冲突';
 END IF;
 IF matches=0 THEN
   SELECT greatest(coalesce(max(id),0),coalesce((SELECT last_value FROM system_menu_seq),0),coalesce((SELECT last_value FROM system_menu_id_seq),0))+1 INTO page_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,creator,updater)
   VALUES(page_id,'敏感清单','asset:sensitive-list:query',2,90,root_id,'sensitive-list','lucide:shield-check','asset/sensitive/index','AssetSensitiveList','migration:0227','migration:0227');
 END IF;
 FOR item IN SELECT * FROM (VALUES('create','新增',1),('update','修改及启停',2),('delete','删除',3)) v(action,name,sort) LOOP
   SELECT count(*),min(id) INTO matches,child_id FROM system_menu WHERE permission='asset:sensitive-list:'||item.action;
   IF matches>1 OR EXISTS(SELECT 1 FROM system_menu WHERE id=child_id AND
      (deleted<>0 OR type<>3 OR parent_id<>page_id)) THEN RAISE EXCEPTION '敏感清单按钮身份冲突：%',item.action; END IF;
   IF matches=0 THEN
     SELECT greatest(coalesce(max(id),0),coalesce((SELECT last_value FROM system_menu_seq),0),coalesce((SELECT last_value FROM system_menu_id_seq),0))+1 INTO child_id FROM system_menu;
     INSERT INTO system_menu(id,name,permission,type,sort,parent_id,creator,updater)
       VALUES(child_id,item.name,'asset:sensitive-list:'||item.action,3,item.sort,page_id,'migration:0227','migration:0227');
   END IF;
 END LOOP;
 FOR role_value IN SELECT id,tenant_id FROM system_role WHERE code='super_admin' AND deleted=0 LOOP
   FOR item IN SELECT id FROM system_menu WHERE id=page_id OR parent_id=page_id LOOP
     IF NOT EXISTS(SELECT 1 FROM system_role_menu WHERE role_id=role_value.id AND menu_id=item.id) THEN
       SELECT greatest(coalesce(max(id),0),coalesce((SELECT last_value FROM system_role_menu_seq),0))+1 INTO next_id FROM system_role_menu;
       INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater) VALUES(next_id,role_value.id,item.id,role_value.tenant_id,'migration:0227','migration:0227');
     END IF;
   END LOOP;
 END LOOP;
 -- 仅当新编号超过既有序列下一值时事务性前移，不回退并发或人工编号。
 FOR item IN SELECT * FROM (VALUES('system_menu_seq','system_menu'),('system_menu_id_seq','system_menu'),('system_role_menu_seq','system_role_menu')) v(sequence_name,table_name) LOOP
   EXECUTE format('SELECT greatest(coalesce(max(id),0)+1,(SELECT last_value+CASE WHEN is_called THEN 1 ELSE 0 END FROM %I)) FROM %I',item.sequence_name,item.table_name) INTO next_id;
   EXECUTE format('ALTER SEQUENCE %I RESTART WITH %s',item.sequence_name,next_id);
 END LOOP;
END;
$menus$;
COMMIT;

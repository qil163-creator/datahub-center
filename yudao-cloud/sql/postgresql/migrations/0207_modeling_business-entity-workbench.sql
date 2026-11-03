-- Migration: 0207 业务实体独立生命周期与数据板块详情。
-- Purpose: 新增业务实体及结构化关系，补齐六类命名规则和详情操作权限，导入脱敏业务定义。
-- Depends on: 0206_modeling_subject-board-ownership-guard.sql 已执行；十个源板块、二十个主题域及既有归属守卫已存在。
-- Target: 已有 PostgreSQL 平台开发库，固定 schema 为 datahub-center；新库使用同步的 datahub-center.sql。
-- Repeatable: 否；新增领域初始化只执行一次，新表已存在即阻断，避免覆盖上线后的独立实体及关系。
-- Rollback: 单事务失败完整回滚；成功后恢复执行前备份可撤销初始化，已有业务写入后不得直接删除新表；旧概念设计、逻辑模型及两张域表记录始终保留。
-- 事务边界：取得既有归属互斥锁，锁等待十秒、语句超时一百二十秒即失败；不复制外部人员账号或联系方式。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));

CREATE SEQUENCE dh_modeling_business_entity_seq;
CREATE TABLE dh_modeling_business_entity (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_business_entity_seq'),
    data_domain_id bigint NOT NULL REFERENCES dh_modeling_data_domain(id),
    subject_domain_id bigint NOT NULL REFERENCES dh_modeling_subject_domain(id),
    name varchar(100) NOT NULL CHECK (btrim(name)<>''),
    code varchar(64) NOT NULL CHECK (code ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    entity_type varchar(16) NOT NULL,
    entity_subtype varchar(16) NOT NULL,
    leader_user_id bigint,
    leader_phone varchar(32),
    description varchar(256),
    status smallint NOT NULL DEFAULT 1 CHECK (status IN (0,1)),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT ck_modeling_business_entity_kind CHECK (
        (entity_type='OBJECT' AND entity_subtype IN ('NORMAL','HIERARCHICAL'))
        OR (entity_type='ACTIVITY' AND entity_subtype IN ('EVENT','PROCESS')))
);
COMMENT ON TABLE dh_modeling_business_entity IS '业务规划实体，独立于概念模型设计保存定义、关系和生命周期';
COMMENT ON COLUMN dh_modeling_business_entity.id IS '业务实体编号';
COMMENT ON COLUMN dh_modeling_business_entity.data_domain_id IS '所属数据板块编号，创建后不变';
COMMENT ON COLUMN dh_modeling_business_entity.subject_domain_id IS '所属主题域编号，必须属于同一板块';
COMMENT ON COLUMN dh_modeling_business_entity.name IS '业务实体显示名称，允许同名';
COMMENT ON COLUMN dh_modeling_business_entity.code IS '板块内不区分大小写唯一的英文名称';
COMMENT ON COLUMN dh_modeling_business_entity.entity_type IS '实体分类：业务对象或业务活动';
COMMENT ON COLUMN dh_modeling_business_entity.entity_subtype IS '实体类型：普通对象、层级对象、业务事件或业务流程';
COMMENT ON COLUMN dh_modeling_business_entity.leader_user_id IS '本地负责人用户编号';
COMMENT ON COLUMN dh_modeling_business_entity.leader_phone IS '本地负责人联系方式';
COMMENT ON COLUMN dh_modeling_business_entity.description IS '业务含义及边界说明';
COMMENT ON COLUMN dh_modeling_business_entity.status IS '实体状态：零上线，一下线';
COMMENT ON COLUMN dh_modeling_business_entity.revision IS '并发修订号';
COMMENT ON COLUMN dh_modeling_business_entity.creator IS '创建人';
COMMENT ON COLUMN dh_modeling_business_entity.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_business_entity.updater IS '更新人';
COMMENT ON COLUMN dh_modeling_business_entity.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_business_entity.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX uq_modeling_business_entity_code ON dh_modeling_business_entity(data_domain_id,lower(code)) WHERE deleted=0;
CREATE INDEX idx_modeling_business_entity_subject ON dh_modeling_business_entity(subject_domain_id,entity_type,entity_subtype) WHERE deleted=0;
COMMENT ON INDEX uq_modeling_business_entity_code IS '有效业务实体英文名称在板块内唯一';
COMMENT ON INDEX idx_modeling_business_entity_subject IS '主题域详情及业务对象、活动统计';

CREATE SEQUENCE dh_modeling_business_entity_relation_seq;
CREATE TABLE dh_modeling_business_entity_relation (
    id bigint PRIMARY KEY DEFAULT nextval('dh_modeling_business_entity_relation_seq'),
    source_entity_id bigint NOT NULL REFERENCES dh_modeling_business_entity(id),
    target_entity_id bigint NOT NULL REFERENCES dh_modeling_business_entity(id),
    relation_type varchar(16) NOT NULL CHECK (relation_type IN ('INHERITANCE','ASSOCIATION','PREDECESSOR','PROCESS_EVENT')),
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order>=0),
    creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT ck_modeling_business_entity_relation_self CHECK (source_entity_id<>target_entity_id)
);
COMMENT ON TABLE dh_modeling_business_entity_relation IS '业务实体继承、关联、前序流程与有序流程事件的结构化关系';
COMMENT ON COLUMN dh_modeling_business_entity_relation.id IS '关系编号';
COMMENT ON COLUMN dh_modeling_business_entity_relation.source_entity_id IS '当前业务实体编号';
COMMENT ON COLUMN dh_modeling_business_entity_relation.target_entity_id IS '被继承、关联或选用的目标实体编号';
COMMENT ON COLUMN dh_modeling_business_entity_relation.relation_type IS '关系类型：继承、关联、前序流程、流程事件';
COMMENT ON COLUMN dh_modeling_business_entity_relation.sort_order IS '同类关系中的显示顺序';
COMMENT ON COLUMN dh_modeling_business_entity_relation.creator IS '创建人';
COMMENT ON COLUMN dh_modeling_business_entity_relation.create_time IS '创建时间';
COMMENT ON COLUMN dh_modeling_business_entity_relation.updater IS '更新人';
COMMENT ON COLUMN dh_modeling_business_entity_relation.update_time IS '更新时间';
COMMENT ON COLUMN dh_modeling_business_entity_relation.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX uq_modeling_business_entity_relation ON dh_modeling_business_entity_relation(source_entity_id,target_entity_id,relation_type) WHERE deleted=0;
CREATE UNIQUE INDEX uq_modeling_business_entity_parent ON dh_modeling_business_entity_relation(source_entity_id) WHERE deleted=0 AND relation_type='INHERITANCE';
CREATE UNIQUE INDEX uq_modeling_business_entity_predecessor ON dh_modeling_business_entity_relation(target_entity_id) WHERE deleted=0 AND relation_type='PREDECESSOR';
CREATE UNIQUE INDEX uq_modeling_business_entity_process_event ON dh_modeling_business_entity_relation(target_entity_id) WHERE deleted=0 AND relation_type='PROCESS_EVENT';
CREATE INDEX idx_modeling_business_entity_relation_target ON dh_modeling_business_entity_relation(target_entity_id) WHERE deleted=0;
COMMENT ON INDEX uq_modeling_business_entity_relation IS '同一实体同类目标关系不得重复';
COMMENT ON INDEX uq_modeling_business_entity_parent IS '普通对象只能继承一个实体';
COMMENT ON INDEX uq_modeling_business_entity_predecessor IS '前序流程只能被一个后续流程占用';
COMMENT ON INDEX uq_modeling_business_entity_process_event IS '业务事件只能属于一个流程';
COMMENT ON INDEX idx_modeling_business_entity_relation_target IS '被引用实体删除保护与关系反向查询';

CREATE FUNCTION modeling_business_entity_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $guard$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_OP='DELETE' OR NEW.deleted<>0 THEN
        IF EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_business_entity_relation r
            WHERE r.deleted=0 AND (r.source_entity_id=OLD.id OR r.target_entity_id=OLD.id)) THEN
            RAISE EXCEPTION '业务实体仍存在关系引用，不能删除' USING ERRCODE='23514';
        END IF;
        IF TG_OP='DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_subject_domain s
        JOIN "datahub-center".dh_modeling_data_domain d ON d.id=s.data_domain_id AND d.deleted=0
        WHERE s.id=NEW.subject_domain_id AND s.data_domain_id=NEW.data_domain_id AND s.deleted=0) THEN
        RAISE EXCEPTION '业务实体主题域必须属于当前有效数据板块' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF NEW.data_domain_id<>OLD.data_domain_id OR NEW.entity_type<>OLD.entity_type OR NEW.entity_subtype<>OLD.entity_subtype THEN
            RAISE EXCEPTION '业务实体所属板块和实体类型创建后不能修改' USING ERRCODE='23514';
        END IF;
        IF NEW.subject_domain_id<>OLD.subject_domain_id AND EXISTS(
            SELECT 1 FROM "datahub-center".dh_modeling_business_entity_relation r
            JOIN "datahub-center".dh_modeling_business_entity e ON e.id=CASE WHEN r.source_entity_id=NEW.id THEN r.target_entity_id ELSE r.source_entity_id END
            WHERE r.deleted=0 AND r.relation_type='INHERITANCE' AND (r.source_entity_id=NEW.id OR r.target_entity_id=NEW.id)
                AND e.subject_domain_id<>NEW.subject_domain_id) THEN
            RAISE EXCEPTION '主题域变更会破坏已有继承关系' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_business_entity_guard() IS '保护业务实体有效归属、不可变类型及关系引用';
CREATE TRIGGER modeling_business_entity_guard BEFORE INSERT OR UPDATE OR DELETE ON dh_modeling_business_entity
    FOR EACH ROW EXECUTE FUNCTION modeling_business_entity_guard();

CREATE FUNCTION modeling_business_entity_relation_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $guard$
DECLARE source_row "datahub-center".dh_modeling_business_entity%ROWTYPE;
    target_row "datahub-center".dh_modeling_business_entity%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.deleted<>0 THEN RETURN NEW; END IF;
    SELECT * INTO source_row FROM "datahub-center".dh_modeling_business_entity WHERE id=NEW.source_entity_id AND deleted=0;
    SELECT * INTO target_row FROM "datahub-center".dh_modeling_business_entity WHERE id=NEW.target_entity_id AND deleted=0;
    IF source_row.id IS NULL OR target_row.id IS NULL OR source_row.data_domain_id<>target_row.data_domain_id THEN
        RAISE EXCEPTION '关系两端必须是同一板块中的有效业务实体' USING ERRCODE='23514';
    END IF;
    IF (NEW.relation_type='INHERITANCE' AND (source_row.entity_subtype<>'NORMAL' OR source_row.subject_domain_id<>target_row.subject_domain_id))
        OR (NEW.relation_type='ASSOCIATION' AND source_row.entity_subtype='HIERARCHICAL')
        OR (NEW.relation_type='PREDECESSOR' AND (source_row.entity_subtype<>'PROCESS' OR target_row.entity_subtype<>'PROCESS'))
        OR (NEW.relation_type='PROCESS_EVENT' AND (source_row.entity_subtype<>'PROCESS' OR target_row.entity_subtype<>'EVENT')) THEN
        RAISE EXCEPTION '业务实体类型或主题域不支持此关系' USING ERRCODE='23514';
    END IF;
    IF NEW.relation_type IN ('INHERITANCE','ASSOCIATION') AND EXISTS(
        SELECT 1 FROM "datahub-center".dh_modeling_business_entity_relation r
        WHERE r.source_entity_id=NEW.source_entity_id AND r.target_entity_id=NEW.target_entity_id AND r.deleted=0
            AND r.id<>NEW.id AND r.relation_type=CASE NEW.relation_type WHEN 'INHERITANCE' THEN 'ASSOCIATION' ELSE 'INHERITANCE' END) THEN
        RAISE EXCEPTION '继承实体不能同时作为直接关联实体' USING ERRCODE='23514';
    END IF;
    IF NEW.relation_type IN ('INHERITANCE','PREDECESSOR') AND EXISTS(
        WITH RECURSIVE ancestors(id) AS (
            SELECT NEW.target_entity_id
            UNION
            SELECT r.target_entity_id FROM "datahub-center".dh_modeling_business_entity_relation r
            JOIN ancestors a ON r.source_entity_id=a.id
            WHERE r.deleted=0 AND r.relation_type=NEW.relation_type AND r.id<>NEW.id
        ) SELECT 1 FROM ancestors WHERE id=NEW.source_entity_id) THEN
        RAISE EXCEPTION '继承关系或前序流程不能形成循环' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_business_entity_relation_guard() IS '校验关系端点归属、类型、同主题继承和有向关系无环';
CREATE TRIGGER modeling_business_entity_relation_guard BEFORE INSERT OR UPDATE OR DELETE ON dh_modeling_business_entity_relation
    FOR EACH ROW EXECUTE FUNCTION modeling_business_entity_relation_guard();

CREATE FUNCTION modeling_business_entity_process_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $guard$
DECLARE entity_ids bigint[]; entity_id bigint;
BEGIN
    IF TG_TABLE_NAME='dh_modeling_business_entity' THEN
        entity_ids:=ARRAY[CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END];
    ELSE
        entity_ids:=CASE WHEN TG_OP='INSERT' THEN ARRAY[NEW.source_entity_id]
            WHEN TG_OP='DELETE' THEN ARRAY[OLD.source_entity_id] ELSE ARRAY[OLD.source_entity_id,NEW.source_entity_id] END;
    END IF;
    FOREACH entity_id IN ARRAY entity_ids LOOP
        IF EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_business_entity e WHERE e.id=entity_id AND e.deleted=0 AND e.entity_subtype='PROCESS')
            AND (SELECT count(*) FROM "datahub-center".dh_modeling_business_entity_relation r
                WHERE r.source_entity_id=entity_id AND r.deleted=0 AND r.relation_type='PROCESS_EVENT')<2 THEN
            RAISE EXCEPTION '业务流程至少需要两个独占业务事件' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NULL;
END $guard$;
COMMENT ON FUNCTION modeling_business_entity_process_guard() IS '事务提交时保证有效业务流程至少包含两个事件';
CREATE CONSTRAINT TRIGGER modeling_business_entity_process_guard AFTER INSERT OR UPDATE OR DELETE ON dh_modeling_business_entity
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION modeling_business_entity_process_guard();
CREATE CONSTRAINT TRIGGER modeling_business_entity_process_guard AFTER INSERT OR UPDATE OR DELETE ON dh_modeling_business_entity_relation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION modeling_business_entity_process_guard();

CREATE FUNCTION modeling_business_entity_domain_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $guard$
DECLARE referenced boolean;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('modeling-ownership-global',0));
    IF TG_TABLE_NAME='dh_modeling_data_domain' THEN
        IF TG_OP='DELETE' OR NEW.deleted<>0 THEN
            SELECT EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_business_entity WHERE data_domain_id=OLD.id AND deleted=0) INTO referenced;
        END IF;
    ELSE
        IF TG_OP='DELETE' OR NEW.deleted<>0 THEN
            SELECT EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_business_entity WHERE subject_domain_id=OLD.id AND deleted=0) INTO referenced;
        ELSIF NEW.data_domain_id<>OLD.data_domain_id THEN
            SELECT EXISTS(SELECT 1 FROM "datahub-center".dh_modeling_business_entity
                WHERE subject_domain_id=OLD.id AND deleted=0 AND data_domain_id<>NEW.data_domain_id) INTO referenced;
        END IF;
    END IF;
    IF referenced THEN RAISE EXCEPTION '规划对象仍被业务实体引用，不能删除或迁移板块' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $guard$;
COMMENT ON FUNCTION modeling_business_entity_domain_guard() IS '独立业务实体对数据板块和主题域的引用保护';
CREATE TRIGGER modeling_business_entity_domain_guard BEFORE UPDATE OR DELETE ON dh_modeling_data_domain
    FOR EACH ROW EXECUTE FUNCTION modeling_business_entity_domain_guard();
CREATE TRIGGER modeling_business_entity_domain_guard BEFORE UPDATE OR DELETE ON dh_modeling_subject_domain
    FOR EACH ROW EXECUTE FUNCTION modeling_business_entity_domain_guard();


-- 按源页面的独立实体定义初始化，不改动已有概念模型中的同名设计。
CREATE TEMP TABLE business_entity_local_actor ON COMMIT DROP AS
SELECT u.id,u.mobile FROM system_users u WHERE u.deleted=0 AND u.status=0
ORDER BY CASE WHEN EXISTS(SELECT 1 FROM system_user_role ur JOIN system_role r ON r.id=ur.role_id
    WHERE ur.user_id=u.id AND ur.deleted=0 AND r.deleted=0 AND r.code='super_admin') THEN 0 ELSE 1 END,u.id LIMIT 1;
DO $actor$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM business_entity_local_actor) THEN
        RAISE EXCEPTION '未找到可映射的有效本地用户，已阻断业务实体初始化';
    END IF;
END $actor$;
INSERT INTO dh_modeling_business_entity(id,data_domain_id,subject_domain_id,name,code,entity_type,entity_subtype,
    description,status,leader_user_id,leader_phone,creator,create_time,updater,update_time)
SELECT e.id,e.board_id,e.subject_id,e.name,e.code,e.kind,e.subtype,e.description,e.status,
    actor.id,actor.mobile,actor.id::text,e.created::timestamp,actor.id::text,e.updated::timestamp
FROM (VALUES
    (22,12,76,'客户','CUSTOMER','OBJECT','NORMAL','在平台产生注册、浏览、购买等行为的客户主体',1,'2026-09-18 17:18:39','2026-09-23 11:33:16'),
    (23,12,76,'会员','MEMBER','OBJECT','NORMAL','',0,'2026-09-18 17:20:49','2026-09-18 17:20:49'),
    (24,12,77,'商品类目','PRODUCT_CATEGORY','OBJECT','HIERARCHICAL','商品所属分类体系',0,'2026-09-18 17:22:22','2026-09-18 17:22:22'),
    (25,12,77,'商品','PRODUCT','OBJECT','NORMAL','平台实际销售的商品',0,'2026-09-18 17:22:57','2026-09-23 11:33:27'),
    (26,12,78,'订单','ORDER','OBJECT','NORMAL','客户发起购买后形成的交易订单',1,'2026-09-18 17:26:24','2026-09-18 17:26:24'),
    (27,12,78,'订单明细','ORDER_ITEM','OBJECT','NORMAL','',0,'2026-09-18 17:27:15','2026-09-18 17:27:15'),
    (28,12,76,'客户注册','CUSTOMER_REGISTER','ACTIVITY','EVENT','客户完成平台注册的业务事件',0,'2026-09-18 17:28:05','2026-09-18 17:28:05'),
    (29,12,78,'客户下单','ORDER_CREATE','ACTIVITY','EVENT','客户提交商品订单形成订单的业务行为',0,'2026-09-18 17:28:47','2026-09-18 17:28:47'),
    (30,12,78,'订单支付','ORDER_PAYMENT','ACTIVITY','EVENT','客户针对订单完成支付',0,'2026-09-18 17:29:24','2026-09-18 17:29:24'),
    (31,12,78,'订单退款','ORDER_REFUND','ACTIVITY','EVENT','客户针对订单或者订单商品发起退款',0,'2026-09-18 17:29:51','2026-09-18 17:29:51'),
    (32,12,79,'订单发货','ORDER_SHIP','ACTIVITY','EVENT','',0,'2026-09-18 17:30:14','2026-09-18 17:30:14'),
    (33,12,79,'订单签收','ORDER_RECEIVE','ACTIVITY','EVENT','',0,'2026-09-18 17:30:36','2026-09-18 17:30:36'),
    (34,12,79,'订单履约流程','ORDER_FULFILLMENT_PROCESS','ACTIVITY','PROCESS','从客户下单到完成订单履约的完整业务过程ORDER_FULFILLMENT_PROCESS',0,'2026-09-18 17:31:31','2026-09-28 11:26:39'),
    (48,12,78,'交易实体','jyst','OBJECT','NORMAL','',0,'2026-09-24 10:43:48','2026-09-24 10:43:48'),
    (49,12,76,'客户','customer2','OBJECT','NORMAL','',0,'2026-09-24 11:26:30','2026-09-24 11:27:01'),
    (56,29,113,'水文站','HYDRO_STATION','OBJECT','NORMAL','水位、流量等水文监测站点',0,'2026-09-28 18:01:50','2026-09-28 18:01:50'),
    (57,29,113,'雨量站','RAIN_STATION','OBJECT','NORMAL','降雨监测站点',0,'2026-09-28 18:02:29','2026-09-28 18:02:29'),
    (58,29,113,'水位采集','WATER_LEVEL_COLLECT','ACTIVITY','EVENT','一次水位采集事件',0,'2026-09-28 18:03:11','2026-09-28 18:03:11'),
    (59,29,113,'流量采集','FLOW_COLLECT','ACTIVITY','EVENT','一次流量采集事件',0,'2026-09-28 18:03:41','2026-09-28 18:03:41'),
    (60,29,113,'降雨采集','RAIN_COLLECT','ACTIVITY','EVENT','',0,'2026-09-28 18:03:59','2026-09-28 18:03:59'),
    (61,29,114,'水库','RESERVOIR','OBJECT','NORMAL','',0,'2026-09-28 18:04:49','2026-09-28 18:04:49'),
    (62,29,114,'水库超汛限','RESERVOIR_OVER_LIMIT','ACTIVITY','EVENT','水库水位超过汛限水位',0,'2026-09-28 18:05:47','2026-09-28 18:05:47'),
    (63,29,115,'河道超警戒','RIVER_OVER_WARNING','OBJECT','NORMAL','河道水位超过警戒水位',0,'2026-09-28 18:06:29','2026-09-28 18:06:29'),
    (64,29,115,'水利工程','WATER_PROJECT','OBJECT','NORMAL','水库、闸门、泵站、堤防等工程统一对象',0,'2026-09-28 18:07:04','2026-09-28 18:07:04'),
    (65,29,115,'闸门','SLUICE_GATE','OBJECT','NORMAL','水闸运行对象',0,'2026-09-28 18:07:45','2026-09-28 18:07:45'),
    (66,29,115,'泵站','PUMP_STATION','OBJECT','NORMAL','排涝、调水、提水工程对象',0,'2026-09-28 18:08:10','2026-09-28 18:08:10'),
    (67,29,115,'闸门启闭','GATE_OPERATION','ACTIVITY','EVENT','一次闸门开启/关闭操作',0,'2026-09-28 18:08:40','2026-09-28 18:08:40'),
    (68,29,115,'泵站启停','PUMP_OPERATION','ACTIVITY','EVENT','一次泵站启动/停止操作',0,'2026-09-28 18:09:20','2026-09-28 18:09:20'),
    (69,29,117,'防汛责任区','FLOOD_CONTROL_AREA','OBJECT','HIERARCHICAL','省、市、区县、责任片区',0,'2026-09-28 18:10:14','2026-09-28 18:10:14'),
    (70,29,117,'洪水预警','FLOOD_WARNING','OBJECT','NORMAL','一条可持续、可解除的预警业务对象',0,'2026-09-28 18:11:27','2026-09-28 18:11:27'),
    (71,29,117,'洪水预警触发','FLOOD_WARNING_TRIGGER','ACTIVITY','EVENT','达到规则后产生预警',0,'2026-09-28 18:12:01','2026-09-28 18:12:01'),
    (72,29,117,'预警发布','WARNING_PUBLISH','OBJECT','NORMAL','正式发布预警',0,'2026-09-28 18:13:05','2026-09-28 18:13:05'),
    (73,29,117,'防汛响应启动','FLOOD_RESPONSE_START','ACTIVITY','EVENT','启动防汛响应',0,'2026-09-28 18:13:35','2026-09-28 18:13:35'),
    (74,29,117,'防汛响应结束','FLOOD_RESPONSE_END','ACTIVITY','EVENT','结束防汛响应',0,'2026-09-28 18:14:03','2026-09-28 18:14:03'),
    (75,29,117,'洪水预警处置流程','FLOOD_WARNING_PROCESS','ACTIVITY','PROCESS','从预警触发到响应结束的全过程',0,'2026-09-28 18:15:25','2026-09-28 18:15:25')
) e(id,board_id,subject_id,name,code,kind,subtype,description,status,created,updated)
CROSS JOIN business_entity_local_actor actor;

INSERT INTO dh_modeling_business_entity_relation(source_entity_id,target_entity_id,relation_type,sort_order,creator,updater)
SELECT r.source_id,r.target_id,r.kind,r.position,actor.id::text,actor.id::text FROM (VALUES
    (23,22,'INHERITANCE',0),
    (25,24,'ASSOCIATION',0),
    (26,22,'ASSOCIATION',0),
    (27,26,'ASSOCIATION',0),
    (27,25,'ASSOCIATION',1),
    (28,22,'ASSOCIATION',0),
    (29,22,'ASSOCIATION',0),
    (29,26,'ASSOCIATION',1),
    (29,27,'ASSOCIATION',2),
    (29,25,'ASSOCIATION',3),
    (30,22,'ASSOCIATION',0),
    (30,26,'ASSOCIATION',1),
    (31,22,'ASSOCIATION',0),
    (31,26,'ASSOCIATION',1),
    (31,27,'ASSOCIATION',2),
    (31,25,'ASSOCIATION',3),
    (32,26,'ASSOCIATION',0),
    (33,26,'ASSOCIATION',0),
    (34,29,'PROCESS_EVENT',0),
    (34,30,'PROCESS_EVENT',1),
    (34,32,'PROCESS_EVENT',2),
    (34,33,'PROCESS_EVENT',3),
    (48,26,'INHERITANCE',0),
    (49,22,'INHERITANCE',0),
    (64,61,'ASSOCIATION',0),
    (65,64,'ASSOCIATION',0),
    (66,64,'ASSOCIATION',0),
    (70,61,'ASSOCIATION',0),
    (70,69,'ASSOCIATION',1),
    (75,71,'PROCESS_EVENT',0),
    (75,73,'PROCESS_EVENT',1),
    (75,74,'PROCESS_EVENT',2)
) r(source_id,target_id,kind,position) CROSS JOIN business_entity_local_actor actor;
SELECT setval('dh_modeling_business_entity_seq',greatest(1,(SELECT max(id) FROM dh_modeling_business_entity)));
SELECT setval('dh_modeling_business_entity_relation_seq',greatest(1,(SELECT max(id) FROM dh_modeling_business_entity_relation)));

-- 原有四类规则保留本地值，只补入源板块的维度与应用规则；顺序与详情页一致。
WITH source_rules(id,rules) AS (VALUES
    (29,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (12,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (35,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (34,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (33,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (32,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (31,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (30,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (2,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf","minuteSuffix":"_mf","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb),
    (1,'[{"tableType":"ODS","prefix":"ods_","daySuffix":"","hourSuffix":"","minuteSuffix":"fgfasdf","includeSubjectAbbreviation":false},{"tableType":"DIM","prefix":"dim_","daySuffix":"","hourSuffix":"_hf1111","minuteSuffix":"_mf2222","includeSubjectAbbreviation":false},{"tableType":"FACT_PROCESS","prefix":"dwd_","daySuffix":"_da","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":true},{"tableType":"FACT_EVENT","prefix":"dwd_","daySuffix":"_di","hourSuffix":"_hi","minuteSuffix":"_mi","includeSubjectAbbreviation":true},{"tableType":"DWS","prefix":"dws_","daySuffix":"","hourSuffix":"_hh","minuteSuffix":"_mm","includeSubjectAbbreviation":false},{"tableType":"ADS","prefix":"ads_","daySuffix":"","hourSuffix":"","minuteSuffix":"","includeSubjectAbbreviation":false}]'::jsonb)
), merged_rules AS (
    SELECT d.id,jsonb_agg(COALESCE(existing.rule,source.rule) ORDER BY source.ordinal) AS rules
    FROM dh_modeling_data_domain d JOIN source_rules seed ON seed.id=d.id
    CROSS JOIN LATERAL jsonb_array_elements(seed.rules) WITH ORDINALITY source(rule,ordinal)
    LEFT JOIN LATERAL (
        SELECT current.rule FROM jsonb_array_elements(d.naming_rules) AS current(rule)
        WHERE current.rule->>'tableType'=source.rule->>'tableType' LIMIT 1
    ) existing ON true
    WHERE d.deleted=0 GROUP BY d.id
)
UPDATE dh_modeling_data_domain d SET naming_rules=m.rules
FROM merged_rules m WHERE d.id=m.id AND d.naming_rules IS DISTINCT FROM m.rules;
COMMENT ON COLUMN dh_modeling_data_domain.naming_rules IS '贴源、维度、事实流程、事实事件、汇总、应用六类推荐命名规则，仅存储配置不执行数据库语句';

-- 修复上一轮种子以创建时间代替更新时间的显示值；本地已修改记录的更新时间保持不变。
UPDATE dh_modeling_data_domain d SET update_time=seed.updated::timestamp
FROM (VALUES
    (1,'水文气象数据板块','HYD_MET','2026-09-13 18:09:03','2026-09-24 18:34:24'),
    (2,'水利工程数据板块','ENG_ODL','2026-09-19 18:09:03','2026-10-19 17:51:31'),
    (12,'电商零售','EC_RETAIL','2026-09-29 12:43:24','2026-09-24 17:14:10'),
    (29,'水利监测与防汛调度','WATER_CONSERVANCY','2026-09-29 17:47:38','2026-09-28 18:20:49'),
    (30,'城市交通运行','URBAN_TRAFFIC','2026-09-28 17:51:41','2026-09-28 18:21:09'),
    (31,'智慧园区运营','SMART_PARK','2026-09-28 17:52:04','2026-09-28 18:21:11'),
    (32,'医疗健康服务','HEALTHCARE','2026-09-28 17:52:28','2026-09-28 18:21:12'),
    (33,'教育教学管理','EDU_MANAGEMENT','2026-09-28 17:53:09','2026-09-28 18:21:13'),
    (34,'工业生产制造','INDUSTRIAL_MFG','2026-09-28 17:53:33','2026-09-30 10:52:53'),
    (35,'能源综合管理','ENERGY_MANAGEMENT','2026-09-28 17:53:54','2026-09-30 10:29:47')
) seed(id,name,code,created,updated)
WHERE d.id=seed.id AND d.name=seed.name AND d.code=seed.code AND d.deleted=0
    AND d.create_time=seed.created::timestamp AND d.update_time=d.create_time;

-- 详情仍归入现有数据板块页面，不新增导航；普通角色的新权限由管理员明确授予。
LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;
DO $permissions$
DECLARE page_id bigint; button_id bigint; matches integer; item record;
BEGIN
    SELECT count(*),min(id) INTO matches,page_id FROM system_menu
        WHERE permission='modeling:data-domain:query' AND type=2 AND deleted=0;
    IF matches<>1 THEN RAISE EXCEPTION '现有数据板块菜单缺失或不唯一，拒绝猜测详情权限归属'; END IF;
    FOR item IN SELECT * FROM (VALUES
        ('查询业务实体','query',1),('新增业务实体','create',2),('修改业务实体','update',3),
        ('删除业务实体','delete',4),('业务实体上下线','publish',5)
    ) p(label,action,position) LOOP
        SELECT count(*),min(id) INTO matches,button_id FROM system_menu
            WHERE permission='modeling:business-entity:'||item.action;
        IF matches>1 THEN RAISE EXCEPTION '业务实体权限身份重复：%',item.action; END IF;
        IF matches=0 THEN
            SELECT COALESCE(max(id),0)+1 INTO button_id FROM system_menu;
            INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
            VALUES(button_id,item.label,'modeling:business-entity:'||item.action,3,item.position,page_id,'',0,true,true,true,'migration:0207','migration:0207',0);
        ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=button_id AND parent_id=page_id AND type=3 AND deleted=0) THEN
            RAISE EXCEPTION '业务实体权限归属冲突：%',item.action;
        END IF;
    END LOOP;
    PERFORM setval('system_menu_id_seq',greatest((SELECT last_value FROM system_menu_id_seq),(SELECT max(id) FROM system_menu)));
    PERFORM setval('system_menu_seq',greatest((SELECT last_value FROM system_menu_seq),(SELECT max(id) FROM system_menu)));
END $permissions$;
DO $verify$
BEGIN
    IF (SELECT count(*) FROM dh_modeling_business_entity)<>35 OR (SELECT count(*) FROM dh_modeling_business_entity_relation)<>32 THEN
        RAISE EXCEPTION '业务实体或关系初始化数量不匹配';
    END IF;
    RAISE NOTICE '初始化完成：独立业务实体 %，结构化关系 %；已有概念设计与逻辑模型未修改',
        (SELECT count(*) FROM dh_modeling_business_entity),(SELECT count(*) FROM dh_modeling_business_entity_relation);
END $verify$;
COMMIT;

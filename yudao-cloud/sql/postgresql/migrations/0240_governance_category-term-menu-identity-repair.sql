-- Migration: 0240 governance 术语、标签类目菜单父级修复。
-- Purpose: 重放 0238 的术语数据和类目菜单，兼容旧库菜单整理导致的治理父菜单编号或名称变化。
-- Depends on: 0238_governance_category-term-menu.sql 已失败回滚或尚未执行；标签类目表及 0230_asset_qdata-tags.sql（标签数据）。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移最终状态的 datahub-center.sql。
-- Repeatable: 是；表、索引、菜单和初始化数据均使用存在性保护。
-- Rollback: 删除新增菜单及术语类目表会丢失术语类目和其引用；执行前必须确认没有业务术语或外部对象引用这些记录。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;

CREATE SEQUENCE IF NOT EXISTS dh_governance_category_term_seq;

CREATE TABLE IF NOT EXISTS dh_governance_category_term (
    id bigint NOT NULL DEFAULT nextval('dh_governance_category_term_seq'::regclass),
    parent_id bigint NOT NULL DEFAULT 0,
    category_name varchar(128) NOT NULL,
    category_code varchar(128) NOT NULL,
    sort integer NOT NULL DEFAULT 0,
    status smallint NOT NULL DEFAULT 0,
    remark text,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT pk_dh_governance_category_term PRIMARY KEY (id)
);
ALTER SEQUENCE dh_governance_category_term_seq OWNED BY dh_governance_category_term.id;
CREATE INDEX IF NOT EXISTS idx_dh_governance_category_term_parent
    ON dh_governance_category_term(parent_id) WHERE deleted=0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_dh_governance_category_term_name
    ON dh_governance_category_term(parent_id, btrim(category_name)) WHERE deleted=0;
COMMENT ON TABLE dh_governance_category_term IS '数据治理-业务术语类目';
COMMENT ON COLUMN dh_governance_category_term.status IS '状态：0-启用 1-停用';

-- qData termCat 页面中的水务字段术语目录及其九个下级类目。
INSERT INTO dh_governance_category_term
    (id,parent_id,category_name,category_code,sort,status,remark,creator,create_time,updater,update_time,deleted)
VALUES
    (1,0,'水务字段术语','0,1',0,0,'按数据字段语义组织水务行业术语，不收录行业、机构、制度或平台等宽泛概念。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (2,1,'基础标识字段','0,1,2',0,0,'测站、行政区划、河流和工程等对象的标识及空间定位字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (3,1,'时间字段','0,1,3',1,0,'监测、采集、上报和统计周期相关的时间字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (4,1,'水位字段','0,1,4',2,0,'河道、水库、闸坝和地下水监测中的水位字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (5,1,'降雨字段','0,1,5',3,0,'实时、时段和累计降雨监测字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (6,1,'流量字段','0,1,6',4,0,'河道、水库、闸门和生态监测中的流量、流速及累计水量字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (7,1,'水库水情字段','0,1,7',5,0,'水库库容、蓄水量和特征水位字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (8,1,'工程运行字段','0,1,8',6,0,'闸门、泵站和机组运行状态及运行参数字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (9,1,'水质监测字段','0,1,9',7,0,'水体物理、化学和营养盐监测指标字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0),
    (10,1,'数据质量字段','0,1,10',8,0,'描述监测数据状态、质量、单位、来源和审核结果的字段。','1','2026-08-03 16:42:00','1','2026-08-03 16:42:00',0)
ON CONFLICT (id) DO NOTHING;
SELECT setval('dh_governance_category_term_seq',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM dh_governance_category_term), 1), true);

DO $migration$
DECLARE
    governance_parent_id bigint;
    term_page_id bigint;
    tag_page_id bigint;
    action_id bigint;
    menu_high bigint;
    action record;
BEGIN
    -- 6219 是旧库编号；当前基线由 0216 创建的类目目录为 path=category，实际编号通常是 7026。
    -- 先接受旧身份，再按路径和既有类目页面识别，避免依赖基础管理父级编号或中文名称。
    SELECT id INTO governance_parent_id
      FROM system_menu
     WHERE id=6219 AND parent_id=5993 AND type=1 AND trim(both '/' from path)='category'
       AND status=0 AND deleted=0
     LIMIT 1;
    IF governance_parent_id IS NULL THEN
        SELECT menu.id INTO governance_parent_id
          FROM system_menu menu
         WHERE menu.type=1 AND trim(both '/' from menu.path)='category'
           AND menu.status=0 AND menu.deleted=0
           AND (menu.name='类目管理'
                OR EXISTS (SELECT 1 FROM system_menu child
                            WHERE child.parent_id=menu.id AND child.status=0 AND child.deleted=0
                              AND trim(both '/' from child.component) IN
                                  ('governance/categorydata/index',
                                   'governance/categorycleanrule/index',
                                   'governance/categorydatameta/index',
                                   'governance/categorydataquality/index')))
         ORDER BY menu.id
         LIMIT 1;
    END IF;
    IF governance_parent_id IS NULL THEN
        RAISE EXCEPTION '未找到类目管理父菜单：需要 system_menu 中存在 path=category 的有效目录（旧编号 6219 或现有类目页面同级目录）';
    END IF;

    SELECT greatest(coalesce(max(id),0),
                    coalesce((SELECT last_value FROM system_menu_seq),0),
                    coalesce((SELECT last_value FROM system_menu_id_seq),0))
      INTO menu_high FROM system_menu;

    SELECT id INTO term_page_id FROM system_menu
     WHERE trim(both '/' from coalesce(component,''))='governance/categoryterm/index'
       AND deleted=0 ORDER BY id LIMIT 1;
    IF term_page_id IS NULL THEN
        menu_high := menu_high + 1;
        term_page_id := menu_high;
        INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,
                                status,visible,keep_alive,always_show,creator,create_time,updater,update_time,deleted)
        VALUES (term_page_id,'术语类目管理','',2,60,governance_parent_id,'category-term','carbon:term',
                'governance/categoryterm/index','CategoryTerm',0,TRUE,TRUE,TRUE,
                'migration:0240',CURRENT_TIMESTAMP,'migration:0240',CURRENT_TIMESTAMP,0);
    ELSE
        UPDATE system_menu SET parent_id=governance_parent_id, path='category-term', type=2,
            status=0, visible=TRUE, updater='migration:0240', update_time=CURRENT_TIMESTAMP
         WHERE id=term_page_id;
    END IF;

    SELECT id INTO tag_page_id FROM system_menu
     WHERE trim(both '/' from coalesce(component,''))='asset/categorytag/index'
       AND deleted=0 ORDER BY id LIMIT 1;
    IF tag_page_id IS NULL THEN
        menu_high := menu_high + 1;
        tag_page_id := menu_high;
        INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,
                                status,visible,keep_alive,always_show,creator,create_time,updater,update_time,deleted)
        VALUES (tag_page_id,'标签类目管理','',2,70,governance_parent_id,'category-tag','carbon:tag-group',
                'asset/categorytag/index','CategoryTag',0,TRUE,TRUE,TRUE,
                'migration:0240',CURRENT_TIMESTAMP,'migration:0240',CURRENT_TIMESTAMP,0);
    ELSE
        UPDATE system_menu SET parent_id=governance_parent_id, path='category-tag', type=2,
            status=0, visible=TRUE, updater='migration:0240', update_time=CURRENT_TIMESTAMP
         WHERE id=tag_page_id;
    END IF;

    FOR action IN SELECT * FROM (VALUES
        (term_page_id,'术语类目管理查询','governance:category-term:query',1),
        (term_page_id,'术语类目管理创建','governance:category-term:create',2),
        (term_page_id,'术语类目管理更新','governance:category-term:update',3),
        (term_page_id,'术语类目管理删除','governance:category-term:delete',4),
        (tag_page_id,'标签类目管理查询','asset:category-tag:query',1),
        (tag_page_id,'标签类目管理创建','asset:category-tag:create',2),
        (tag_page_id,'标签类目管理更新','asset:category-tag:update',3),
        (tag_page_id,'标签类目管理删除','asset:category-tag:delete',4)
    ) AS seed(page_id,name,permission,sort) LOOP
        SELECT id INTO action_id FROM system_menu
         WHERE parent_id=action.page_id AND permission=action.permission AND deleted=0
         ORDER BY id LIMIT 1;
        IF action_id IS NULL THEN
            menu_high := menu_high + 1;
            INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,
                                    creator,create_time,updater,update_time,deleted)
            VALUES (menu_high,action.name,action.permission,3,action.sort,action.page_id,'',0,TRUE,TRUE,TRUE,
                    'migration:0240',CURRENT_TIMESTAMP,'migration:0240',CURRENT_TIMESTAMP,0);
        END IF;
    END LOOP;
    PERFORM setval('system_menu_seq',menu_high,true);
    PERFORM setval('system_menu_id_seq',menu_high,true);
END;
$migration$;

-- 仅向既有超级管理员授予新导航和操作权限，其他角色仍由权限管理员显式配置。
DO $grants$
DECLARE
    high_id bigint;
BEGIN
    SELECT greatest(coalesce(max(id), 0), coalesce((SELECT last_value FROM system_role_menu_seq), 0))
      INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu(id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY role.id, role.tenant_id, menu.id),
           role.id, menu.id, role.tenant_id, 'migration:0240', 'migration:0240', 0
      FROM system_role role
      CROSS JOIN system_menu menu
     WHERE role.code = 'super_admin' AND role.status = 0 AND role.deleted = 0
       AND menu.deleted = 0
       AND (menu.component IN ('governance/categoryterm/index','asset/categorytag/index')
            OR menu.permission IN ('governance:category-term:query','governance:category-term:create',
                                   'governance:category-term:update','governance:category-term:delete',
                                   'asset:category-tag:query','asset:category-tag:create',
                                   'asset:category-tag:update','asset:category-tag:delete'))
       AND NOT EXISTS (SELECT 1 FROM system_role_menu old
                        WHERE old.role_id = role.id AND old.tenant_id = role.tenant_id
                          AND old.menu_id = menu.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), coalesce((SELECT last_value FROM system_role_menu_seq), 0))
      INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id, true);
END;
$grants$;
COMMIT;

-- Rollback（执行前确认没有术语引用；若 0238 已成功执行，应回滚 0238 创建的对象）：
-- DELETE FROM system_menu WHERE id IN (6916,6917,6918,6919) AND parent_id=6915;
-- DELETE FROM system_menu WHERE id IN (6921,6922,6923,6924) AND parent_id=6920;
-- DELETE FROM system_menu WHERE id IN (6915,6920) AND parent_id=6219;
-- DROP TABLE IF EXISTS dh_governance_category_term;
-- DROP SEQUENCE IF EXISTS dh_governance_category_term_seq;


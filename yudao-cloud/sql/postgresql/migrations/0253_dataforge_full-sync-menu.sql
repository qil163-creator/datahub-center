-- Migration: 0253 整库同步配置菜单与权限
-- Purpose: 新增整库同步列表入口及查询、新增、配置、删除权限；不授予发布执行权限。
-- Depends on: 0237_dataforge_full-sync-wizard.sql；0252_dataforge_integration-ds-runtime.sql（编号链）；数据接入父菜单 system_menu.id=5987。
-- Target: 已有 PostgreSQL 平台库；新库基线包含本迁移。
-- Repeatable: 是；按组件和权限身份校验并补齐，重复执行不重复创建。
-- Rollback: 仅添加菜单和授权；回滚时先删除本菜单及其按钮的角色授权，再删除对应菜单，不删除向导配置。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
LOCK TABLE system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;
DO $menus$
DECLARE page_id bigint; new_id bigint; high_id bigint; matches integer; item record;
BEGIN
 IF NOT EXISTS (SELECT 1 FROM system_menu WHERE id=5987 AND path='integrate' AND deleted=0) THEN
  RAISE EXCEPTION '0253 数据接入父菜单缺失';
 END IF;
 SELECT count(*),min(id) INTO matches,page_id FROM system_menu WHERE component='dataforge/fullsync/index' AND deleted=0;
 IF matches>1 THEN RAISE EXCEPTION '0253 整库同步页面重复'; END IF;
 IF matches=0 THEN
  IF EXISTS(SELECT 1 FROM system_menu WHERE deleted=0 AND (permission='dataforge:full-sync:query' OR (parent_id=5987 AND path='full-sync'))) THEN
   RAISE EXCEPTION '0253 整库同步菜单身份冲突';
  END IF;
  SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO page_id FROM system_menu;
  INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
  VALUES(page_id,'整库同步','dataforge:full-sync:query',2,5,5987,'full-sync','lucide:database-backup','dataforge/fullsync/index','DataforgeFullSync',0,true,true,true,'migration:0253','migration:0253',0);
 ELSIF NOT EXISTS(SELECT 1 FROM system_menu WHERE id=page_id AND parent_id=5987 AND path='full-sync' AND permission='dataforge:full-sync:query' AND type=2) THEN
  RAISE EXCEPTION '0253 整库同步菜单归属冲突';
 END IF;
 FOR item IN SELECT * FROM (VALUES ('create','新增整库同步配置',1),('update','配置整库同步任务',2),('delete','删除整库同步配置',3)) permissions(code,label,sort) LOOP
  SELECT count(*) INTO matches FROM system_menu WHERE permission='dataforge:full-sync:'||item.code AND deleted=0;
  IF matches=0 THEN
   SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO new_id FROM system_menu;
   INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
   VALUES(new_id,item.label,'dataforge:full-sync:'||item.code,3,item.sort,page_id,'',0,true,true,true,'migration:0253','migration:0253',0);
  ELSIF matches<>1 OR NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='dataforge:full-sync:'||item.code AND parent_id=page_id AND type=3 AND deleted=0) THEN
   RAISE EXCEPTION '0253 整库同步权限归属冲突';
  END IF;
 END LOOP;
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
 PERFORM setval('system_menu_seq',high_id); PERFORM setval('system_menu_id_seq',high_id);
 -- 只补齐超级管理员配置管理权限，不修改既有发布执行权限。
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,creator,updater,deleted)
 SELECT high_id+row_number() OVER(ORDER BY r.id,r.tenant_id,m.id),r.id,m.id,r.tenant_id,'migration:0253','migration:0253',0
 FROM system_role r CROSS JOIN system_menu m
 WHERE r.code='super_admin' AND r.deleted=0 AND m.deleted=0 AND (m.id=page_id OR m.parent_id=page_id)
 AND NOT EXISTS(SELECT 1 FROM system_role_menu old WHERE old.role_id=r.id AND old.tenant_id=r.tenant_id AND old.menu_id=m.id AND old.deleted=0);
 SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_role_menu_seq)) INTO high_id FROM system_role_menu;
 PERFORM setval('system_role_menu_seq',high_id);
END $menus$;
COMMIT;

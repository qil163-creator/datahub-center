-- Run only in an isolated disposable PostgreSQL database using psql -X -f <this file>.
-- Covers real PostgreSQL JSONB, first execution, replay and permission preservation.
-- All schema resets below are limited to that disposable database.
\set ON_ERROR_STOP on
SET plpgsql.check_asserts=on;
\ir api-log-operation-tabs-prerequisites.sql
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql

DO $verify$
DECLARE
    query_id bigint;
    entries jsonb;
BEGIN
    SELECT id INTO STRICT query_id FROM system_menu WHERE permission='supply:api-log:query';
    ASSERT query_id > 9007199254740991, 'generated query permission must exercise unsafe-range ID';
    ASSERT (SELECT type=3 AND parent_id=6303 FROM system_menu WHERE id=query_id), 'query permission must become a child';
    ASSERT (SELECT permission='' FROM system_menu WHERE id=6303), 'merged page must not require log permission';
    ASSERT NOT EXISTS (SELECT 1 FROM system_menu WHERE id=7000), 'independent page must be removed';
    ASSERT (SELECT count(*)=2 FROM system_menu WHERE id IN(7001,7002) AND parent_id=6303), 'operation permission IDs must survive';
    ASSERT NOT EXISTS (SELECT 1 FROM system_role_menu WHERE menu_id=7000), 'retired page grants must be removed';
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=100 AND tenant_id=1 AND menu_id=query_id AND deleted=0), 'existing log role must keep log query';
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=600 AND menu_id=query_id AND deleted=0), 'duplicate source grants must not duplicate query grant';
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=200 AND menu_id=6303 AND deleted=0), 'operation role must receive merged entry once';
    ASSERT (SELECT count(*)=2 FROM system_role_menu WHERE role_id=200 AND menu_id IN(7001,7002) AND deleted=0), 'operation grants must survive unchanged';
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=500 AND menu_id=6303 AND deleted=0), 'permission-only role must receive merged entry';
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=100 AND tenant_id=2 AND menu_id=6303 AND deleted=0), 'entry inheritance must preserve tenant';
    ASSERT NOT EXISTS (SELECT 1 FROM system_role_menu WHERE deleted=0 AND menu_id=query_id AND (role_id IN(200,500,9007199254740999) OR tenant_id=2)), 'operation-only roles must not receive log query';
    ASSERT NOT EXISTS (SELECT 1 FROM system_role_menu WHERE deleted=0 AND role_id IN(300,400)), 'deleted grants must not be restored';
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=9007199254740999 AND tenant_id=9007199254740999 AND menu_id=6303), 'large role and tenant IDs must survive';
    ASSERT (SELECT last_value >= query_id FROM system_menu_seq), 'Java menu sequence must advance';
    ASSERT (SELECT last_value >= (SELECT max(id) FROM system_role_menu) FROM system_role_menu_seq), 'role-menu sequence must advance';
    SELECT menu_ids::jsonb INTO entries FROM system_tenant_package WHERE id=1;
    ASSERT entries = jsonb_build_array(6303,'7001'::text,7002,6303,'9007199254740999'::text,query_id), 'mixed JSON ordering and numeric/string types must survive';
    SELECT menu_ids::jsonb INTO entries FROM system_tenant_package WHERE id=2;
    ASSERT entries = '["7001",7002,"9007199254740999","6303"]'::jsonb, 'operation-only package must receive only string entry';
    SELECT menu_ids::jsonb INTO entries FROM system_tenant_package WHERE id=3;
    ASSERT entries = '[9007199254740999,"7002","6303"]'::jsonb, 'unsafe numeric ID must remain exact';
    ASSERT (SELECT updater='unchanged' AND update_time=timestamp '2000-01-01' FROM system_tenant_package WHERE id=4), 'unrelated package must not be rewritten';
    SELECT menu_ids::jsonb INTO entries FROM system_tenant_package WHERE id=5;
    ASSERT entries = jsonb_build_array('6303'::text,query_id::text), 'string IDs must remain strings for query inheritance';
END $verify$;
CREATE TEMP TABLE first_state AS
    SELECT 'menu' AS kind,to_jsonb(row) AS value FROM system_menu row
    UNION ALL SELECT 'role',to_jsonb(row) FROM system_role_menu row
    UNION ALL SELECT 'package',to_jsonb(row) FROM system_tenant_package row;
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql
DO $verify$
BEGIN
    ASSERT NOT EXISTS (
        (SELECT * FROM first_state EXCEPT ALL
            (SELECT 'menu',to_jsonb(row) FROM system_menu row
             UNION ALL SELECT 'role',to_jsonb(row) FROM system_role_menu row
             UNION ALL SELECT 'package',to_jsonb(row) FROM system_tenant_package row))
        UNION ALL
        ((SELECT 'menu',to_jsonb(row) FROM system_menu row
          UNION ALL SELECT 'role',to_jsonb(row) FROM system_role_menu row
          UNION ALL SELECT 'package',to_jsonb(row) FROM system_tenant_package row)
         EXCEPT ALL SELECT * FROM first_state)
    ), 'replay must preserve every menu/grant/package field including audit timestamps';
END $verify$;
INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(nextval('system_role_menu_seq'),800,6303,1);
INSERT INTO system_tenant_package(id,name,menu_ids) VALUES(80,'post-merge entry','[6303]');
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql
DO $verify$
BEGIN
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=800), 'replay must not grant log query to newly authorized entry-only role';
    ASSERT (SELECT menu_ids::jsonb='[6303]'::jsonb FROM system_tenant_package WHERE id=80), 'replay must not grant log query to new entry-only package';
END $verify$;
DROP TABLE first_state;
\echo PASS first execution, exact JSON ID preservation, role/tenant scope, removed grants and replay.

-- Existing child permission and grants must be reused rather than duplicated.
\ir api-log-operation-tabs-prerequisites.sql
INSERT INTO system_menu(id,name,permission,type,parent_id) VALUES(6310,'已有查询','supply:api-log:query',3,6303);
INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id) VALUES(11,100,6310,1);
UPDATE system_tenant_package SET menu_ids='[6303,6310]' WHERE id=1;
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql
DO $verify$
BEGIN
    ASSERT (SELECT count(*)=1 FROM system_menu WHERE permission='supply:api-log:query'), 'existing child query must be reused';
    ASSERT (SELECT count(*)=1 FROM system_role_menu WHERE role_id=100 AND tenant_id=1 AND menu_id=6310), 'existing role permission must be deduplicated';
    ASSERT (SELECT menu_ids::jsonb='[6303,6310]'::jsonb FROM system_tenant_package WHERE id=1), 'existing package query must not be appended twice';
END $verify$;
\echo PASS existing query-child permission and grant deduplication.

-- Expected failures run with ON_ERROR_STOP temporarily disabled, then verify rollback.
\ir api-log-operation-tabs-prerequisites.sql
INSERT INTO system_menu(id,name,type,parent_id,component) VALUES(7300,'自定义页面',2,7000,'custom/index');
\unset LAST_ERROR_MESSAGE
\set ON_ERROR_STOP off
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql
\set ON_ERROR_STOP on
CREATE TEMP TABLE expected_error AS SELECT :'LAST_ERROR_SQLSTATE'::text AS state, :'LAST_ERROR_MESSAGE'::text AS message;
DO $verify$
BEGIN
    ASSERT (SELECT state='P0001' AND message LIKE '%禁止连带删除%' FROM expected_error), 'unexpected child must fail at its explicit protection guard';
    ASSERT (SELECT count(*)=6 FROM system_menu), 'unexpected child must prevent all menu cleanup';
    ASSERT (SELECT count(*)=11 FROM system_role_menu), 'unexpected child must prevent all grant cleanup';
    ASSERT (SELECT permission='supply:api-log:query' FROM system_menu WHERE id=6303), 'failed migration must not clear log permission';
    ASSERT (SELECT updater='unchanged' FROM system_tenant_package WHERE id=1), 'failed migration must not update package';
END $verify$;
DROP TABLE expected_error;
\echo PASS unexpected child page protects all existing permissions.

\ir api-log-operation-tabs-prerequisites.sql
UPDATE system_menu SET status=1 WHERE id=7000;
\unset LAST_ERROR_MESSAGE
\set ON_ERROR_STOP off
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql
\set ON_ERROR_STOP on
CREATE TEMP TABLE expected_error AS SELECT :'LAST_ERROR_SQLSTATE'::text AS state, :'LAST_ERROR_MESSAGE'::text AS message;
DO $verify$
BEGIN
    ASSERT (SELECT state='P0001' AND message LIKE '%台账菜单重复或归属冲突%' FROM expected_error), 'disabled page must fail at its explicit protection guard';
    ASSERT (SELECT count(*)=5 FROM system_menu), 'disabled page must reject migration without cleanup';
    ASSERT (SELECT status=1 FROM system_menu WHERE id=7000), 'disabled page must not be restored or deleted';
    ASSERT (SELECT permission='supply:api-log:query' FROM system_menu WHERE id=6303), 'disabled page must preserve original log authorization';
END $verify$;
DROP TABLE expected_error;
\echo PASS disabled old page is rejected without automatic authorization changes.

\ir api-log-operation-tabs-prerequisites.sql
UPDATE system_menu SET deleted=1 WHERE id=7000;
\unset LAST_ERROR_MESSAGE
\set ON_ERROR_STOP off
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql
\set ON_ERROR_STOP on
CREATE TEMP TABLE expected_error AS SELECT :'LAST_ERROR_SQLSTATE'::text AS state, :'LAST_ERROR_MESSAGE'::text AS message;
DO $verify$
BEGIN
    ASSERT (SELECT state='P0001' AND message LIKE '%台账菜单重复或归属冲突%' FROM expected_error), 'deleted page must fail at its explicit protection guard';
    ASSERT (SELECT count(*)=5 FROM system_menu), 'deleted page must reject migration without cleanup';
    ASSERT (SELECT deleted=1 FROM system_menu WHERE id=7000), 'deleted page must not be restored or removed';
END $verify$;
DROP TABLE expected_error;
\echo PASS deleted old page is rejected without restoring permissions.

\ir api-log-operation-tabs-prerequisites.sql
INSERT INTO system_menu(id,name,permission,type,parent_id) VALUES(7300,'重复核查','supply:data-api:operation-resolve',3,7000);
\unset LAST_ERROR_MESSAGE
\set ON_ERROR_STOP off
\ir ../../../../../../sql/postgresql/migrations/0152_supply_api-log-operation-tabs.sql
\set ON_ERROR_STOP on
CREATE TEMP TABLE expected_error AS SELECT :'LAST_ERROR_SQLSTATE'::text AS state, :'LAST_ERROR_MESSAGE'::text AS message;
DO $verify$
BEGIN
    ASSERT (SELECT state='P0001' AND message LIKE '%缺少台账查询、核查权限或权限归属冲突%' FROM expected_error), 'duplicate operation permission must fail at its explicit guard';
    ASSERT (SELECT count(*)=6 FROM system_menu), 'duplicate operation permission must reject migration';
    ASSERT (SELECT count(*)=11 FROM system_role_menu), 'duplicate permission must preserve grants';
END $verify$;
DROP TABLE expected_error;
\echo PASS duplicate operation permission is rejected.

DROP SCHEMA "datahub-center" CASCADE;
\echo PASS api-log-operation-tabs PostgreSQL self-check completed; fixture schema removed.

-- Disposable PostgreSQL fixture only: this resets the datahub-center schema.
DROP SCHEMA IF EXISTS "datahub-center" CASCADE;
CREATE SCHEMA "datahub-center";
SET search_path TO "datahub-center", public;
CREATE SEQUENCE system_menu_id_seq;
CREATE SEQUENCE system_menu_seq;
CREATE SEQUENCE system_role_menu_seq;
CREATE TABLE system_menu (
    id bigint PRIMARY KEY DEFAULT nextval('system_menu_id_seq'),
    name varchar(50) NOT NULL,
    permission varchar(100) NOT NULL DEFAULT '',
    type smallint NOT NULL,
    sort integer NOT NULL DEFAULT 0,
    parent_id bigint NOT NULL DEFAULT 0,
    path varchar(200) DEFAULT '',
    icon varchar(100) DEFAULT '#',
    component varchar(255),
    component_name varchar(255),
    status smallint NOT NULL DEFAULT 0,
    visible boolean NOT NULL DEFAULT true,
    keep_alive boolean NOT NULL DEFAULT true,
    always_show boolean NOT NULL DEFAULT true,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
CREATE TABLE system_role_menu (
    id bigint PRIMARY KEY,
    role_id bigint NOT NULL,
    menu_id bigint NOT NULL,
    creator varchar(64) DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    tenant_id bigint NOT NULL DEFAULT 0
);
CREATE TABLE system_tenant_package (
    id bigint PRIMARY KEY,
    name varchar(30) NOT NULL,
    status smallint NOT NULL DEFAULT 0,
    remark varchar(256) DEFAULT '',
    menu_ids varchar(4096) NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '',
    update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
INSERT INTO system_menu (id,name,permission,type,parent_id,component,component_name) VALUES
    (6303,'调用记录','supply:api-log:query',2,5990,'supply/apilog/index','SupplyApiLog'),
    (7000,'写操作台账','',2,5990,'supply/operation/index','SupplyWriteOperation'),
    (7001,'查询写操作台账','supply:data-api:operation-query',3,7000,NULL,NULL),
    (7002,'核查未知写结果','supply:data-api:operation-resolve',3,7000,NULL,NULL),
    (9007199254740999,'无关菜单','unrelated:query',2,0,'unrelated/index','UnrelatedPage');
INSERT INTO system_role_menu (id,role_id,menu_id,tenant_id,deleted) VALUES
    (1,100,6303,1,0),
    (2,200,7000,1,0),
    (3,200,7001,1,0),
    (4,200,7002,1,0),
    (5,300,7000,1,1),
    (6,400,6303,1,1),
    (7,500,7001,1,0),
    (8,100,7001,2,0),
    (9,600,6303,1,0),
    (10,600,6303,1,0),
    (9007199254740999,9007199254740999,7002,9007199254740999,0);
INSERT INTO system_tenant_package (id,name,menu_ids,updater,update_time,deleted) VALUES
    (1,'mixed','[6303,7000,"7001",7002,6303,"9007199254740999"]','unchanged',timestamp '2000-01-01',0),
    (2,'operation only','["7000","7001",7002,"9007199254740999"]','unchanged',timestamp '2000-01-01',0),
    (3,'permission only','[9007199254740999,"7002"]','unchanged',timestamp '2000-01-01',0),
    (4,'unrelated','[9007199254740999]','unchanged',timestamp '2000-01-01',0),
    (5,'deleted package','["6303"]','unchanged',timestamp '2000-01-01',1);

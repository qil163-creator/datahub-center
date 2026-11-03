-- 建模旧数据适配测试夹具：只在专用 modeling_legacy_test 数据库中加载。
-- 此夹具保留旧列和关系表，故意不添加草稿主域唯一约束，以覆盖历史脏数据。

CREATE SEQUENCE "datahub-center"."dh_modeling_biz_category_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_biz_category" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_biz_category_seq'::regclass),
    "parent_id" int8 NOT NULL DEFAULT 0,
    "name" varchar(100) NOT NULL DEFAULT '测试分类',
    "code" varchar(50) NOT NULL DEFAULT 'category',
    "domain_ids" jsonb,
    "status" int2 NOT NULL DEFAULT 0,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_biz_category" IS '测试夹具-业务分类';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."parent_id" IS '父分类编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."name" IS '分类名称';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."code" IS '分类编码';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."domain_ids" IS '旧版数据域编号数组';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."status" IS '状态：0启用、1停用';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_data_domain_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_data_domain" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_data_domain_seq'::regclass),
    "name" varchar(100) NOT NULL DEFAULT '测试数据域',
    "code" varchar(50) NOT NULL DEFAULT 'domain',
    "status" int2 NOT NULL DEFAULT 0,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_data_domain" IS '测试夹具-数据域';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."name" IS '数据域名称';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."code" IS '数据域编码';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."status" IS '状态：0启用、1停用';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_data_domain"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_business_process_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_business_process" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_business_process_seq'::regclass),
    "biz_category_id" int8 NOT NULL,
    "name" varchar(100) NOT NULL DEFAULT '测试过程',
    "code" varchar(50) NOT NULL DEFAULT 'process',
    "activity_definition" varchar(1024) NOT NULL DEFAULT '测试活动',
    "canonical_grain_definition" varchar(512) NOT NULL DEFAULT '测试粒度',
    "status" int2 NOT NULL DEFAULT 0,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_business_process" IS '测试夹具-业务过程';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."biz_category_id" IS '业务分类编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."name" IS '过程名称';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."code" IS '过程编码';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."activity_definition" IS '活动定义';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."canonical_grain_definition" IS '标准粒度定义';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."status" IS '状态：0启用、1停用';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_subject_domain_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_subject_domain" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_subject_domain_seq'::regclass),
    "parent_id" int8 NOT NULL DEFAULT 0,
    "name" varchar(100) NOT NULL DEFAULT '测试主题',
    "code" varchar(50) NOT NULL DEFAULT 'subject',
    "layer_id" int8,
    "status" int2 NOT NULL DEFAULT 0,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_subject_domain" IS '测试夹具-主题域';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."parent_id" IS '父主题域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."name" IS '主题名称';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."code" IS '主题编码';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."layer_id" IS '旧版分层编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."status" IS '状态：0启用、1停用';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_subject_domain_process_rel_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_subject_domain_process_rel" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_subject_domain_process_rel_seq'::regclass),
    "subject_domain_id" int8 NOT NULL,
    "business_process_id" int8 NOT NULL,
    "sort_order" int4 NOT NULL DEFAULT 0,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_subject_domain_process_rel" IS '测试夹具-主题域业务过程关系';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."subject_domain_id" IS '主题域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."business_process_id" IS '业务过程编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."sort_order" IS '展示排序';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_subject_domain_process_rel"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_biz_category_data_domain_rel_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_biz_category_data_domain_rel" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_biz_category_data_domain_rel_seq'::regclass),
    "biz_category_id" int8 NOT NULL,
    "data_domain_id" int8 NOT NULL,
    "sort_order" int4 NOT NULL DEFAULT 0,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_biz_category_data_domain_rel" IS '测试夹具-旧版业务分类数据域关系';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."biz_category_id" IS '业务分类编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."data_domain_id" IS '数据域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."sort_order" IS '展示排序';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_biz_category_data_domain_rel"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_business_process_data_domain_rel_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_business_process_data_domain_rel" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_business_process_data_domain_rel_seq'::regclass),
    "business_process_id" int8 NOT NULL,
    "data_domain_id" int8 NOT NULL,
    "relation_role" varchar(24) NOT NULL,
    "sort_order" int4 NOT NULL DEFAULT 0,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_business_process_data_domain_rel" IS '测试夹具-旧版业务过程数据域关系';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."business_process_id" IS '业务过程编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."data_domain_id" IS '数据域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."relation_role" IS '关系角色：主域、支撑域或历史继承';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."sort_order" IS '展示排序';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_business_process_data_domain_rel"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_concept_model_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_concept_model" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_concept_model_seq'::regclass),
    "theme_id" int8,
    "name" varchar(100) NOT NULL DEFAULT '测试模型',
    "layer_id" int8,
    "subject_domain_id" int8,
    "business_process_id" int8,
    "latest_version" int4 NOT NULL DEFAULT 1,
    "table_type" varchar(32),
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_concept_model" IS '测试夹具-concept模型';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."theme_id" IS '模型目录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."name" IS '模型名称';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."layer_id" IS '数仓分层编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."subject_domain_id" IS '主题域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."business_process_id" IS '业务过程编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."latest_version" IS '最新版本号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."table_type" IS '表类型';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_concept_model_version_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_concept_model_version" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_concept_model_version_seq'::regclass),
    "model_id" int8 NOT NULL,
    "version" int4 NOT NULL DEFAULT 1,
    "status" varchar(16) NOT NULL DEFAULT 'PUBLISHED',
    "fields" jsonb,
    "model_snapshot" jsonb,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_concept_model_version" IS '测试夹具-concept模型版本';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."model_id" IS '模型编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."version" IS '版本号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."status" IS '版本状态';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."fields" IS '冻结的字段快照';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."model_snapshot" IS '冻结的模型快照';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_concept_model_version"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_logic_model_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_logic_model" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_logic_model_seq'::regclass),
    "theme_id" int8,
    "name" varchar(100) NOT NULL DEFAULT '测试模型',
    "layer_id" int8,
    "subject_domain_id" int8,
    "business_process_id" int8,
    "latest_version" int4 NOT NULL DEFAULT 1,
    "table_type" varchar(32),
    "status" varchar(16) NOT NULL DEFAULT 'PUBLISHED',
    "warehouse_layer" varchar(10) NOT NULL DEFAULT 'DWD',
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_logic_model" IS '测试夹具-logic模型';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."theme_id" IS '模型目录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."name" IS '模型名称';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."layer_id" IS '数仓分层编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."subject_domain_id" IS '主题域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."business_process_id" IS '业务过程编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."latest_version" IS '最新版本号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."table_type" IS '表类型';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."status" IS '模型状态';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."warehouse_layer" IS '分层标记';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_logic_model_version_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_logic_model_version" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_logic_model_version_seq'::regclass),
    "model_id" int8 NOT NULL,
    "version" int4 NOT NULL DEFAULT 1,
    "status" varchar(16) NOT NULL DEFAULT 'PUBLISHED',
    "fields" jsonb,
    "model_snapshot" jsonb,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_logic_model_version" IS '测试夹具-logic模型版本';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."model_id" IS '模型编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."version" IS '版本号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."status" IS '版本状态';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."fields" IS '冻结的字段快照';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."model_snapshot" IS '冻结的模型快照';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_logic_model_version"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_physical_model_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_physical_model" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_physical_model_seq'::regclass),
    "theme_id" int8,
    "name" varchar(100) NOT NULL DEFAULT '测试模型',
    "layer_id" int8,
    "subject_domain_id" int8,
    "business_process_id" int8,
    "latest_version" int4 NOT NULL DEFAULT 1,
    "table_type" varchar(32),
    "status" varchar(16) NOT NULL DEFAULT 'PUBLISHED',
    "warehouse_layer" varchar(10) NOT NULL DEFAULT 'DWD',
    "database_id" int8 NOT NULL DEFAULT 800,
    "table_name" varchar(128) NOT NULL DEFAULT 'legacy_table',
    "enabled" int2 NOT NULL DEFAULT 1,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_physical_model" IS '测试夹具-physical模型';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."theme_id" IS '模型目录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."name" IS '模型名称';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."layer_id" IS '数仓分层编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."subject_domain_id" IS '主题域编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."business_process_id" IS '业务过程编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."latest_version" IS '最新版本号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."table_type" IS '表类型';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."status" IS '模型状态';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."warehouse_layer" IS '分层标记';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."database_id" IS '目标数据源编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."table_name" IS '物理表名';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."enabled" IS '启用标志';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model"."tenant_id" IS '租户编号';

CREATE SEQUENCE "datahub-center"."dh_modeling_physical_model_version_seq" START 10000;
CREATE TABLE "datahub-center"."dh_modeling_physical_model_version" (
    "id" int8 PRIMARY KEY DEFAULT nextval('"datahub-center".dh_modeling_physical_model_version_seq'::regclass),
    "model_id" int8 NOT NULL,
    "version" int4 NOT NULL DEFAULT 1,
    "status" varchar(16) NOT NULL DEFAULT 'PUBLISHED',
    "fields" jsonb,
    "model_snapshot" jsonb,
    "creator" varchar(64) DEFAULT '',
    "create_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater" varchar(64) DEFAULT '',
    "update_time" timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted" int2 NOT NULL DEFAULT 0,
    "tenant_id" int8 NOT NULL DEFAULT 1
);
COMMENT ON TABLE "datahub-center"."dh_modeling_physical_model_version" IS '测试夹具-physical模型版本';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."id" IS '记录编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."model_id" IS '模型编号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."version" IS '版本号';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."status" IS '版本状态';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."fields" IS '冻结的字段快照';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."model_snapshot" IS '冻结的模型快照';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."creator" IS '创建者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."create_time" IS '创建时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."updater" IS '更新者';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."update_time" IS '更新时间';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."deleted" IS '逻辑删除标识';
COMMENT ON COLUMN "datahub-center"."dh_modeling_physical_model_version"."tenant_id" IS '租户编号';

CREATE UNIQUE INDEX "uk_legacy_test_subject_process"
    ON "datahub-center"."dh_modeling_subject_domain_process_rel"
        ("tenant_id", "subject_domain_id", "business_process_id") WHERE "deleted" = 0;


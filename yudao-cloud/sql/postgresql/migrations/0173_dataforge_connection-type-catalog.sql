-- Migration: 0173 连接类型目录与 DB Switch 产品对齐。
-- Purpose: 补齐九种连接、修正 Oracle 映射、退役无实现的 GBase 8s；删除冗余驱动类、新增描述并统一八类连接分类。
-- Depends on: 0172_modeling_shorten-table-comments.sql 已执行；0169 已完成单租户转换，连接目录、连接信息、系统字典表及原序列存在。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；按有效产品编码与字典键补齐，保留已有编号、连接引用、URL 模板和非空描述，重复执行不增加记录。
-- Rollback: 事务失败回滚表结构与数据，序列可能留下安全空洞；提交后删除的驱动类值只能从执行前备份恢复。回退应用前须恢复该列及原目录、分类字典快照。
-- 风险：连接目录及分类字典会短暂阻塞读写；GBase 8s 仅逻辑删除目录记录，不改写已有连接的类型编号，不冒充 GBase 8a。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
LOCK TABLE dh_dataforge_database_type, system_dict_type, system_dict_data IN ACCESS EXCLUSIVE MODE;

ALTER TABLE dh_dataforge_database_type ADD COLUMN IF NOT EXISTS description varchar(512);
COMMENT ON COLUMN dh_dataforge_database_type.description IS '连接描述';
COMMENT ON COLUMN dh_dataforge_database_type.category_code IS '连接分类：关系型数据库、分析型数据库/数据仓库、文件/对象存储、NoSQL/缓存、消息队列、时序数据库、半结构化存储、向量型数据库';
COMMENT ON COLUMN dh_dataforge_database.name IS '链接名称';
COMMENT ON COLUMN dh_dataforge_database.database_type IS '连接类型';
COMMENT ON COLUMN dh_dataforge_database.usage_type IS '用途分类：1-业务库 2-中台库';

-- 两种 Oracle 模板共用产品编码，保留原编号、名称与连接方式。
UPDATE dh_dataforge_database_type
SET type_code = 'ORACLE', update_time = CURRENT_TIMESTAMP
WHERE regexp_replace(upper(type_code), '[-_[:space:]]', '', 'g') IN ('ORACLESID', 'ORACLESERVICE');
UPDATE dh_dataforge_database_type
SET port_templates = '1521', update_time = CURRENT_TIMESTAMP
WHERE type_code = 'ORACLE' AND port_templates = '1512';

UPDATE dh_dataforge_database_type
SET status = 1, deleted = 1,
    description = CASE WHEN coalesce(btrim(description), '') = '' THEN 'GBase 8s 尚未接入 DB Switch，已退役此连接类型。' ELSE description END,
    remark = CASE WHEN coalesce(remark, '') = '' THEN 'DB Switch 未提供 GBase 8s 产品实现，已退役此连接类型。' ELSE remark END,
    update_time = CURRENT_TIMESTAMP
WHERE regexp_replace(upper(type_code), '[-_[:space:]]', '', 'g') = 'GBASE8S'
  AND (status <> 1 OR deleted <> 1 OR coalesce(btrim(description), '') = '');

CREATE TEMP TABLE connection_product_seed (
    product_code text, category_code text, description text
) ON COMMIT DROP;
INSERT INTO connection_product_seed VALUES
    ('MYSQL', 'RELATIONAL', 'MySQL 关系型数据库，适用于业务数据接入。'),
    ('ORACLE', 'RELATIONAL', 'Oracle 关系型数据库，支持 SID 和服务名两种连接方式。'),
    ('SQLSERVER2000', 'RELATIONAL', 'SQL Server 2000 历史数据库连接，使用前需验证驱动与服务版本兼容性。'),
    ('SQLSERVER', 'RELATIONAL', 'SQL Server 关系型数据库，适用于企业业务数据接入。'),
    ('POSTGRESQL', 'RELATIONAL', 'PostgreSQL 关系型数据库，适用于业务与分析数据接入。'),
    ('GREENPLUM', 'ANALYTICAL', 'Greenplum 分布式分析数据库，适用于数据仓库查询。'),
    ('MARIADB', 'RELATIONAL', 'MariaDB 关系型数据库，适用于业务数据接入。'),
    ('DB2', 'RELATIONAL', 'IBM DB2 关系型数据库，适用于企业业务数据接入。'),
    ('DM', 'RELATIONAL', '达梦关系型数据库，适用于国产数据库数据接入。'),
    ('KINGBASE', 'RELATIONAL', '人大金仓 KingbaseES 数据库，使用 Kingbase8 协议接入。'),
    ('OSCAR', 'RELATIONAL', '神通关系型数据库，适用于国产数据库数据接入。'),
    ('GBASE8A', 'RELATIONAL', '南大通用 GBase 8a 数据库连接，不适用于 GBase 8s 或 8c。'),
    ('HIVE', 'ANALYTICAL', 'Hive 数据仓库，通过 HiveServer2 接入离线分析数据。'),
    ('SQLITE3', 'RELATIONAL', 'SQLite 文件数据库，使用数据库文件路径连接。'),
    ('SYBASE', 'RELATIONAL', 'Sybase 关系型数据库，适用于历史业务系统数据接入。'),
    ('DORIS', 'ANALYTICAL', 'Doris 分析型数据库，适用于数据仓库与分析查询。'),
    ('CLICKHOUSE', 'ANALYTICAL', 'ClickHouse 列式分析数据库，适用于明细数据分析。'),
    ('MONGODB', 'NOSQL_CACHE', 'MongoDB 文档数据库，通过组件提供的 JDBC 适配访问集合。'),
    ('TIDB', 'RELATIONAL', 'TiDB 分布式关系型数据库，使用 MySQL 兼容协议连接。'),
    ('TDENGINE', 'TIME_SERIES', 'TDengine 时序数据库，使用 WebSocket JDBC 协议接入。'),
    ('INFLUXDB3', 'TIME_SERIES', 'InfluxDB 3 时序数据库，使用 Flight SQL 协议，不适用于 1.x 或 2.x。'),
    ('STARROCKS', 'ANALYTICAL', 'StarRocks 分析型数据库，适用于数据仓库与分析查询。'),
    ('HIGHGO', 'RELATIONAL', '瀚高关系型数据库，适用于国产数据库数据接入。'),
    ('OPENGAUSS', 'RELATIONAL', 'openGauss 关系型数据库，适用于业务数据接入。'),
    ('ELASTICSEARCH', 'NOSQL_CACHE', 'Elasticsearch 检索存储，通过组件提供的 JDBC 适配访问索引。'),
    ('OCEANBASE', 'RELATIONAL', 'OceanBase 分布式数据库，根据实际连接识别 MySQL 或 Oracle 兼容模式。'),
    ('UXDB', 'RELATIONAL', '优炫关系型数据库，适用于国产数据库数据接入。');

-- 旧分类按连接用途重分；国产身份不再单独作为连接分类。
UPDATE dh_dataforge_database_type
SET category_code = CASE category_code WHEN 'DOMESTIC' THEN 'RELATIONAL' ELSE 'NOSQL_CACHE' END,
    update_time = CURRENT_TIMESTAMP
WHERE category_code IN ('DOMESTIC', 'DOCUMENT_CACHE_SEARCH');

UPDATE dh_dataforge_database_type t
SET category_code = s.category_code,
    description = CASE WHEN coalesce(btrim(t.description), '') = '' THEN s.description ELSE t.description END,
    update_time = CURRENT_TIMESTAMP
FROM connection_product_seed s
WHERE CASE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g')
    WHEN 'KINGBASEES' THEN 'KINGBASE' WHEN 'KINGBASE8' THEN 'KINGBASE'
    WHEN 'SQLITE' THEN 'SQLITE3'
    WHEN 'GBASE' THEN 'GBASE8A' WHEN 'INFLUXDB' THEN 'INFLUXDB3'
    WHEN 'ES' THEN 'ELASTICSEARCH' ELSE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g') END = s.product_code
  AND t.deleted = 0;

CREATE TEMP TABLE connection_new_type_seed (
    type_code text, type_name text, url_templates text, port_templates text, validation_query text, sort integer
) ON COMMIT DROP;
INSERT INTO connection_new_type_seed VALUES
    ('SQLSERVER2000', 'SQL Server 2000', 'jdbc:sqlserver://${host}:${port};databaseName=${dbName}', '1433', 'SELECT 1', 7),
    ('DB2', 'DB2', 'jdbc:db2://${host}:${port}/${dbName}', '50000', 'SELECT 1 FROM SYSIBM.SYSDUMMY1', 8),
    ('SQLITE3', 'SQLite', 'jdbc:sqlite:${dbName}', '', 'SELECT 1', 9),
    ('SYBASE', 'Sybase', 'jdbc:sybase:Tds:${host}:${port}/${dbName}', '5000', 'SELECT 1', 10),
    ('MONGODB', 'MongoDB', 'jdbc:mongodb://${host}:${port}/${dbName}?authSource=admin', '27017', 'db.runCommand({ping:1,maxTimeMS:5000})', 60),
    ('TIDB', 'TiDB', 'jdbc:mysql://${host}:${port}/${dbName}', '4000', 'SELECT 1', 11),
    ('TDENGINE', 'TDengine', 'jdbc:TAOS-WS://${host}:${port}/${dbName}', '6041', 'SELECT 1', 70),
    ('INFLUXDB3', 'InfluxDB 3', 'jdbc:arrow-flight-sql://${host}:${port}/?useEncryption=0&database=${dbName}', '8181', 'SELECT 1', 71),
    ('ELASTICSEARCH', 'Elasticsearch', 'jdbc:jest://${host}:${port}', '9200', '', 61);

-- 序列先避开已有自定义编号；插入不使用固定主键，不改变任何现有连接引用。
SELECT setval('"datahub-center".dh_foundation_database_type_seq',
    greatest((SELECT last_value FROM dh_foundation_database_type_seq),
             coalesce((SELECT max(id) FROM dh_dataforge_database_type), 1)), true);
INSERT INTO dh_dataforge_database_type
    (id, type_code, type_name, category_code, protocol_type, url_templates, port_templates,
     validation_query, sort, status, description, creator, updater)
SELECT nextval('"datahub-center".dh_foundation_database_type_seq'), n.type_code, n.type_name,
       s.category_code, 'JDBC', n.url_templates, n.port_templates, n.validation_query, n.sort, 0,
       s.description, 'migration', 'migration'
FROM connection_new_type_seed n JOIN connection_product_seed s ON s.product_code = n.type_code
WHERE NOT EXISTS (
    SELECT 1 FROM dh_dataforge_database_type t
    WHERE t.deleted = 0 AND CASE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g')
        WHEN 'SQLITE' THEN 'SQLITE3' WHEN 'INFLUXDB' THEN 'INFLUXDB3'
        WHEN 'ES' THEN 'ELASTICSEARCH' ELSE regexp_replace(upper(t.type_code), '[-_[:space:]]', '', 'g') END = n.type_code
)
ORDER BY n.sort, n.type_code;

-- 分类键仅在连接目录使用；停用旧分类并按新分类键补齐字典。
UPDATE system_dict_type SET name = '连接分类', update_time = CURRENT_TIMESTAMP
WHERE type = 'category_code' AND deleted = 0;
UPDATE system_dict_data SET status = 1, deleted = 1, update_time = CURRENT_TIMESTAMP
WHERE dict_type = 'category_code' AND value IN ('DOMESTIC', 'DOCUMENT_CACHE_SEARCH') AND deleted = 0;

CREATE TEMP TABLE connection_category_seed (sort integer, value text, label text) ON COMMIT DROP;
INSERT INTO connection_category_seed VALUES
    (1, 'RELATIONAL', '关系型数据库'),
    (2, 'ANALYTICAL', '分析型数据库/数据仓库'),
    (3, 'FILE_OBJECT_STORAGE', '文件/对象存储'),
    (4, 'NOSQL_CACHE', 'NoSQL/缓存'),
    (5, 'MESSAGE_QUEUE', '消息队列'),
    (6, 'TIME_SERIES', '时序数据库'),
    (7, 'SEMI_STRUCTURED', '半结构化存储'),
    (8, 'VECTOR', '向量型数据库');
UPDATE system_dict_data d
SET label = s.label, sort = s.sort, status = 0, update_time = CURRENT_TIMESTAMP
FROM connection_category_seed s
WHERE d.dict_type = 'category_code' AND d.value = s.value AND d.deleted = 0;
SELECT setval('"datahub-center".system_dict_data_seq',
    greatest((SELECT last_value FROM system_dict_data_seq),
             coalesce((SELECT max(id) FROM system_dict_data), 1)), true);
INSERT INTO system_dict_data (id, sort, label, value, dict_type, status, creator, updater)
SELECT nextval('"datahub-center".system_dict_data_seq'), s.sort, s.label, s.value,
       'category_code', 0, 'migration', 'migration'
FROM connection_category_seed s
WHERE NOT EXISTS (
    SELECT 1 FROM system_dict_data d WHERE d.dict_type = 'category_code' AND d.value = s.value AND d.deleted = 0
)
ORDER BY s.sort;

-- 实际驱动由 DB Switch 驱动配置管理，目录不再保存第二份驱动类。
ALTER TABLE dh_dataforge_database_type DROP COLUMN IF EXISTS driver_class;
COMMIT;


-- Migration: 0197 多协议连接器目录及仅测连接入。
-- Purpose: 补齐九种数据库、六种外部文件/缓存/消息连接及 SFTP，保留 InfluxDB 3 并新增 2.x 兼容入口。
-- Depends on: 0196_asset_remove-unused-persistence.sql；0173 的连接分类、0186 的文件连接及既有目录序列已存在。
-- Target: 已升级至 0196 的 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；有效类型按规范编码补齐，不改写原编号、连接引用、用户模板和已有描述。
-- Rollback: 事务失败自动回滚；提交后可停用新增目录，不能删除已被连接引用的类型；列扩容回退前须检查已有值长度。
-- 风险：列扩容及目录、字典写入需要短暂锁；不连接或修改任何外部数据源，不产生平台租户字段。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE dh_dataforge_database ALTER COLUMN database_ip TYPE varchar(253);
ALTER TABLE dh_dataforge_database ALTER COLUMN user_name TYPE varchar(512);
COMMENT ON COLUMN dh_dataforge_database.database_ip IS '连接主机，Endpoint 或服务器列表协议可为空串，按协议校验';
COMMENT ON COLUMN dh_dataforge_database.database_port IS '连接端口，Endpoint 或服务器列表协议可为空串';
COMMENT ON COLUMN dh_dataforge_database.user_name IS '账号、AccessKey 标识或 InfluxDB 组织名称，不保存秘密';
COMMENT ON COLUMN dh_dataforge_database.database_name IS '目标数据库、Bucket 或项目名称，按连接协议解释';
COMMENT ON COLUMN dh_dataforge_database.extend_config IS 'JDBC 为连接参数；新原生协议为版本化非秘密 JSON，凭据只存加密密码列';
COMMENT ON COLUMN dh_dataforge_database_type.protocol_type IS '连接协议：JDBC、HTTP、S3、FTP、SFTP、WEBHDFS、REDIS、KAFKA、AMQP、ROCKETMQ、ODPS、BOLT、MILVUS 等';

CREATE TEMP TABLE connect_only_seed (
    code text, name text, category text, protocol text, port text, template text, probe text, description text, ordering integer
) ON COMMIT DROP;
INSERT INTO connect_only_seed VALUES
('GBASE8C','GBase 8c','RELATIONAL','JDBC','5432','jdbc:gbase8c://${host}:${port}/${dbName}','SELECT 1','GBase 8c 连接测试；使用与服务端版本及兼容模式匹配的可信驱动，不适用于 8a/8s。',110),
('GAUSSDB','GaussDB DWS','ANALYTICAL','JDBC','8000','jdbc:postgresql://${host}:${port}/${dbName}','SELECT 1','GaussDB DWS 数仓连接测试；不等同 GaussDB OLTP，驱动由服务端配置。',111),
('PHOENIX','Phoenix Query Server','ANALYTICAL','JDBC','8765','jdbc:phoenix:thin:url=http://${host}:${port};serialization=PROTOBUF','SELECT 1','Phoenix thin 连接测试，访问 Query Server；不是 ZooKeeper/HBase thick 模式。',112),
('SAPHANA','SAP HANA','ANALYTICAL','JDBC','30015','jdbc:sap://${host}:${port}/?databaseName=${dbName}','SELECT 1 FROM DUMMY','SAP HANA 连接测试，使用服务端配置的官方 ngdbc 驱动。',113),
('PRESTO','PrestoDB','ANALYTICAL','JDBC','8080','jdbc:presto://${host}:${port}/${dbName}/${schema}','SELECT 1','PrestoDB 连接测试，库名为 Catalog，模式为 Schema；不作为 Trino 产品使用。',114),
('ODPS','ODPS / MaxCompute','ANALYTICAL','ODPS','','','','MaxCompute Endpoint、Project 与 AccessKey 只读连接验证，不提交计算作业。',115),
('OSS','阿里云 OSS','FILE_OBJECT_STORAGE','S3','','','','复用文件组件的 S3 兼容签名与目标 Bucket 只读探测，不修改对象。',116),
('MINIO','MinIO','FILE_OBJECT_STORAGE','S3','9000','','','MinIO S3 兼容连接，支持 Endpoint、Region、Bucket 及路径访问方式。',117),
('REDIS','Redis','NOSQL_CACHE','REDIS','6379','','','独立 Redis 单机连接，执行认证、目标 DB 选择和 PING；不扫描业务键。',118),
('KAFKA','Kafka','MESSAGE_QUEUE','KAFKA','9092','','','独立 Kafka Broker 连接，支持 SASL/TLS，只读检查集群和可选 Topic。',119),
('RABBITMQ','RabbitMQ','MESSAGE_QUEUE','AMQP','5672','','','独立 AMQP 连接，验证账号与 VirtualHost，不创建队列或消费消息。',120),
('ROCKETMQ','RocketMQ Remoting','MESSAGE_QUEUE','ROCKETMQ','9876','','','RocketMQ Remoting 只读协议与 Broker 探测，需要相应只读权限；不支持 Proxy 或 TLS 模式。',121),
('NEO4J','Neo4j','SEMI_STRUCTURED','BOLT','7687','','','Neo4j 原生协议连接及目标数据库只读验证，本阶段不接入图元数据或读写。',122),
('MILVUS','Milvus','VECTOR','MILVUS','19530','','','Milvus REST v2 认证与目标数据库只读验证，支持密码或 Token。',123),
('INFLUXDB2','InfluxDB 2.x','TIME_SERIES','HTTP','8086','','','Org、Token、Bucket 的 InfluxDB 2.x HTTP 兼容路径，与现有 InfluxDB 3 Flight SQL 分离。',124),
('SFTP','SFTP 文件连接','FILE_OBJECT_STORAGE','SFTP','22','','','SSH 文件协议，仅测连和根目录验证，使用可信主机公钥指纹与密码认证。',125);

SELECT setval('dh_foundation_database_type_seq', greatest(
    (SELECT last_value FROM dh_foundation_database_type_seq),
    coalesce((SELECT max(id) FROM dh_dataforge_database_type),1)),true);
INSERT INTO dh_dataforge_database_type
    (id,type_code,type_name,description,category_code,protocol_type,icon_path,url_templates,port_templates,
     validation_query,sort,status,creator,updater,deleted)
SELECT nextval('dh_foundation_database_type_seq'),s.code,s.name,s.description,s.category,s.protocol,
    '/connection-icons/database.svg',s.template,s.port,s.probe,s.ordering,0,'migration:0197','migration:0197',0
FROM connect_only_seed s
WHERE NOT EXISTS (SELECT 1 FROM dh_dataforge_database_type t WHERE t.deleted=0
    AND regexp_replace(upper(t.type_code),'[-_[:space:]]','','g')=s.code)
ORDER BY s.ordering;

UPDATE dh_dataforge_database_type t
SET description=coalesce(nullif(btrim(t.description),''),s.description),
    category_code=coalesce(nullif(btrim(t.category_code),''),s.category),
    protocol_type=coalesce(nullif(btrim(t.protocol_type),''),s.protocol),
    icon_path=coalesce(nullif(btrim(t.icon_path),''),'/connection-icons/database.svg'),
    updater='migration:0197',update_time=CURRENT_TIMESTAMP
FROM connect_only_seed s
WHERE t.deleted=0 AND regexp_replace(upper(t.type_code),'[-_[:space:]]','','g')=s.code
    AND (coalesce(btrim(t.description),'')='' OR coalesce(btrim(t.category_code),'')=''
        OR coalesce(btrim(t.protocol_type),'')='' OR coalesce(btrim(t.icon_path),'')='');

CREATE TEMP TABLE connection_protocol_seed (value text,label text,ordering integer) ON COMMIT DROP;
INSERT INTO connection_protocol_seed VALUES
('FTP','FTP',10),('WEBHDFS','WebHDFS',11),('SFTP','SFTP',12),('S3','S3 兼容存储',13),
('REDIS','Redis',14),('KAFKA','Kafka',15),('AMQP','AMQP',16),('ROCKETMQ','RocketMQ Remoting',17),
('ODPS','MaxCompute API',18),('BOLT','Neo4j Bolt',19),('MILVUS','Milvus API',20);
SELECT setval('system_dict_data_seq',greatest((SELECT last_value FROM system_dict_data_seq),
    coalesce((SELECT max(id) FROM system_dict_data),1)),true);
INSERT INTO system_dict_data(id,sort,label,value,dict_type,status,creator,updater,deleted)
SELECT nextval('system_dict_data_seq'),s.ordering,s.label,s.value,'protocol_type',0,'migration:0197','migration:0197',0
FROM connection_protocol_seed s
WHERE NOT EXISTS(SELECT 1 FROM system_dict_data d WHERE d.dict_type='protocol_type' AND d.value=s.value AND d.deleted=0)
ORDER BY s.ordering;

COMMIT;

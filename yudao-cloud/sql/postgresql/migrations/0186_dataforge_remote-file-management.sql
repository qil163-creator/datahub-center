-- Migration: 0186 外部连接文件管理。
-- Purpose: 用 FTP/WebHDFS 文件管理替换平台文件分组和上传登记，补充操作审计、连接类型及下载权限。
-- Depends on: 0185_modeling_metric-pages-business-condition.sql；0173 已建立连接类型目录及分类字典，0169 已完成单租户转换。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；表和索引按存在性创建，类型和菜单按有效身份补齐，旧表按存在性删除。
-- Rollback: 事务失败回滚；提交后删除的旧文件元数据和分组不可恢复，须用执行前备份恢复。源端文件和 infra 文件对象不由本脚本删除。
-- 风险：按本次不保留旧数据的产品要求物理删除旧文件表；在维护窗口停止旧应用文件写入及菜单写入，再执行本脚本。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
LOCK TABLE dh_dataforge_database_type, system_menu IN ACCESS EXCLUSIVE MODE;

CREATE SEQUENCE IF NOT EXISTS dh_dataforge_file_operation_seq;
CREATE TABLE IF NOT EXISTS dh_dataforge_file_operation (
    id bigint PRIMARY KEY DEFAULT nextval('dh_dataforge_file_operation_seq'),
    connection_id bigint NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('UPLOAD','MKDIR','RENAME','DELETE')),
    path varchar(4096) NOT NULL,
    target_path varchar(4096),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING','SUCCESS','UNKNOWN')),
    result_message varchar(255),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0
);
COMMENT ON TABLE dh_dataforge_file_operation IS '外部文件操作意图和结果审计，不保存源文件内容及凭据';
COMMENT ON COLUMN dh_dataforge_file_operation.id IS '操作编号';
COMMENT ON COLUMN dh_dataforge_file_operation.connection_id IS '平台受管文件连接编号';
COMMENT ON COLUMN dh_dataforge_file_operation.action IS '操作类型：上传、建目录、重命名、删除';
COMMENT ON COLUMN dh_dataforge_file_operation.path IS '连接根目录内的逻辑路径';
COMMENT ON COLUMN dh_dataforge_file_operation.target_path IS '重命名目标逻辑路径';
COMMENT ON COLUMN dh_dataforge_file_operation.status IS '结果：待执行、成功、源端结果未知需刷新核对';
COMMENT ON COLUMN dh_dataforge_file_operation.result_message IS '不含连接凭据的结果摘要';
COMMENT ON COLUMN dh_dataforge_file_operation.creator IS '操作人';
COMMENT ON COLUMN dh_dataforge_file_operation.create_time IS '意图登记时间';
COMMENT ON COLUMN dh_dataforge_file_operation.updater IS '结果更新人';
COMMENT ON COLUMN dh_dataforge_file_operation.update_time IS '结果更新时间';
COMMENT ON COLUMN dh_dataforge_file_operation.deleted IS '逻辑删除标记';
CREATE INDEX IF NOT EXISTS idx_file_operation_connection_time ON dh_dataforge_file_operation(connection_id, create_time DESC) WHERE deleted=0;
CREATE INDEX IF NOT EXISTS idx_file_operation_pending ON dh_dataforge_file_operation(status, create_time) WHERE deleted=0 AND status<>'SUCCESS';

DO $file_catalog$
DECLARE item record; new_id bigint;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('FTP','FTP 文件服务','FTP','21','FTP 文件目录，支持被动模式及根目录边界。'),
        ('HDFS','HDFS 文件服务','WEBHDFS','9870','通过显式 WebHDFS HTTP 端点访问 HDFS；使用简单用户认证，非 NameNode RPC 端口。')
    ) seed(code,name,protocol,port,description) LOOP
        IF NOT EXISTS (SELECT 1 FROM dh_dataforge_database_type WHERE upper(type_code)=item.code AND deleted=0) THEN
            SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM dh_foundation_database_type_seq))+1 INTO new_id FROM dh_dataforge_database_type;
            INSERT INTO dh_dataforge_database_type(id,type_code,type_name,description,category_code,protocol_type,
                port_templates,sort,status,creator,updater,deleted)
            VALUES(new_id,item.code,item.name,item.description,'FILE_OBJECT_STORAGE',item.protocol,item.port,80,0,'migration:0186','migration:0186',0);
            PERFORM setval('dh_foundation_database_type_seq',new_id);
        END IF;
    END LOOP;
END $file_catalog$;

DO $file_menus$
DECLARE page_id bigint; matches integer; new_id bigint;
BEGIN
    SELECT count(*),min(id) INTO matches,page_id FROM system_menu WHERE component='dataforge/datafile/index' AND deleted=0;
    IF matches<>1 THEN RAISE EXCEPTION '0186 文件管理页面菜单缺失或重复，请核查原文件采集菜单'; END IF;
    UPDATE system_menu SET name='文件管理', updater='migration:0186',update_time=CURRENT_TIMESTAMP WHERE id=page_id;
    UPDATE system_menu SET name='文件上传及新建目录',updater='migration:0186',update_time=CURRENT_TIMESTAMP
        WHERE permission='dataforge:data-file:create' AND parent_id=page_id AND deleted=0;
    UPDATE system_menu SET name='文件重命名',updater='migration:0186',update_time=CURRENT_TIMESTAMP
        WHERE permission='dataforge:data-file:update' AND parent_id=page_id AND deleted=0;
    SELECT count(*) INTO matches FROM system_menu WHERE permission='dataforge:data-file:download' AND deleted=0;
    IF matches=0 THEN
        SELECT greatest(coalesce(max(id),0),(SELECT last_value FROM system_menu_seq),(SELECT last_value FROM system_menu_id_seq))+1 INTO new_id FROM system_menu;
        INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)
        VALUES(new_id,'文件下载','dataforge:data-file:download',3,4,page_id,'',0,true,true,true,'migration:0186','migration:0186',0);
        PERFORM setval('system_menu_seq',new_id);
        PERFORM setval('system_menu_id_seq',new_id);
    ELSIF matches<>1 OR NOT EXISTS(SELECT 1 FROM system_menu WHERE permission='dataforge:data-file:download' AND parent_id=page_id AND type=3 AND deleted=0) THEN
        RAISE EXCEPTION '0186 文件下载权限归属冲突';
    END IF;
END $file_menus$;

-- 新增下载权限由管理员明确授权，不从查询权限推导下载权。
DROP TABLE IF EXISTS dh_dataforge_file;
DROP TABLE IF EXISTS dh_dataforge_file_category;
DROP SEQUENCE IF EXISTS dh_dataforge_file_seq;
DROP SEQUENCE IF EXISTS dh_dataforge_file_category_seq;
COMMIT;


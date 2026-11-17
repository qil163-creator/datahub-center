-- Migration: 0297 同步上游系统和流程模块的生产库增量
-- Purpose: 同步 b4a645654 至 422a95bdb 之间的扫码登录及流程字典、短信日志字段和已有示例数据修正。
-- Depends on: 0296_asset_market-file-delivery.sql 为编号前驱。需要现有系统字典及序列、登录类型、任务状态、短信日志、验证码、OAuth2 客户端和角色表。
-- Target: 已有 PostgreSQL 平台库的 "datahub-center" 模式；执行账号需能修改短信日志表、写入相关系统表并读取、使用字典序列。
--         若曾手工导入字典编号且序列滞后，执行账号还需为相应序列的所有者或其成员，才能在事务内校正序列。
-- Repeatable: 是。单事务执行；按业务键补缺，保留现有字典标签及停用、删除状态。只更正仍匹配上游旧值的示例字段。
-- Rollback: 可在提交前回滚整笔事务。提交后按备份恢复改动过的示例字段，确认无业务引用后可撤回本次新增的字典项。
--           缩回短信日志字段前须确认两列均无超过 255 个字符的记录，否则应保留 text。序列已分配的编号无需回收。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

-- 新版短信验证码映射按整数读取和写入；项目现有字段为 smallint。
DO $check$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute WHERE attrelid = 'system_sms_code'::regclass
          AND attname = 'used' AND NOT attisdropped
          AND atttypid IN ('smallint'::regtype, 'integer'::regtype, 'bigint'::regtype)
    ) THEN
        RAISE EXCEPTION '0297：system_sms_code.used 与整数型验证码映射不匹配，请核对生产库结构';
    END IF;
END;
$check$;

-- 字典表的主键由各自的序列分配。串行补种，避免两个升级会话重复创建同一业务键。
LOCK TABLE system_dict_type, system_dict_data IN SHARE ROW EXCLUSIVE MODE;
DO $check$
BEGIN
    IF EXISTS (
        SELECT 1 FROM (VALUES ('system_login_type'), ('bpm_task_status')) AS required(type)
        WHERE NOT EXISTS (SELECT 1 FROM system_dict_type d WHERE d.type = required.type)
    ) THEN
        RAISE EXCEPTION '0297：缺少 system_login_type 或 bpm_task_status 字典类型，请核对前置基线';
    END IF;
END;
$check$;

-- 手工导入的固定编号可能超过序列。只校正滞后的序列，不复用已发号或已被其他会话缓存的编号。
DO $dict_sequences$
DECLARE
    item record;
    behind_sql text;
    behind boolean;
    cache_size bigint;
BEGIN
    FOR item IN
        SELECT 'system_dict_type_seq'::regclass AS seq, max(id) AS max_id FROM system_dict_type
        UNION ALL
        SELECT 'system_dict_data_seq'::regclass, max(id) FROM system_dict_data
    LOOP
        behind_sql := format('SELECT last_value < $1 OR (last_value = $1 AND NOT is_called) FROM %s', item.seq);
        EXECUTE behind_sql INTO behind USING item.max_id;
        IF behind THEN
            -- 保持原缓存大小，通过 ALTER 获得阻止并发发号的序列锁，锁内再次检查，避免将已追上的序列调回。
            SELECT seqcache INTO cache_size FROM pg_sequence WHERE seqrelid = item.seq;
            EXECUTE format('ALTER SEQUENCE %s CACHE %s', item.seq, cache_size);
            EXECUTE behind_sql INTO behind USING item.max_id;
            IF behind THEN
                EXECUTE format('ALTER SEQUENCE %s RESTART WITH %s', item.seq, item.max_id + 1);
            END IF;
        END IF;
    END LOOP;
END;
$dict_sequences$;

INSERT INTO system_dict_type (id, name, type, status, remark, creator, updater)
SELECT nextval('system_dict_type_seq'), '流程紧急程度', 'bpm_process_instance_priority', 0,
       '流程实例紧急程度', 'migration:0297', 'migration:0297'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_type WHERE type = 'bpm_process_instance_priority');

INSERT INTO system_dict_data (id, sort, label, value, dict_type, status, color_type, creator, updater)
SELECT nextval('system_dict_data_seq'), s.sort, s.label, s.value, s.dict_type, 0, s.color_type,
       'migration:0297', 'migration:0297'
FROM (VALUES
    ('system_login_type', '105', '扫码登录', 105, 'primary'),
    ('bpm_process_instance_priority', 'normal', '普通', 1, 'info'),
    ('bpm_process_instance_priority', 'urgency', '加急', 2, 'danger'),
    ('bpm_task_status', '-2', '跳过', 8, 'info')
) AS s(dict_type, value, label, sort, color_type)
WHERE EXISTS (SELECT 1 FROM system_dict_type t WHERE t.type = s.dict_type AND t.deleted = 0)
  AND NOT EXISTS (SELECT 1 FROM system_dict_data d WHERE d.dict_type = s.dict_type AND d.value = s.value);

-- 授权码示例采用完整的回调地址。生产环境自行配置的回调列表保留原值。
UPDATE system_oauth2_client
SET redirect_uris = '["http://127.0.0.1:18080/callback.html"]', updater = 'migration:0297', update_time = CURRENT_TIMESTAMP
WHERE client_id = 'yudao-sso-demo-by-code' AND deleted = 0 AND redirect_uris = '["http://127.0.0.1:18080"]';

-- 若库中保留了上游 HRM 示例角色，仅分别纠正与旧快照完全相同的乱码。备注中的 U+008F 用转义表示。
WITH old_seed(name, remark) AS (
    VALUES ('HRMå›¢é˜Ÿè´Ÿè´£äºº', U&'æœ¬åœ°å›¢é˜Ÿå·¥ä½œå\008F°æ¼”ç¤ºè§’è‰²')
)
UPDATE system_role r
SET name = CASE WHEN r.name = old_seed.name THEN 'HRM团队负责人' ELSE r.name END,
    remark = CASE WHEN r.remark = old_seed.remark THEN '本地团队工作台演示角色' ELSE r.remark END,
    updater = 'migration:0297', update_time = CURRENT_TIMESTAMP
FROM old_seed
WHERE r.id = 162 AND r.code = 'hrm_team_leader' AND r.tenant_id = 1 AND r.deleted = 0
  AND (r.name = old_seed.name OR r.remark = old_seed.remark);

-- 最后扩宽短信内容和短信参数，尽量缩短排他锁的持有时间。保留两列已有数据、非空约束和中文注释。
DO $sms_log$
DECLARE
    supported_count integer;
    needs_change boolean;
BEGIN
    SELECT count(*) FILTER (WHERE atttypid IN ('varchar'::regtype, 'text'::regtype)),
           bool_or(atttypid = 'varchar'::regtype)
      INTO supported_count, needs_change
      FROM pg_attribute
     WHERE attrelid = 'system_sms_log'::regclass AND attname IN ('template_content', 'template_params') AND NOT attisdropped;
    IF supported_count <> 2 THEN
        RAISE EXCEPTION '0297：请核对 system_sms_log.template_content 和 template_params，预期为 varchar 或 text';
    END IF;
    IF needs_change THEN
        ALTER TABLE system_sms_log ALTER COLUMN template_content TYPE text, ALTER COLUMN template_params TYPE text;
    END IF;
END;
$sms_log$;
COMMIT;

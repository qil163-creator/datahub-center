-- Migration: 0283 在线填报截止延期与结束填报
-- Purpose: 1) 修复在线填报站内信模板（code 以 fill_ 开头）的 params 格式：原值为逗号分隔字符串，
--             system 模块按 JSON 数组（Jackson3TypeHandler）读取 params，读取即抛异常，导致全部填报通知发送失败；
--             本迁移把仍为逗号格式的 fill_ 模板转换为 JSON 数组，其他领域模板不在本迁移范围内。
--          2) 新增两个在线填报站内信模板：fill_deadline_extended（截止时间延期，通知仍需填报的组织）与
--             fill_task_closed（发起者结束填报，通知被取消或撤销更正的组织），params 直接使用 JSON 数组。
--          3) 补全 dh_dataforge_fill_notify_record.notify_type 注释中的 12~14 类型。
--          4) 将已有按钮 dataforge:fill-task:cancel 的名称由“任务取消”改为“结束填报”，与后端
--             PUT /dataforge/fill-task/close 的实际语义一致。延期接口复用已有按钮 dataforge:fill-task:extend，
--             不新增权限、不变更角色授权。
-- Depends on: 0282_modeling_indicator-board.sql（编号前驱）；system_notify_template 及其主键序列已存在；
--             dh_dataforge_fill_notify_record 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；params 只转换仍为逗号格式（不以 [ 开头）的 fill_ 模板；新模板按 code 判重，已存在且内容一致时
--             跳过，内容不一致或已停用时报错中止，不静默覆盖；注释更新和按钮改名可重复执行。
-- Rollback: 无破坏性操作。按 code 删除 fill_deadline_extended、fill_task_closed 两个模板，把
--           dataforge:fill-task:cancel 按钮名称改回“任务取消”即可；params 转换是格式修复，不建议回退（回退后填报通知
--           会重新全部发送失败）。通知失败不影响延期、结束填报等业务结果。站内信模板按 code 直接查库无缓存；按钮改名
--           需清理权限菜单相关 Redis 缓存或重启 system 服务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_notify_template IN SHARE ROW EXCLUSIVE MODE;

-- 逗号分隔的参数名转为 JSON 数组，例如 taskName,orgName → ["taskName", "orgName"]
UPDATE system_notify_template
SET params = (SELECT json_agg(trim(both from name) ORDER BY ord)::text
              FROM unnest(string_to_array(params, ',')) WITH ORDINALITY AS t(name, ord)
              WHERE trim(both from name) <> ''),
    updater = '1', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0 AND code LIKE 'fill\_%' AND params IS NOT NULL AND btrim(params) <> ''
  AND left(btrim(params), 1) <> '[';

DO $fill_notify_templates$
DECLARE
    template_sequence regclass;
    matches integer;
    new_id bigint;
    item record;
BEGIN
    template_sequence := COALESCE(pg_get_serial_sequence('"datahub-center".system_notify_template', 'id')::regclass,
                                  to_regclass('"datahub-center".system_notify_template_seq'));
    IF template_sequence IS NULL THEN
        RAISE EXCEPTION '0283 缺少站内信模板主键序列';
    END IF;
    FOR item IN
        SELECT * FROM (VALUES
            ('填报-截止延期', 'fill_deadline_extended',
             '「{taskName}（{periodKey}）/ {orgName}」的截止时间已延期至 {deadline}，请在截止前完成填报并提交。',
             '["taskName","periodKey","orgName","deadline"]'),
            ('填报-结束填报', 'fill_task_closed',
             '「{taskName}（{periodKey}）/ {orgName}」已由发起者结束填报，本期无需再填报或更正。',
             '["taskName","periodKey","orgName"]')
        ) AS t(name, code, content, params)
    LOOP
        SELECT count(*) INTO matches FROM system_notify_template WHERE code = item.code AND deleted = 0;
        IF matches > 0 THEN
            IF matches <> 1 OR NOT EXISTS (SELECT 1 FROM system_notify_template
                                           WHERE code = item.code AND deleted = 0 AND status = 0
                                             AND content = item.content AND params = item.params) THEN
                RAISE EXCEPTION '0283 站内信模板 % 已存在但内容不同或已停用，请人工核查，禁止静默覆盖', item.code;
            END IF;
            CONTINUE;
        END IF;
        LOOP
            new_id := nextval(template_sequence);
            EXIT WHEN NOT EXISTS (SELECT 1 FROM system_notify_template WHERE id = new_id);
        END LOOP;
        INSERT INTO system_notify_template
            (id, name, code, nickname, content, type, params, status, remark, creator, updater, deleted)
        VALUES (new_id, item.name, item.code, '在线填报', item.content, 2, item.params, 0, '在线填报通知', '1', '1', 0);
    END LOOP;
END;
$fill_notify_templates$;

COMMENT ON COLUMN dh_dataforge_fill_notify_record.notify_type IS '类型：1-指定填报人 2-转派/重新分配 3-任务开始 4-临近截止 5-已逾期 6-他人已提交 7-驳回 8-通过 9-合并成功 10-合并失败 11-催办 12-数据更正 13-截止延期 14-结束填报';

UPDATE system_menu
SET name = '结束填报', updater = '1', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0 AND type = 3 AND permission = 'dataforge:fill-task:cancel' AND name = '任务取消';

COMMIT;

-- Migration: 0285 在线填报合并失败退回整改
-- Purpose: 1) 新增在线填报站内信模板 fill_merge_returned：合并批次失败且重试无法解决（如目标表约束冲突）时，
--             管理员把批次退回整改，通知该组织的有效填报人修改后重新提交审批；params 使用 JSON 数组。
--          2) dh_dataforge_fill_merge_batch.status 注释补充 4-已退回整改；该状态是终态，合并任务不再拾取。
--          3) dh_dataforge_fill_notify_record.notify_type 注释补充 15-合并失败退回整改。
--          退回整改接口 PUT /dataforge/fill-merge-batch/return-rework 复用已有按钮 dataforge:fill-merge:retry，
--          不新增权限、不变更角色授权。
-- Depends on: 0284_system_notify-template-params-json.sql（编号前驱）；system_notify_template 及其主键序列已存在；
--             dh_dataforge_fill_merge_batch、dh_dataforge_fill_notify_record 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；新模板按 code 判重，已存在且内容一致时跳过，内容不一致或已停用时报错中止，不静默覆盖；
--             注释更新可重复执行。单事务执行，不修改任何业务数据行。
-- Rollback: 无破坏性操作。按 code 删除 fill_merge_returned 模板并把两处注释改回原文即可；回退前须确认没有状态为 4 的
--           合并批次，且应用已回退到不含退回整改功能的版本。模板缺失只会导致退回整改的通知发送失败，不影响退回本身。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_notify_template IN SHARE ROW EXCLUSIVE MODE;

DO $fill_merge_returned_template$
DECLARE
    template_sequence regclass;
    matches integer;
    new_id bigint;
    template_content constant text :=
        '「{taskName}（{periodKey}）/ {orgName}」的数据未能写入正式表，已退回整改：{reason}。请修改后重新提交审批。';
    template_params constant text := '["taskName","periodKey","orgName","reason"]';
BEGIN
    SELECT count(*) INTO matches FROM system_notify_template WHERE code = 'fill_merge_returned' AND deleted = 0;
    IF matches > 0 THEN
        IF matches <> 1 OR NOT EXISTS (SELECT 1 FROM system_notify_template
                                       WHERE code = 'fill_merge_returned' AND deleted = 0 AND status = 0
                                         AND content = template_content AND params = template_params) THEN
            RAISE EXCEPTION '0285 站内信模板 fill_merge_returned 已存在但内容不同或已停用，请人工核查，禁止静默覆盖';
        END IF;
        RETURN;
    END IF;
    template_sequence := COALESCE(pg_get_serial_sequence('"datahub-center".system_notify_template', 'id')::regclass,
                                  to_regclass('"datahub-center".system_notify_template_seq'));
    IF template_sequence IS NULL THEN
        RAISE EXCEPTION '0285 缺少站内信模板主键序列';
    END IF;
    LOOP
        new_id := nextval(template_sequence);
        EXIT WHEN NOT EXISTS (SELECT 1 FROM system_notify_template WHERE id = new_id);
    END LOOP;
    INSERT INTO system_notify_template
        (id, name, code, nickname, content, type, params, status, remark, creator, updater, deleted)
    VALUES (new_id, '填报-合并失败退回整改', 'fill_merge_returned', '在线填报', template_content, 2, template_params,
            0, '在线填报通知', '1', '1', 0);
END;
$fill_merge_returned_template$;

COMMENT ON COLUMN dh_dataforge_fill_merge_batch.status IS '状态：0-待执行 1-执行中 2-成功 3-失败（等待自动/人工重试） 4-已退回整改';

COMMENT ON COLUMN dh_dataforge_fill_notify_record.notify_type IS '类型：1-指定填报人 2-转派/重新分配 3-任务开始 4-临近截止 5-已逾期 6-他人已提交 7-驳回 8-通过 9-合并成功 10-合并失败 11-催办 12-数据更正 13-截止延期 14-结束填报 15-合并失败退回整改';

COMMIT;

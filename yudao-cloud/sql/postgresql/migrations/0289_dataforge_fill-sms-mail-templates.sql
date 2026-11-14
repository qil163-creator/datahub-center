-- Migration: 0289 在线填报短信、邮件通知模板
-- Purpose: 计划提醒规则可勾选“短信”“邮件”附加渠道（remind_config.channels），对催办、临近截止、逾期、驳回、
--          延期这几类需要填报人处理的通知，在站内信之外再发短信或邮件。本迁移登记两条通用模板：
--          fill_sms_notice（短信）与 fill_mail_notice（邮件），参数为 taskName、periodKey、orgName、notice，
--          notice 为服务端拼好的一句提醒（例如“请于 2026-11-20 18:00 前完成填报”）。
--          模板以“停用”状态插入：短信需由管理员在“短信管理 - 短信模板”中选择实际渠道、填写服务商模板编号后启用；
--          邮件需在“邮件管理 - 邮件模板”中确认发送账号后启用。停用期间系统只记录发送日志，不会真正发出。
--          不新增菜单与权限。
-- Depends on: 0288_dataforge_fill-template-designer.sql（编号前驱）；system_sms_template、system_sms_channel、
--             system_mail_template、system_mail_account 及各自序列已存在（yudao 系统模块基线）。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；按模板编码判断，已存在（含管理员已修改过的）模板不覆盖。没有可用短信渠道或邮箱账号时跳过对应模板并输出 NOTICE，
--             配置好渠道或账号后重新执行即可补齐。
-- Rollback: 应用回退后可执行
--           DELETE FROM "datahub-center".system_sms_template WHERE code = 'fill_sms_notice';
--           DELETE FROM "datahub-center".system_mail_template WHERE code = 'fill_mail_notice';
--           已发送的短信、邮件日志保留。旧版本应用不读取这两条模板。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $$
DECLARE
    v_channel_id   int8;
    v_channel_code varchar(63);
    v_account_id   int8;
BEGIN
    -- 短信模板：绑定编号最小的可用渠道，停用状态，服务商模板编号待管理员填写
    IF NOT EXISTS (SELECT 1 FROM system_sms_template WHERE code = 'fill_sms_notice' AND deleted = 0) THEN
        SELECT id, code INTO v_channel_id, v_channel_code
          FROM system_sms_channel WHERE deleted = 0 ORDER BY id LIMIT 1;
        IF v_channel_id IS NULL THEN
            RAISE NOTICE '[0289] 没有可用的短信渠道，跳过 fill_sms_notice；配置渠道后重新执行本迁移';
        ELSE
            INSERT INTO system_sms_template (id, type, status, code, name, content, params, remark,
                                             api_template_id, channel_id, channel_code,
                                             creator, create_time, updater, update_time, deleted)
            VALUES (nextval('system_sms_template_seq'), 2, 1, 'fill_sms_notice', '【在线填报】填报提醒',
                    '{orgName}：{taskName}（{periodKey}）{notice}',
                    '["taskName","periodKey","orgName","notice"]',
                    '在线填报短信提醒；请选择实际渠道并填写服务商模板编号后启用',
                    'TODO', v_channel_id, v_channel_code, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, 0);
        END IF;
    END IF;

    -- 邮件模板：绑定编号最小的可用邮箱账号，停用状态
    IF NOT EXISTS (SELECT 1 FROM system_mail_template WHERE code = 'fill_mail_notice' AND deleted = 0) THEN
        SELECT id INTO v_account_id FROM system_mail_account WHERE deleted = 0 ORDER BY id LIMIT 1;
        IF v_account_id IS NULL THEN
            RAISE NOTICE '[0289] 没有可用的邮箱账号，跳过 fill_mail_notice；配置账号后重新执行本迁移';
        ELSE
            INSERT INTO system_mail_template (id, name, code, account_id, nickname, title, content, params, status,
                                              remark, creator, create_time, updater, update_time, deleted)
            VALUES (nextval('system_mail_template_seq'), '【在线填报】填报提醒', 'fill_mail_notice', v_account_id,
                    '数据填报', '【填报提醒】{taskName}（{periodKey}）',
                    '<p>{orgName}：</p><p>{taskName}（{periodKey}）{notice}</p><p>请登录系统，在“我的填报”中处理。</p>',
                    '["taskName","periodKey","orgName","notice"]', 1,
                    '在线填报邮件提醒；请确认发送账号后启用',
                    '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, 0);
        END IF;
    END IF;
END $$;

COMMIT;

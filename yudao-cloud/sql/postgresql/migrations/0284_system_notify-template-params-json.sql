-- Migration: 0284 非填报站内信模板参数改为 JSON 数组
-- Purpose: system 模块 NotifyTemplateDO.params 为 List<String>，由 MyBatis-Plus Jackson3TypeHandler 按 JSON 数组读取；
--          以下 10 个非填报模板的 params 仍为逗号分隔字符串（例如 taskName,issueCount,alertTitle），按 code 读取模板
--          即抛 StreamReadException，对应站内信全部发送失败，站内信模板管理分页查询读到这些行同样失败：
--          quality_alert（数据质量）；dataforge_push_fail、dataforge_push_queue_full（数据推送）；
--          market_apply_submit、market_apply_checked、market_apply_revoked、market_apply_expired、
--          market_demand_replied、market_demand_matched、market_demand_confirmed（数据集市）。
--          本迁移只把上述模板中仍为逗号格式的 params 转为紧凑 JSON 数组，参数名、顺序和模板内容均不变；
--          fill_ 开头的在线填报模板已由 0283 处理，其他模板不在本迁移范围内。
-- Depends on: 0283_dataforge_fill-deadline-extend-close.sql（编号前驱，无数据依赖）；system_notify_template 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；只更新上述 code 中未删除、params 非空且不以 [ 开头的行，已是 JSON 数组的行不再更新；
--             单事务执行，末尾校验上述模板的 params 均为合法 JSON 数组，否则报错并整笔回滚。
-- Rollback: 无破坏性操作，不建议回退：回退为逗号格式后这些站内信会重新全部发送失败。确需回退时，可按 code 将
--           params 改回逗号分隔字符串（例如 SELECT string_agg(value, ',' ORDER BY ord) FROM
--           json_array_elements_text(params::json) WITH ORDINALITY AS t(value, ord)）。按 code 读取模板使用 Redis 缓存，
--           但读取失败时不会写入缓存，转换后无需清理缓存；通知失败不影响质量任务、集市申请与需求的业务结果。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

LOCK TABLE system_notify_template IN SHARE ROW EXCLUSIVE MODE;

-- 逗号分隔的参数名转为紧凑 JSON 数组，例如 taskName,issueCount,alertTitle → ["taskName","issueCount","alertTitle"]
UPDATE system_notify_template
SET params = COALESCE((SELECT '[' || string_agg(to_json(trim(both from name))::text, ',' ORDER BY ord) || ']'
                       FROM unnest(string_to_array(params, ',')) WITH ORDINALITY AS t(name, ord)
                       WHERE trim(both from name) <> ''), '[]'),
    updater = '1', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0
  AND code IN ('quality_alert', 'dataforge_push_fail', 'dataforge_push_queue_full',
               'market_apply_submit', 'market_apply_checked', 'market_apply_revoked', 'market_apply_expired',
               'market_demand_replied', 'market_demand_matched', 'market_demand_confirmed')
  AND params IS NOT NULL AND btrim(params) <> ''
  AND left(btrim(params), 1) <> '[';

-- 校验：上述模板的 params 必须是 JSON 数组，否则 system 模块读取仍会失败
DO $notify_template_params_check$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT id, code, params FROM system_notify_template
        WHERE deleted = 0
          AND code IN ('quality_alert', 'dataforge_push_fail', 'dataforge_push_queue_full',
                       'market_apply_submit', 'market_apply_checked', 'market_apply_revoked', 'market_apply_expired',
                       'market_demand_replied', 'market_demand_matched', 'market_demand_confirmed')
          AND params IS NOT NULL AND btrim(params) <> ''
    LOOP
        BEGIN
            IF jsonb_typeof(item.params::jsonb) <> 'array' THEN
                RAISE EXCEPTION '0284 站内信模板 %（id=%）的 params 不是 JSON 数组：%，请人工核查',
                    item.code, item.id, item.params;
            END IF;
        EXCEPTION WHEN invalid_text_representation THEN
            RAISE EXCEPTION '0284 站内信模板 %（id=%）的 params 不是合法 JSON：%，请人工核查',
                item.code, item.id, item.params;
        END;
    END LOOP;
END;
$notify_template_params_check$;

COMMIT;

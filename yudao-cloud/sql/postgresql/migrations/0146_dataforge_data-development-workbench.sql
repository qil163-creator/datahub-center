-- Migration: 0146 数据开发任务工作台与执行属性
-- Purpose: 补齐开发类目状态、负责人、执行属性、七字段 Cron 容量、工作台菜单和独立 Schema 脚本授权，复用已有发布、调度及实例。
-- Depends on: 0145_dataforge_drop-database-reference-impact.sql 已执行；当前全量基线中的开发目录、任务、实例及系统菜单结构存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；加列、约束、索引及菜单均按存在性处理，重放不覆盖已有任务、负责人或执行配置。
-- Rollback: 失败整笔回滚；提交后回退应用可保留新增列，删除新增列会丢失配置，须先备份。菜单可恢复原显示名“开发工作台”。
-- 此迁移不读取或导入参考系统的账号、数据源凭据、业务任务、日志及演示数据。
-- 加列和约束需要短时锁表；锁等待超过十秒即失败。目录重名或非法配置将阻止提交，须核对历史记录后再执行。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_dataforge_dev_dir
    ADD COLUMN IF NOT EXISTS status smallint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS description varchar(500),
    ADD COLUMN IF NOT EXISTS remark varchar(500);
COMMENT ON COLUMN dh_dataforge_dev_dir.status IS '类目状态：0启用、1禁用；禁用类目不接受新的任务归属';
COMMENT ON COLUMN dh_dataforge_dev_dir.description IS '数据开发类目描述';
COMMENT ON COLUMN dh_dataforge_dev_dir.remark IS '数据开发类目备注';

ALTER TABLE dh_dataforge_dev_task
    ADD COLUMN IF NOT EXISTS owner_id bigint,
    ADD COLUMN IF NOT EXISTS owner_name varchar(100),
    ADD COLUMN IF NOT EXISTS contact_number varchar(32),
    ADD COLUMN IF NOT EXISTS scheduler varchar(32) NOT NULL DEFAULT 'XXL_JOB',
    ADD COLUMN IF NOT EXISTS execution_config_json jsonb NOT NULL DEFAULT '{}'::jsonb;
-- 与接口一百字符上限一致，容纳可选年份字段及年份多选；仅扩宽，不截断既有表达式。
ALTER TABLE dh_dataforge_dev_task ALTER COLUMN cron_expression TYPE varchar(100);
COMMENT ON COLUMN dh_dataforge_dev_task.owner_id IS '维护负责人用户编号；由服务端校验用户所属租户';
COMMENT ON COLUMN dh_dataforge_dev_task.owner_name IS '维护负责人姓名；由服务端解析用户信息，不作为授权依据';
COMMENT ON COLUMN dh_dataforge_dev_task.contact_number IS '负责人联系电话；由服务端解析用户信息';
COMMENT ON COLUMN dh_dataforge_dev_task.scheduler IS '调度系统：XXL_JOB；调度触发复用平台任务，生产执行委托已有调度执行链路';
COMMENT ON COLUMN dh_dataforge_dev_task.execution_config_json IS '执行属性对象：优先级、工作组、重试、延迟、SQL类型、分隔符及绑定参数；不存储数据源凭据';

DO $constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dataforge_dev_dir_status'
                   AND conrelid = '"datahub-center".dh_dataforge_dev_dir'::regclass) THEN
        ALTER TABLE dh_dataforge_dev_dir ADD CONSTRAINT ck_dataforge_dev_dir_status CHECK (status IN (0, 1));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dataforge_dev_execution_config'
                   AND conrelid = '"datahub-center".dh_dataforge_dev_task'::regclass) THEN
        ALTER TABLE dh_dataforge_dev_task ADD CONSTRAINT ck_dataforge_dev_execution_config
            CHECK (jsonb_typeof(execution_config_json) = 'object');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dataforge_dev_scheduler'
                   AND conrelid = '"datahub-center".dh_dataforge_dev_task'::regclass) THEN
        ALTER TABLE dh_dataforge_dev_task ADD CONSTRAINT ck_dataforge_dev_scheduler CHECK (scheduler IN ('XXL_JOB'));
    END IF;
END $constraints$;

-- 同一租户的同级有效目录名唯一；不同租户和不同父目录可以使用相同名称。
CREATE UNIQUE INDEX IF NOT EXISTS uk_dataforge_dev_dir_sibling_name
    ON dh_dataforge_dev_dir (tenant_id, parent_id, name) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dataforge_dev_workbench_filter
    ON dh_dataforge_dev_task (tenant_id, dir_id, status, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dataforge_dev_record_latest
    ON dh_dataforge_dev_record (tenant_id, task_id, id DESC) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_dataforge_dev_record_daily
    ON dh_dataforge_dev_record (tenant_id, start_time, status) WHERE deleted = 0;

-- 保留已有菜单编号、路由、角色授权及套餐引用，切换入口后仍使用同一个页面组件。
UPDATE system_menu
SET name = '数据开发', icon = 'carbon:code', component_name = 'DataforgeDevWorkbench',
    updater = 'migration:0146', update_time = CURRENT_TIMESTAMP
WHERE deleted = 0 AND trim(both '/' from coalesce(component, '')) = 'dataforge/dev/index';

-- 缺少旧工作台菜单的环境明确失败，避免在未知菜单树中插入入口或擅自授予权限。
DO $menu_preflight$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM system_menu
                   WHERE deleted = 0 AND type = 2
                     AND trim(both '/' from coalesce(component, '')) = 'dataforge/dev/index') THEN
        RAISE EXCEPTION '0146 缺少数据开发工作台菜单，请先完成当前平台全量基线或等价升级';
    END IF;
END $menu_preflight$;

-- 对缺少按钮定义的环境补齐权限目录，授权仍由现有角色管理完成，不升级保存或表级读写权限。
-- Schema 脚本拥有更宽的目标 Schema 维护能力，必须单独授予执行账号；迁移不自动添加任何角色授权。
LOCK TABLE system_menu IN SHARE ROW EXCLUSIVE MODE;
SELECT setval('"datahub-center".system_menu_id_seq',
              GREATEST((SELECT last_value FROM "datahub-center".system_menu_id_seq),
                       COALESCE((SELECT max(id) FROM system_menu), 1)), true);
INSERT INTO system_menu (name, permission, type, sort, parent_id, status, creator, updater)
SELECT button.name, button.permission, 3, button.sort, page.id, 0, 'migration:0146', 'migration:0146'
FROM (VALUES
    ('开发查询', 'dataforge:dev-task:query', 0),
    ('开发保存', 'dataforge:dev-task:save', 1),
    ('开发删除', 'dataforge:dev-task:delete', 2),
    ('开发执行', 'dataforge:dev-task:run', 3),
    ('研发生产发布', 'dataforge:dev-task:publish', 4),
    ('研发写入调试', 'dataforge:dev-task:write', 5),
    ('目标Schema脚本执行', 'governance:safety-script:execute', 6)
) button(name, permission, sort)
JOIN LATERAL (
    SELECT id FROM system_menu WHERE deleted = 0 AND type = 2
      AND trim(both '/' from coalesce(component, '')) = 'dataforge/dev/index'
    ORDER BY id LIMIT 1
) page ON true
WHERE NOT EXISTS (SELECT 1 FROM system_menu existing
                   WHERE existing.deleted = 0 AND existing.permission = button.permission);

COMMIT;

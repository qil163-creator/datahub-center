-- Migration: 0275 数据血缘上报标记：开发任务、数据同步、集成任务、可视化 ETL 流程
-- Purpose: 为四类血缘来源表增加 lineage_report_key 列，记录已成功上报到 governance 的"修订号:解析器版本"。
--          dataforge 的 lineageReportJob 按该标记有界扫描修订号或解析器版本变化的对象重新上报，并对已删除但仍有
--          标记的对象上报 REMOVED 后清空标记。标记只由上报任务按旧值 CAS 更新，不参与任务定义、版本快照或乐观锁。
-- Depends on: 平台库已升级至 0274（0273 已建立 governance 血缘存储，0274 已建立数据血缘菜单）；
--             dh_dataforge_dev_task、dh_dataforge_access、dh_dataforge_integration_task、dh_dataforge_etl_pipeline 已存在。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；ADD COLUMN IF NOT EXISTS，注释可重复覆盖，单事务执行。
-- Rollback: 加法迁移，无不可逆操作。回滚应用版本后可保留该列；如需删除，执行
--           ALTER TABLE <表> DROP COLUMN IF EXISTS lineage_report_key；删除后上报任务会对全部来源重新上报一次。
-- 不回填：新增列为空，首轮上报任务会对全部已保存的来源对象上报一次（每类每轮最多 50 个，串行）。
-- 锁：每张表短时 ACCESS EXCLUSIVE 锁（只改目录，不重写表），锁等待超过十秒即整体失败。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE dh_dataforge_dev_task ADD COLUMN IF NOT EXISTS lineage_report_key varchar(64);
COMMENT ON COLUMN dh_dataforge_dev_task.lineage_report_key IS '血缘上报标记：已成功上报的"当前版本编号:解析器版本"；为空表示尚未上报';

ALTER TABLE dh_dataforge_access ADD COLUMN IF NOT EXISTS lineage_report_key varchar(64);
COMMENT ON COLUMN dh_dataforge_access.lineage_report_key IS '血缘上报标记：已成功上报的"运行事实修订号:解析器版本"；为空表示尚未上报';

ALTER TABLE dh_dataforge_integration_task ADD COLUMN IF NOT EXISTS lineage_report_key varchar(64);
COMMENT ON COLUMN dh_dataforge_integration_task.lineage_report_key IS '血缘上报标记：已成功上报的"任务修订号:解析器版本"；为空表示尚未上报';

ALTER TABLE dh_dataforge_etl_pipeline ADD COLUMN IF NOT EXISTS lineage_report_key varchar(64);
COMMENT ON COLUMN dh_dataforge_etl_pipeline.lineage_report_key IS '血缘上报标记：已成功上报的"发布修订号:解析器版本"；为空表示尚未上报';

COMMIT;

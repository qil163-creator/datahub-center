-- Migration: 0293 数据质量表行数波动检测
-- Purpose: 新增“表行数波动检测”（策略 ROW_COUNT_FLUCTUATION，评估目录编号 120）：每次检测统计（可带过滤条件的）表行数，
--          按环比（上次检测）、同比（1 天、7 天、上月同日、去年同日同一时段）的增降幅上限、动态阈值（近 N 次历史的
--          均值±k·标准差或中位数±k·MAD，可只取同一星期几）及固定最少/最多行数判定。质量规则的 Where 条件同时支持
--          ${bizdate}、${bizdate_nodash}、${bizmonth} 业务日期变量（实例调度时间的前一天），执行时替换为日期字面量。
--          历史基准直接取自检测结果明细，本迁移为 dh_governance_quality_audit_log 增加六列：
--            metric_key   表级指标键（ROW_COUNT），逐行规则为空；
--            metric_value 表级指标值（表行数）；
--            metric_scope 指标口径摘要（受检表与 Where 条件模板的 SHA-256），口径变化后不与旧值比较；
--            metric_lower、metric_upper 本次判定的预期区间（供趋势图展示）；
--            biz_date     本次替换日期变量所用的业务日期（所有规则都会记录）；
--          并建立只覆盖表级指标结果的部分索引，供按任务、检查对象、指标键与检测时间读取历史基准与趋势。
--          策略与评估目录在应用代码中登记，不新增字典、菜单与权限；旧检测结果新增列为空，不回填。
-- Depends on: 0292_dataforge_run-failure-alert.sql（编号前驱）；dh_governance_quality_audit_log 已存在
--             （含 task_id、check_object_id、check_time、deleted 列）。
-- Target: 已有 PostgreSQL 平台库，schema 为 datahub-center；新库基线同步包含本迁移。
-- Repeatable: 是；列使用 ADD COLUMN IF NOT EXISTS，索引带存在性保护，注释可重复执行。单事务执行。
--             新增列可空、无默认值，不重写表；建立部分索引需扫描全表并在建立期间阻塞该表写入（锁等待上限 10 秒，超时即失败，
--             可稍后重试），检测结果表很大时建议在质量任务低峰执行。
-- Rollback: 应用回退后可执行 DROP INDEX IF EXISTS "datahub-center".idx_quality_audit_metric_history; 再删除上述六列。
--           会丢失表行数波动检测的历史行数，之后重新启用时首次检测没有基准、只记录行数。旧版本应用不读取这些新增列。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '600s';

ALTER TABLE dh_governance_quality_audit_log
    ADD COLUMN IF NOT EXISTS metric_key varchar(32),
    ADD COLUMN IF NOT EXISTS metric_value numeric(38, 6),
    ADD COLUMN IF NOT EXISTS metric_scope varchar(64),
    ADD COLUMN IF NOT EXISTS metric_lower numeric(38, 6),
    ADD COLUMN IF NOT EXISTS metric_upper numeric(38, 6),
    ADD COLUMN IF NOT EXISTS biz_date date;

COMMENT ON COLUMN dh_governance_quality_audit_log.metric_key IS '表级指标键：ROW_COUNT-表行数；逐行规则为空';
COMMENT ON COLUMN dh_governance_quality_audit_log.metric_value IS '表级指标值（表行数），作为后续环比、同比的历史基准';
COMMENT ON COLUMN dh_governance_quality_audit_log.metric_scope IS '指标口径摘要：受检表与 Where 条件模板的 SHA-256，口径变化后不与旧值比较';
COMMENT ON COLUMN dh_governance_quality_audit_log.metric_lower IS '本次判定的预期区间下限（含），为空表示不限';
COMMENT ON COLUMN dh_governance_quality_audit_log.metric_upper IS '本次判定的预期区间上限（含），为空表示不限';
COMMENT ON COLUMN dh_governance_quality_audit_log.biz_date IS '业务日期：替换 Where 条件中 ${bizdate} 等变量所用的日期（实例调度时间的前一天）';

CREATE INDEX IF NOT EXISTS idx_quality_audit_metric_history
    ON dh_governance_quality_audit_log (task_id, check_object_id, metric_key, check_time DESC)
    WHERE deleted = 0 AND metric_key IS NOT NULL;
COMMENT ON INDEX idx_quality_audit_metric_history IS '表级指标历史基准：按任务、检查对象、指标键与检测时间读取';

COMMIT;

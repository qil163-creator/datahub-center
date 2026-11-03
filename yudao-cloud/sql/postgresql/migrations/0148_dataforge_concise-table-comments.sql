-- Migration: 0148 数据集成表注释简化
-- Purpose: 将数据同步、可视化ETL、在线填报等表的长描述改为简洁用途名称，原详细说明保留在全量基线的源码注释中。
-- Depends on: 0147_dataforge_native-engine-execution.sql 已执行或具有等价平台基线状态；本脚本列出的数据集成表均已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；仅以固定文本更新表注释，重复执行得到相同结果。
-- Rollback: 失败整笔回滚；无不可逆操作。提交后可按全量基线保留的原详细说明创建后续编号迁移，恢复各表注释。
-- 注释更新可能等待对象锁；锁等待上限十秒，超时整笔回滚。不修改表结构、业务数据、字段注释或权限。

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 数据同步
COMMENT ON TABLE "datahub-center".dh_dataforge_access_checkpoint IS '数据同步-表级同步游标';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_group_pin IS '数据同步-组替换占用';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_group_recovery IS '数据同步-组替换恢复材料';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_lineage IS '数据同步-血缘投递记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_table_page IS '数据同步-整表恢复镜像分页';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_table_pin IS '数据同步-整表恢复占用';
COMMENT ON TABLE "datahub-center".dh_dataforge_access_table_recovery IS '数据同步-整表替换恢复材料';

-- 可视化ETL
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_archive IS '可视化ETL-运行归档';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_archive_action IS '可视化ETL-归档操作审计';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_environment IS '可视化ETL-环境';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_environment_binding IS '可视化ETL-环境连接绑定';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_file_audit IS '可视化ETL-文件输出审计';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_file_output IS '可视化ETL-文件输出账本';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_lineage_delivery IS '可视化ETL-血缘投递记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_quality_batch IS '可视化ETL-质量检测批次';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_quality_issue IS '可视化ETL-质量问题证据';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_quality_retry IS '可视化ETL-质量登记重试记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_replay IS '可视化ETL-整改回放记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_target_action IS '可视化ETL-目标恢复操作审计';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_target_batch IS '可视化ETL-目标写入批次';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_template IS '可视化ETL-团队模板';
COMMENT ON TABLE "datahub-center".dh_dataforge_etl_version IS '可视化ETL-发布版本';

-- 在线填报
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_assignee IS '在线填报-工作项填报人';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_assignment_log IS '在线填报-填报人变更日志';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_correction IS '在线填报-数据更正实例';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_draft_row IS '在线填报-草稿行';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_event_inbox IS '在线填报-外部事件收件箱';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_event_outbox IS '在线填报-可靠事件发件箱';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_merge_batch IS '在线填报-合并批次';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_merge_detail IS '在线填报-合并明细';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_notify_record IS '在线填报-通知与催办记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_operation IS '在线填报-操作日志';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_plan IS '在线填报-采集计划';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_plan_org IS '在线填报-计划目标组织';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_plan_org_assignee IS '在线填报-计划组织默认填报人';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_review_annotation IS '在线填报-审批批注';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_submission IS '在线填报-提交记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_submission_row IS '在线填报-提交快照行';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_table_version IS '在线填报-模板版本';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_target_scope_registry IS '在线填报-目标表写入登记';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_task IS '在线填报-周期任务';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_validation_issue IS '在线填报-校验问题';
COMMENT ON TABLE "datahub-center".dh_dataforge_fill_work_item IS '在线填报-组织填报实例';

-- 日志采集
COMMENT ON TABLE "datahub-center".dh_dataforge_log_file_state IS '日志采集-文件位点';

-- 消息集成
COMMENT ON TABLE "datahub-center".dh_dataforge_message_log IS '消息集成-失败消息记录';

-- 数据推送
COMMENT ON TABLE "datahub-center".dh_dataforge_push_alert IS '数据推送-告警投递记录';
COMMENT ON TABLE "datahub-center".dh_dataforge_push_alert_disposition IS '数据推送-告警处置审计';

-- 运行控制
COMMENT ON TABLE "datahub-center".dh_dataforge_run_command IS '数据集成-运行控制回执';

-- 同步边界
COMMENT ON TABLE "datahub-center".dh_dataforge_sync_boundary IS '数据同步-治理提交边界';
COMMENT ON TABLE "datahub-center".dh_dataforge_sync_clock IS '数据同步-目标事务序号';

COMMIT;

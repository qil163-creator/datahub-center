-- Migration: 0149 数据平台表注释规范化
-- Purpose: 将治理、资产、服务和建模表的冗长描述统一为“业务域-用途”，原详细说明保留在全量基线的源码注释中。
-- Depends on: 0148_dataforge_concise-table-comments.sql 已执行或具有等价平台基线状态；本脚本列出的现行表均已存在。
-- Target: 已有 PostgreSQL 平台库，固定 schema 为 datahub-center；新库使用包含本迁移的全量基线。
-- Repeatable: 是；仅以固定文本更新表注释，重复执行得到相同结果。
-- Rollback: 失败整笔回滚；无不可逆操作。提交后可依据本次修改前的全量基线或保留的表说明，创建后续编号迁移恢复原表注释。
-- 注释更新可能等待对象锁；锁等待上限十秒，超时整笔回滚。仅修改表注释，保留字段注释、表结构、业务数据及权限。

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 数据资产
COMMENT ON TABLE "datahub-center".dh_asset_catalog_link_operation IS '数据资产-目录挂接操作';
COMMENT ON TABLE "datahub-center".dh_asset_catalog_version IS '数据资产-政务目录版本';
COMMENT ON TABLE "datahub-center".dh_asset_data_item IS '数据资产-资源数据项';
COMMENT ON TABLE "datahub-center".dh_asset_delivery_op IS '数据资产-交付操作台账';
COMMENT ON TABLE "datahub-center".dh_asset_etl_batch_binding IS '数据资产-ETL批次关联';
COMMENT ON TABLE "datahub-center".dh_asset_grant_source IS '数据资产-授权来源';
COMMENT ON TABLE "datahub-center".dh_asset_review_ticket IS '数据资产-数据校核工单';
COMMENT ON TABLE "datahub-center".dh_asset_tech_link IS '数据资产-技术对象关联';

-- 元数据管理
COMMENT ON TABLE "datahub-center".dh_governance_catalog_link_receipt IS '元数据管理-目录挂接回执';
COMMENT ON TABLE "datahub-center".dh_governance_catalog_link_source IS '元数据管理-目录来源版本';
COMMENT ON TABLE "datahub-center".dh_governance_meta_change IS '元数据管理-变更明细';
COMMENT ON TABLE "datahub-center".dh_governance_meta_collect_instance IS '元数据管理-采集实例';
COMMENT ON TABLE "datahub-center".dh_governance_meta_collect_log IS '元数据管理-采集实例日志';
COMMENT ON TABLE "datahub-center".dh_governance_meta_collect_task IS '元数据管理-采集任务';
COMMENT ON TABLE "datahub-center".dh_governance_meta_column IS '元数据管理-字段';
COMMENT ON TABLE "datahub-center".dh_governance_meta_db IS '元数据管理-库';
COMMENT ON TABLE "datahub-center".dh_governance_meta_table IS '元数据管理-表';
COMMENT ON TABLE "datahub-center".dh_governance_meta_table_snapshot IS '元数据管理-表结构快照';
COMMENT ON TABLE "datahub-center".dh_governance_meta_tag IS '元数据管理-标签';
COMMENT ON TABLE "datahub-center".dh_governance_meta_tag_rel IS '元数据管理-标签关联';
COMMENT ON TABLE "datahub-center".dh_meta_ingest_event IS '元数据管理-接收事件台账';

-- 数据质量
COMMENT ON TABLE "datahub-center".dh_governance_category_data_quality IS '数据质量-质量维度分类';
COMMENT ON TABLE "datahub-center".dh_governance_etl_quality_report IS '数据质量-ETL内联报告';
COMMENT ON TABLE "datahub-center".dh_governance_quality_alert IS '数据质量-告警记录';
COMMENT ON TABLE "datahub-center".dh_governance_quality_audit_log IS '数据质量-检测结果明细';
COMMENT ON TABLE "datahub-center".dh_governance_quality_audit_rule IS '数据质量-稽查规则库';
COMMENT ON TABLE "datahub-center".dh_governance_quality_brief IS '数据质量-质检简报';
COMMENT ON TABLE "datahub-center".dh_governance_quality_brief_column IS '数据质量-简报模板附列';
COMMENT ON TABLE "datahub-center".dh_governance_quality_brief_paragraph IS '数据质量-简报模板段落';
COMMENT ON TABLE "datahub-center".dh_governance_quality_brief_template IS '数据质量-质检简报模板';
COMMENT ON TABLE "datahub-center".dh_governance_quality_check_object IS '数据质量-检查对象';
COMMENT ON TABLE "datahub-center".dh_governance_quality_clean_rule IS '数据质量-清洗规则';
COMMENT ON TABLE "datahub-center".dh_governance_quality_config_category IS '数据质量-规则配置目录';
COMMENT ON TABLE "datahub-center".dh_governance_quality_etl_batch_run IS '数据质量-ETL批次检测绑定';
COMMENT ON TABLE "datahub-center".dh_governance_quality_etl_task_creation IS '数据质量-ETL检测任务创建回执';
COMMENT ON TABLE "datahub-center".dh_governance_quality_exception_expiry_cursor IS '数据质量-例外到期扫描游标';
COMMENT ON TABLE "datahub-center".dh_governance_quality_issue_data IS '数据质量-问题数据';
COMMENT ON TABLE "datahub-center".dh_governance_quality_org_score IS '数据质量-机构评分';
COMMENT ON TABLE "datahub-center".dh_governance_quality_repair_order IS '数据质量-修复工单';
COMMENT ON TABLE "datahub-center".dh_governance_quality_report_log IS '数据质量-报告生成记录';
COMMENT ON TABLE "datahub-center".dh_governance_quality_rule IS '数据质量-质量规则库';
COMMENT ON TABLE "datahub-center".dh_governance_quality_rule_set IS '数据质量-规则集';
COMMENT ON TABLE "datahub-center".dh_governance_quality_rule_set_item IS '数据质量-规则集明细';
COMMENT ON TABLE "datahub-center".dh_governance_quality_task IS '数据质量-质量任务';
COMMENT ON TABLE "datahub-center".dh_governance_quality_task_instance IS '数据质量-任务实例';
COMMENT ON TABLE "datahub-center".dh_governance_quality_task_object IS '数据质量-任务检查对象关联';
COMMENT ON TABLE "datahub-center".dh_governance_quality_task_rule IS '数据质量-任务规则关联';
COMMENT ON TABLE "datahub-center".dh_governance_quality_task_template IS '数据质量-任务模板';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order IS '数据质量-问题处理工单';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order_action IS '数据质量-工单操作记录';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order_etl_report IS '数据质量-工单内联报告关联';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order_issue IS '数据质量-工单问题关联';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order_notify IS '数据质量-工单通知记录';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order_notify_review IS '数据质量-工单通知核验审计';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order_recheck IS '数据质量-工单复检轮次';
COMMENT ON TABLE "datahub-center".dh_governance_quality_work_order_submission IS '数据质量-工单整改与解释提交';

-- 数据治理
COMMENT ON TABLE "datahub-center".dh_governance_lineage_apply_watermark IS '数据治理-血缘应用水位';
COMMENT ON TABLE "datahub-center".dh_governance_lineage_event_outbox IS '数据治理-血缘事件发件箱';

-- 主数据管理
COMMENT ON TABLE "datahub-center".dh_governance_master_distribute_alert IS '主数据管理-派发告警投递记录';
COMMENT ON TABLE "datahub-center".dh_governance_master_distribute_alert_disposition IS '主数据管理-派发告警处置审计';

-- 数据安全
COMMENT ON TABLE "datahub-center".dh_governance_safety_access_log IS '数据安全-敏感数据访问审计';
COMMENT ON TABLE "datahub-center".dh_governance_safety_category IS '数据安全-数据分类';
COMMENT ON TABLE "datahub-center".dh_governance_safety_field_permission IS '数据安全-字段访问权限';
COMMENT ON TABLE "datahub-center".dh_governance_safety_field_rel IS '数据安全-字段脱敏绑定';
COMMENT ON TABLE "datahub-center".dh_governance_safety_field_tag IS '数据安全-敏感字段标签';
COMMENT ON TABLE "datahub-center".dh_governance_safety_identify_rule IS '数据安全-敏感数据识别规则';
COMMENT ON TABLE "datahub-center".dh_governance_safety_key_ref IS '数据安全-外部密钥引用';
COMMENT ON TABLE "datahub-center".dh_governance_safety_level IS '数据安全-数据分级';
COMMENT ON TABLE "datahub-center".dh_governance_safety_replace_item IS '数据安全-脱敏替换明细';
COMMENT ON TABLE "datahub-center".dh_governance_safety_rule IS '数据安全-脱敏规则';
COMMENT ON TABLE "datahub-center".dh_governance_safety_rule_ver IS '数据安全-脱敏规则版本';
COMMENT ON TABLE "datahub-center".dh_governance_safety_scan_run IS '数据安全-敏感数据扫描记录';
COMMENT ON TABLE "datahub-center".dh_governance_safety_static_task IS '数据安全-静态脱敏任务';
COMMENT ON TABLE "datahub-center".dh_governance_safety_static_task_log IS '数据安全-静态脱敏执行记录';
COMMENT ON TABLE "datahub-center".dh_governance_safety_table_permission IS '数据安全-数据表访问权限';
COMMENT ON TABLE "datahub-center".dh_governance_safety_whitelist IS '数据安全-脱敏白名单';
COMMENT ON TABLE "datahub-center".dh_governance_safety_whitelist_approval IS '数据安全-脱敏白名单审批记录';
COMMENT ON TABLE "datahub-center".dh_governance_safety_whitelist_subject IS '数据安全-脱敏白名单主体';
COMMENT ON TABLE "datahub-center".dh_governance_safety_write_audit IS '数据安全-SQL写授权审计';

-- 数据标准
COMMENT ON TABLE "datahub-center".dh_governance_standard_code_item IS '数据标准-标准码值';
COMMENT ON TABLE "datahub-center".dh_governance_standard_code_mapping IS '数据标准-码值有效期映射';
COMMENT ON TABLE "datahub-center".dh_governance_standard_code_set IS '数据标准-标准码表';
COMMENT ON TABLE "datahub-center".dh_governance_standard_doc IS '数据标准-标准文件登记';
COMMENT ON TABLE "datahub-center".dh_governance_standard_element IS '数据标准-标准数据元';
COMMENT ON TABLE "datahub-center".dh_governance_standard_mapping IS '数据标准-落标映射';
COMMENT ON TABLE "datahub-center".dh_governance_standard_mapping_candidate IS '数据标准-落标候选与评分';
COMMENT ON TABLE "datahub-center".dh_governance_standard_mapping_recommend_run IS '数据标准-落标推荐运行';
COMMENT ON TABLE "datahub-center".dh_governance_standard_object_version IS '数据标准-对象版本快照';
COMMENT ON TABLE "datahub-center".dh_governance_standard_release_gate IS '数据标准-发布门禁与豁免审计';
COMMENT ON TABLE "datahub-center".dh_governance_standard_word_root IS '数据标准-命名词根库';

-- 数据集市
COMMENT ON TABLE "datahub-center".dh_market_delivery_operation IS '数据集市-交付操作';

-- 数据建模
COMMENT ON TABLE "datahub-center".dh_modeling_biz_category IS '数据建模-业务分类';
COMMENT ON TABLE "datahub-center".dh_modeling_logic_model IS '数据建模-逻辑模型';
COMMENT ON TABLE "datahub-center".dh_modeling_logic_model_column IS '数据建模-逻辑模型字段';
COMMENT ON TABLE "datahub-center".dh_modeling_logic_model_version IS '数据建模-逻辑模型版本';
COMMENT ON TABLE "datahub-center".dh_modeling_model_materialized IS '数据建模-模型物化发布记录';
COMMENT ON TABLE "datahub-center".dh_modeling_model_materialized_log IS '数据建模-模型物化发布日志';
COMMENT ON TABLE "datahub-center".dh_modeling_subject_domain IS '数据建模-主题域';

-- 数据服务
COMMENT ON TABLE "datahub-center".dh_supply_api_operation IS '数据服务-开放写请求台账';
COMMENT ON TABLE "datahub-center".dh_supply_api_release IS '数据服务-API发布快照';
COMMENT ON TABLE "datahub-center".dh_supply_etl_grant_audit IS '数据服务-ETL批次授权审计';
COMMENT ON TABLE "datahub-center".dh_supply_grant_source IS '数据服务-授权来源';
COMMENT ON TABLE "datahub-center".dh_supply_legacy_admin_disposal IS '数据服务-历史授权处置审计';
COMMENT ON TABLE "datahub-center".dh_supply_quota_balance IS '数据服务-消费额度账本';
COMMENT ON TABLE "datahub-center".dh_supply_quota_receipt IS '数据服务-额度预占与确认回执';
COMMENT ON TABLE "datahub-center".dh_supply_review_disposition IS '数据服务-授权核查结论';

COMMIT;

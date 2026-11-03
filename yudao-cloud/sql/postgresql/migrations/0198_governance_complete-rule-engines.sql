-- Migration: 0198 稽查与清洗规则引擎目录补齐。
-- Purpose: 将全部二十八项稽查、四十项清洗目录关联到可执行策略，补正表级绑定；不修改用户规则参数或历史冻结计划。
-- Depends on: 0197_dataforge_connect-only-connectors.sql；0190、0191 的规则目录与标准工作台迁移已执行，内置目录仍保留原编码和名称。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；仅补齐空策略与自动停用状态，已配置策略的人工停用状态保持不变；冲突时整个事务回滚。
-- Rollback: 无删除和数据丢失；失败自动回滚，提交后如需撤销，应从执行前备份恢复这些目录行的策略、级别和状态，不回退已冻结计划。
-- 风险：短暂锁定两个规则目录；仅解锁可配置的内置模板，使用前仍须通过算法参数校验，不会自动运行任何任务。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
LOCK TABLE dh_governance_audit_rule, dh_governance_clean_rule IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE audit_engine_seed(code text, name text, strategy_key text, rule_level smallint) ON COMMIT DROP;
INSERT INTO audit_engine_seed VALUES
('107','字段字符类型校验','CHARACTER_VALIDATION',1),
('108','字段长度范围校验','LENGTH_VALIDATION',1),
('109','日期时间格式校验','DATE_FORMAT_VALIDATION',1),
('110','数值字段精度校验','DECIMAL_PRECISION_VALIDATION',1),
('111','枚举值校验','ENUM_VALIDATION',1),
('112','主数据引用一致性校验','FOREIGN_KEY_VALIDATION',1),
('113','逻辑模型一致性校验','MODEL_STRUCTURE_VALIDATION',2),
('114','字段元信息完整性校验','COLUMN_METADATA_COMPLETENESS',2),
('115','时间字段顺序逻辑校验','TIME_ORDER_VALIDATION',2),
('116','数值字段逻辑关系校验','NUMERIC_RELATION_VALIDATION',2),
('117','状态字段依赖项校验','CONDITIONAL_REQUIRED_VALIDATION',2),
('118','互斥字段冲突校验','MUTUAL_EXCLUSION_VALIDATION',2),
('119','敏感字段脱敏校验','SENSITIVE_MASK_VALIDATION',1),
('203','必填字段非空校验','NOT_NULL_VALIDATION',1),
('204','非法占位值识别','PLACEHOLDER_VALIDATION',1),
('205','字段组完整性校验','GROUP_FIELD_COMPLETENESS',2),
('206','整记录缺失检查校验','RECORD_COMPLETENESS_VALIDATION',2),
('207','条件字段完整性校验','CONDITIONAL_REQUIRED_VALIDATION',2),
('306','数值字段范围校验','NUMERIC_RANGE_VALIDATION',1),
('307','数值字段逻辑值合法校验','NUMERIC_RANGE_VALIDATION',1),
('308','数值字段异常值识别','NUMERIC_OUTLIER_VALIDATION',1),
('309','脏数据识别','PLACEHOLDER_VALIDATION',1),
('310','单字段唯一性校验','UNIQUE_VALIDATION',1),
('311','多字段组合唯一性校验','COMPOSITE_UNIQUENESS_VALIDATION',2),
('403','跨表字段值一致性校验','CROSS_TABLE_CONSISTENCY',2),
('404','外键有效性校验','FOREIGN_KEY_VALIDATION',1),
('405','状态字段一致性校验','STATE_CONSISTENCY_VALIDATION',2),
('504','时间字段先后顺序校验','TIME_ORDER_VALIDATION',2);

CREATE TEMP TABLE clean_engine_seed(code text, name text, strategy_key text, rule_level smallint) ON COMMIT DROP;
INSERT INTO clean_engine_seed VALUES
('001','数值边界调整','WITHIN_BOUNDARY',1),
('002','字段值替换','VALUE_REPLACE',1),
('003','字符串转数值','STRING_TO_NUMBER',1),
('004','字符串转日期','STRING_TO_DATE',1),
('005','任意类型转布尔值','TO_BOOLEAN',1),
('006','手机号格式统一','PHONE_FORMAT_STD',1),
('007','日期格式统一','DATE_FORMAT_STD',1),
('008','小数位统一','FIX_TO_PRECISION',1),
('009','去除字段空格','REMOVE_WHITESPACE',1),
('010','字段前缀/后缀统一','ADD_PREFIX_SUFFIX',1),
('011','正则表达式替换','SIMPLE_REPLACE',1),
('012','超长字段截断','STRING_SUBSTR',1),
('013','字段补零','ZERO_PAD',1),
('014','数值空值填充','NUMBER_FILL_NULL',1),
('015','字符串空值填充','STRING_FILL_NULL',1),
('016','布尔空值填充','BOOLEAN_FILL_NULL',1),
('017','日期空值填充','DATE_FILL_NULL',1),
('018','全字段为空删除','REMOVE_EMPTY_ROW',2),
('019','组合字段为空删除','REMOVE_EMPTY_COMBINATION',2),
('020','单位标准化类','UNIT_CONVERSION',1),
('021','字段值转大写','TO_UPPERCASE',1),
('022','字段值转小写','TO_LOWERCASE',1),
('023','字段值首字母大写','CAPITALIZE_WORDS',1),
('024','枚举值映射标准化','MENU_CUSTOM',1),
('025','按主键去重（保留首条）','PK_DEDUP_FIRST',2),
('026','按主键去重（按时间字段保留最新）','PK_DEDUP_LATEST',2),
('027','按优先级字段保留记录','DEDUP_BY_PRIORITY',2),
('028','按非空字段优先保留记录','DEDUP_PREFER_NON_NULL',2),
('029','按组合字段去重（保留最新或首条）','KEEP_LATEST_OR_FIRST',2),
('030','替换无效数值','INVALID_NUMBER_REPLACE',1),
('031','替换非法枚举值','INVALID_ENUM_REPLACE',1),
('032','替换无效时间格式','INVALID_DATE_REPLACE',1),
('033','替换非法布尔值','INVALID_BOOLEAN_REPLACE',1),
('034','关键词脱敏','KEYWORD_MASK',1),
('035','敏感词替换','SENSITIVE_WORD_REPLACE',1),
('036','修正未来时间值','FIX_FUTURE_DATE',1),
('037','校正超前时间差值','CLAMP_FUTURE_OFFSET',1),
('038','填补空时间为当前时间','TIME_FILL_NULL',1),
('039','清理过期记录','CHECK_EXPIRATION',1),
('040','修正历史时间边界','FIX_HISTORICAL_DATE',1);

DO $catalog$
DECLARE item record; matches integer;
BEGIN
 FOR item IN SELECT * FROM audit_engine_seed LOOP
  SELECT count(*) INTO matches FROM dh_governance_audit_rule WHERE code=item.code AND name=item.name AND deleted=0;
  IF matches<>1 OR EXISTS (SELECT 1 FROM dh_governance_audit_rule WHERE code=item.code AND deleted=0 AND
      (name<>item.name OR (nullif(btrim(strategy_key),'') IS NOT NULL AND strategy_key<>item.strategy_key))) THEN
   RAISE EXCEPTION '0198 稽查内置目录归属冲突: %',item.code;
  END IF;
 END LOOP;
 FOR item IN SELECT * FROM clean_engine_seed LOOP
  SELECT count(*) INTO matches FROM dh_governance_clean_rule WHERE code=item.code AND name=item.name AND deleted=0;
  IF matches<>1 OR EXISTS (SELECT 1 FROM dh_governance_clean_rule WHERE code=item.code AND deleted=0 AND
      (name<>item.name OR (nullif(btrim(strategy_key),'') IS NOT NULL AND strategy_key<>item.strategy_key))) THEN
   RAISE EXCEPTION '0198 清洗内置目录归属冲突: %',item.code;
  END IF;
 END LOOP;
END $catalog$;

UPDATE dh_governance_audit_rule t SET strategy_key=s.strategy_key,rule_level=s.rule_level,
 status=CASE WHEN nullif(btrim(t.strategy_key),'') IS NULL THEN 0 ELSE t.status END,
 updater='migration:0198',update_time=CURRENT_TIMESTAMP
FROM audit_engine_seed s WHERE t.code=s.code AND t.name=s.name AND t.deleted=0
 AND (nullif(btrim(t.strategy_key),'') IS NULL OR t.rule_level<>s.rule_level);

UPDATE dh_governance_clean_rule t SET strategy_key=s.strategy_key,rule_level=s.rule_level,
 status=CASE WHEN nullif(btrim(t.strategy_key),'') IS NULL THEN 0 ELSE t.status END,
 updater='migration:0198',update_time=CURRENT_TIMESTAMP
FROM clean_engine_seed s WHERE t.code=s.code AND t.name=s.name AND t.deleted=0
 AND (nullif(btrim(t.strategy_key),'') IS NULL OR t.rule_level<>s.rule_level);
COMMIT;

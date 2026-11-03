-- Migration: 0214 指标单位字典及七类单位目录。
-- Purpose: 将已核实的单位叶子名称作为字典值，备注保存分组，复用系统字典能力。
-- Depends on: 0213_modeling_metric-entity-binding.sql；既有 system_dict_type、system_dict_data 及其序列。
-- Target: 已有 PostgreSQL 平台库；新库基线已包含本迁移。
-- Repeatable: 是；仅补充缺失的有效字典类型和键值，不覆盖管理员已维护的标签、备注或状态。
-- Rollback: 无破坏性操作；如需撤回，先确认指标单位使用情况，再删除本迁移创建且未被修改的字典记录。

BEGIN;
SET LOCAL search_path TO "datahub-center", public;
CREATE TEMP TABLE metric_unit_seed (sort integer, label text, category text) ON COMMIT DROP;
INSERT INTO metric_unit_seed VALUES
    (1, '其他', '其他'),
    (2, '分(人民币)', '货币单位'),
    (3, '元(人民币)', '货币单位'),
    (4, '万元(人民币)', '货币单位'),
    (5, '百万元(人民币)', '货币单位'),
    (6, '千万元(人民币)', '货币单位'),
    (7, '亿元(人民币)', '货币单位'),
    (8, '分(美元)', '货币单位'),
    (9, '元(美元)', '货币单位'),
    (10, '元(欧元)', '货币单位'),
    (11, '元(港币)', '货币单位'),
    (12, '天', '时间单位'),
    (13, '月', '时间单位'),
    (14, '周', '时间单位'),
    (15, '年', '时间单位'),
    (16, '小时', '时间单位'),
    (17, '分钟', '时间单位'),
    (18, '秒', '时间单位'),
    (19, '季度', '时间单位'),
    (20, '毫秒', '时间单位'),
    (21, '小数', '比例单位'),
    (22, '百分位数', '比例单位'),
    (23, '千分位数', '比例单位'),
    (24, '排名(名次)', '名次'),
    (25, '户', '对象量词'),
    (26, '笔', '对象量词'),
    (27, '件', '对象量词'),
    (28, '个', '对象量词'),
    (29, '次', '对象量词'),
    (30, '人日', '对象量词'),
    (31, '家', '对象量词'),
    (32, '手', '对象量词'),
    (33, '张', '对象量词'),
    (34, '包', '对象量词'),
    (35, '吨', '重量单位'),
    (36, '公斤', '重量单位');
SELECT setval('system_dict_type_seq', greatest((SELECT last_value FROM system_dict_type_seq), coalesce((SELECT max(id) FROM system_dict_type), 1)));
SELECT setval('system_dict_data_seq', greatest((SELECT last_value FROM system_dict_data_seq), coalesce((SELECT max(id) FROM system_dict_data), 1)));
INSERT INTO system_dict_type (id, name, type, status, remark, creator, updater)
SELECT nextval('system_dict_type_seq'), '指标单位', 'modeling_indicator_unit', 0,
       '单位名称作为键值，数据备注表示单位分组', 'migration:0214', 'migration:0214'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_type WHERE type = 'modeling_indicator_unit' AND deleted = 0);
INSERT INTO system_dict_data (id, sort, label, value, dict_type, status, remark, creator, updater)
SELECT nextval('system_dict_data_seq'), s.sort, s.label, s.label, 'modeling_indicator_unit', 0,
       s.category, 'migration:0214', 'migration:0214'
FROM metric_unit_seed s
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data d WHERE d.dict_type = 'modeling_indicator_unit' AND d.value = s.label AND d.deleted = 0)
ORDER BY s.sort;
COMMIT;

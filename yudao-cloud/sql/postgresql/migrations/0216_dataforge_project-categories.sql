-- Migration: 0216 数据集成与数据开发类目管理。
-- Purpose: 复用两类事实表迁入已核实的各34条类目，在基础管理的类目管理下建立独立页面及精确权限。
-- Depends on: 0215_dataforge_integration-workbench.sql；既有开发目录状态、描述、任务归属及系统菜单、角色授权表已存在。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的全量基线。
-- Repeatable: 是；来源创建人列作为首次导入标记，已完成导入后不再清理或补回源记录，重放保留用户后续新增、修改与删除。
-- Rollback: 失败时业务数据、结构及菜单回滚；序列可能前移但不影响重放。首次导入删除旧开发类目并将其任务归属改为顶级，提交后须从执行前备份恢复旧类目及归属。
-- 首次导入不删除开发任务；集成目录只补齐0215迁入且未被用户修改的来源记录，不覆盖后续业务修改。
-- 表结构和首次迁入需要短时锁表；建议停写窗口执行，锁等待超过十秒即失败，不在共享或生产环境自动执行。
-- 本迁移只包含业务类目及显示姓名，不迁移源账号、凭据、项目租户或源任务引用计数。

BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

-- 与目录和任务写入互斥，确保旧目录清理期间不会出现新的悬空归属。
LOCK TABLE dh_dataforge_dev_dir, dh_dataforge_dev_task,
    dh_dataforge_integration_category, system_menu, system_role_menu IN SHARE ROW EXCLUSIVE MODE;

-- 列的存在性与首次导入在同一事务内提交；重放不会重复重置目录或恢复用户删除的数据。
CREATE TEMP TABLE qdata_category_import_scope ON COMMIT DROP AS
SELECT
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'datahub-center' AND table_name = 'dh_dataforge_dev_dir'
                  AND column_name = 'source_creator_name') AS development_first,
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'datahub-center' AND table_name = 'dh_dataforge_integration_category'
                  AND column_name = 'source_creator_name') AS integration_first;

ALTER TABLE dh_dataforge_dev_dir ADD COLUMN IF NOT EXISTS source_creator_name varchar(64);
ALTER TABLE dh_dataforge_integration_category ADD COLUMN IF NOT EXISTS source_creator_name varchar(64);
COMMENT ON COLUMN dh_dataforge_dev_dir.source_creator_name IS '迁入来源的创建人显示姓名；不作为登录身份或授权依据';
COMMENT ON COLUMN dh_dataforge_integration_category.source_creator_name IS '迁入来源的创建人显示姓名；不作为登录身份或授权依据';

CREATE TEMP TABLE qdata_project_category_seed (
    kind varchar(16) NOT NULL,
    id bigint NOT NULL,
    parent_id bigint NOT NULL,
    name varchar(50) NOT NULL,
    sort integer NOT NULL,
    description varchar(256),
    valid_flag boolean NOT NULL,
    source_creator_name varchar(64),
    create_time timestamp NOT NULL,
    update_time timestamp NOT NULL,
    PRIMARY KEY (kind, id)
) ON COMMIT DROP;

INSERT INTO qdata_project_category_seed
    (kind, id, parent_id, name, sort, description, valid_flag, source_creator_name, create_time, update_time)
VALUES
    ('integration', 1, 0, '数据同步_第三方库到ODS', 0, '用于管理从第三方业务库、接口及文件等数据源采集并同步至ODS层的数据集成任务，保留源端原始数据和采集过程信息。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('integration', 2, 0, '数据清洗_ODS到DIM', 1, '用于管理ODS层数据经清洗、去重、标准化和主数据关联后写入DIM层的数据集成任务，形成统一公共维度数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('integration', 3, 0, '明细加工_ODS_DIM到DWD', 2, '用于管理基于ODS原始数据和DIM维度数据进行关联、转换及明细加工并写入DWD层的数据集成任务。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('integration', 4, 0, '汇总统计_DWD_DIM到DWS', 3, '用于管理基于DWD明细数据和DIM维度数据进行指标计算、分组汇总及统计分析并写入DWS层的数据集成任务。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('integration', 5, 0, '应用服务_DWD_DWS到ADS', 4, '用于管理面向看板、报表、接口及专题应用，将DWD和DWS数据加工并写入ADS层的数据集成任务。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('integration', 6, 1, '基础对象原始数据', 0, '同步河流、河段、断面、行政区划、测站、上下游关系等基础对象原始表到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 7, 1, '实时监测原始数据', 1, '同步或接入实时水位、流量、雨量、在线状态、采集状态等实时监测原始数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 8, 1, '历史时序原始数据', 2, '同步历史水位、历史流量、历史雨量等时序原始数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 9, 1, '规则阈值原始数据', 3, '同步防洪告警阈值、生态基流阈值、异常识别规则等原始规则数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 10, 1, '来水下泄原始数据', 4, '同步来水、下泄、上下游断面监测相关原始数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 11, 2, '基础对象维度', 0, '清洗基础对象ODS数据，形成行政区划、河流、河段、断面、测站等公共维表。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 12, 2, '规则阈值参考数据', 1, '清洗阈值、规则、参数配置类数据，形成可复用参考维表。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 13, 2, '生态基流参考数据', 2, '清洗生态基流控制断面、目标流量、监管标准等参考数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 14, 3, '实时监测明细', 0, '对实时水位、流量、雨量等数据进行清洗、去重、维度关联，形成实时监测明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 15, 3, '历史时序明细', 1, '对历史水位、流量、雨量及多年同期数据进行标准化加工，形成历史时序明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 16, 3, '来水下泄明细', 2, '加工来水、下泄、上下游断面对比等过程明细数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 17, 3, '防洪告警事件明细', 3, '加工超警、超保、流量突增、水位上涨速率等防洪告警事件明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 18, 3, '生态基流监管明细', 4, '加工生态基流达标、不达标、连续不达标等监管明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 19, 3, '异常识别明细', 5, '加工测站离线、数据缺测、采集中断、数据突变等异常识别明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 20, 4, '日旬月年统计汇总', 0, '按日、旬、月、年或窗口周期汇总水位、流量、雨量、水势变化等统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 21, 4, '防洪告警汇总', 1, '汇总防洪告警次数、等级、断面分布、处置状态等统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 22, 4, '生态基流监管汇总', 2, '汇总生态基流达标率、断面达标情况、连续不达标等监管统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 23, 4, '来水下泄分析汇总', 3, '汇总来水量、下泄流量、上下游变化趋势等分析结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 24, 4, '异常识别汇总', 4, '汇总异常数据类型、异常次数、影响范围、修复状态等统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 25, 5, '河道水情总览', 0, '面向河道水情总览页面组织核心指标、趋势、告警和统计卡片数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 26, 5, '日旬月年统计看板', 1, '面向日旬月年统计分析看板组织水位、流量、雨量等统计展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 27, 5, '防洪告警看板', 2, '面向防洪告警看板组织告警事件、告警等级和处置状态展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 28, 5, '生态基流监管看板', 3, '面向生态基流监管看板组织达标率、断面状态和连续不达标展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 29, 5, '来水下泄断面监控看板', 4, '面向来水下泄断面监控页面组织断面对比和趋势分析数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 30, 5, '异常数据识别工作台', 5, '面向异常识别工作台组织异常记录、影响对象和处理状态数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 31, 5, '河道水情一张图', 6, '面向河道水情一张图组织断面、测站、空间坐标和实时指标展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 32, 5, '水情简报数据服务', 7, '面向水情简报生成、查询和接口服务组织简报指标和文本数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 33, 5, '告警推送数据服务', 8, '面向告警消息推送、接口调用和通知服务组织推送数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('integration', 34, 5, '统计报表数据服务', 9, '面向统计报表查询、导出和接口服务组织报表结果数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 1, 0, '数据同步_第三方库到ODS', 0, '用于归类第三方业务库、接口及文件等数据源同步至ODS层的开发任务，包括采集脚本、同步流程和源数据落库处理。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('development', 2, 0, '数据清洗_ODS到DIM', 1, '用于归类ODS层数据清洗并加工至DIM层的开发任务，包括数据去重、格式标准化、编码映射和公共维度建设。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('development', 3, 0, '明细加工_ODS_DIM到DWD', 2, '用于归类基于ODS和DIM数据建设DWD明细模型的开发任务，包括业务关联、字段转换、数据补全和明细事实加工。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('development', 4, 0, '汇总统计_DWD_DIM到DWS', 3, '用于归类基于DWD和DIM数据建设DWS汇总模型的开发任务，包括指标计算、周期统计、多维汇总和主题分析加工。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('development', 5, 0, '应用服务_DWD_DWS到ADS', 4, '用于归类面向业务应用建设ADS数据模型的开发任务，为看板展示、统计报表、数据接口和专题应用提供数据支撑。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:08:27'),
    ('development', 6, 1, '基础对象原始数据', 0, '同步河流、河段、断面、行政区划、测站、上下游关系等基础对象原始表到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 7, 1, '实时监测原始数据', 1, '同步或接入实时水位、流量、雨量、在线状态、采集状态等实时监测原始数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 8, 1, '历史时序原始数据', 2, '同步历史水位、历史流量、历史雨量等时序原始数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 9, 1, '规则阈值原始数据', 3, '同步防洪告警阈值、生态基流阈值、异常识别规则等原始规则数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 10, 1, '来水下泄原始数据', 4, '同步来水、下泄、上下游断面监测相关原始数据到ODS。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 11, 2, '基础对象维度', 0, '清洗基础对象ODS数据，形成行政区划、河流、河段、断面、测站等公共维表。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 12, 2, '规则阈值参考数据', 1, '清洗阈值、规则、参数配置类数据，形成可复用参考维表。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 13, 2, '生态基流参考数据', 2, '清洗生态基流控制断面、目标流量、监管标准等参考数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 14, 3, '实时监测明细', 0, '对实时水位、流量、雨量等数据进行清洗、去重、维度关联，形成实时监测明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 15, 3, '历史时序明细', 1, '对历史水位、流量、雨量及多年同期数据进行标准化加工，形成历史时序明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 16, 3, '来水下泄明细', 2, '加工来水、下泄、上下游断面对比等过程明细数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 17, 3, '防洪告警事件明细', 3, '加工超警、超保、流量突增、水位上涨速率等防洪告警事件明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 18, 3, '生态基流监管明细', 4, '加工生态基流达标、不达标、连续不达标等监管明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 19, 3, '异常识别明细', 5, '加工测站离线、数据缺测、采集中断、数据突变等异常识别明细。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 20, 4, '日旬月年统计汇总', 0, '按日、旬、月、年或窗口周期汇总水位、流量、雨量、水势变化等统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 21, 4, '防洪告警汇总', 1, '汇总防洪告警次数、等级、断面分布、处置状态等统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 22, 4, '生态基流监管汇总', 2, '汇总生态基流达标率、断面达标情况、连续不达标等监管统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 23, 4, '来水下泄分析汇总', 3, '汇总来水量、下泄流量、上下游变化趋势等分析结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 24, 4, '异常识别汇总', 4, '汇总异常数据类型、异常次数、影响范围、修复状态等统计结果。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 25, 5, '河道水情总览', 0, '面向河道水情总览页面组织核心指标、趋势、告警和统计卡片数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 26, 5, '日旬月年统计看板', 1, '面向日旬月年统计分析看板组织水位、流量、雨量等统计展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 27, 5, '防洪告警看板', 2, '面向防洪告警看板组织告警事件、告警等级和处置状态展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 28, 5, '生态基流监管看板', 3, '面向生态基流监管看板组织达标率、断面状态和连续不达标展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 29, 5, '来水下泄断面监控看板', 4, '面向来水下泄断面监控页面组织断面对比和趋势分析数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 30, 5, '异常数据识别工作台', 5, '面向异常识别工作台组织异常记录、影响对象和处理状态数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 31, 5, '河道水情一张图', 6, '面向河道水情一张图组织断面、测站、空间坐标和实时指标展示数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 32, 5, '水情简报数据服务', 7, '面向水情简报生成、查询和接口服务组织简报指标和文本数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 33, 5, '告警推送数据服务', 8, '面向告警消息推送、接口调用和通知服务组织推送数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52'),
    ('development', 34, 5, '统计报表数据服务', 9, '面向统计报表查询、导出和接口服务组织报表结果数据。', true, '吴同', '2026-07-29 17:51:43', '2026-07-29 18:05:52');

DO $development$
BEGIN
    IF (SELECT development_first FROM qdata_category_import_scope) THEN
        -- 不兼容旧开发类目，但保留全部任务；顶级编号0符合现有开发任务领域合同。
        UPDATE dh_dataforge_dev_task
        SET dir_id = 0, updater = 'migration:0216', update_time = CURRENT_TIMESTAMP
        WHERE dir_id IN (SELECT id FROM dh_dataforge_dev_dir);
        DELETE FROM dh_dataforge_dev_dir;

        INSERT INTO dh_dataforge_dev_dir
            (id, parent_id, name, sort, status, description, remark, source_creator_name,
             creator, create_time, updater, update_time, deleted)
        SELECT id, parent_id, name, sort, CASE WHEN valid_flag THEN 0 ELSE 1 END,
            description, NULL, source_creator_name, 'migration:0216', create_time,
            'migration:0216', update_time, 0
        FROM qdata_project_category_seed WHERE kind = 'development'
        ORDER BY parent_id, sort, id;
    END IF;
END $development$;

-- 0215的既有类目保持原编号，任务归属不变；仅第一次迁入时补齐未修改种子的完整业务信息。
UPDATE dh_dataforge_integration_category category
SET parent_id = seed.parent_id, name = seed.name, sort = seed.sort,
    valid_flag = seed.valid_flag, description = seed.description,
    source_creator_name = seed.source_creator_name, create_time = seed.create_time,
    update_time = seed.update_time, updater = 'migration:0216'
FROM qdata_project_category_seed seed
WHERE (SELECT integration_first FROM qdata_category_import_scope)
  AND seed.kind = 'integration' AND seed.id = category.id AND category.deleted = 0
  AND category.creator = 'migration:0215' AND category.updater = 'migration:0215'
  AND category.source_creator_name IS NULL;

INSERT INTO dh_dataforge_integration_category
    (id, parent_id, name, sort, valid_flag, description, source_creator_name,
     creator, create_time, updater, update_time, deleted)
SELECT id, parent_id, name, sort, valid_flag, description, source_creator_name,
    'migration:0216', create_time, 'migration:0216', update_time, 0
FROM qdata_project_category_seed
WHERE kind = 'integration' AND (SELECT integration_first FROM qdata_category_import_scope)
ON CONFLICT (id) DO NOTHING;

SELECT setval('dh_dataforge_dev_dir_seq', greatest(
    (SELECT last_value FROM dh_dataforge_dev_dir_seq),
    coalesce((SELECT max(id) FROM dh_dataforge_dev_dir), 1)));
SELECT setval('dh_dataforge_integration_category_seq', greatest(
    (SELECT last_value FROM dh_dataforge_integration_category_seq),
    coalesce((SELECT max(id) FROM dh_dataforge_integration_category), 1)));

-- 保留既有基础管理导航属性；只有缺少同路径入口的新库才补建目录。
DO $menus$
DECLARE
    basic_id bigint;
    category_id bigint;
    page_id bigint;
    action_id bigint;
    high_id bigint;
    granted_ids bigint[];
    entry record;
    action record;
BEGIN
    SELECT id INTO basic_id FROM system_menu
    WHERE parent_id = 0 AND type = 1 AND deleted = 0 AND trim(both '/' from path) = 'basic'
    ORDER BY id LIMIT 1;
    IF basic_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO basic_id FROM system_menu;
        INSERT INTO system_menu
            (id, name, permission, type, sort, parent_id, path, icon, status, visible,
             keep_alive, always_show, creator, updater, deleted)
        VALUES (basic_id, '基础管理', '', 1, 0, 0, '/basic', 'lucide:boxes', 0, true,
            true, true, 'migration:0216', 'migration:0216', 0);
    END IF;
    SELECT id INTO category_id FROM system_menu
    WHERE parent_id = basic_id AND type = 1 AND deleted = 0 AND trim(both '/' from path) = 'category'
    ORDER BY id LIMIT 1;
    IF category_id IS NULL THEN
        SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
            (SELECT last_value FROM system_menu_id_seq)) + 1 INTO category_id FROM system_menu;
        INSERT INTO system_menu
            (id, name, permission, type, sort, parent_id, path, icon, status, visible,
             keep_alive, always_show, creator, updater, deleted)
        VALUES (category_id, '类目管理', '', 1, 50, basic_id, 'category', 'lucide:folder-tree',
            0, true, true, true, 'migration:0216', 'migration:0216', 0);
    END IF;
    granted_ids := ARRAY[basic_id, category_id];

    FOR entry IN SELECT * FROM (VALUES
        ('数据集成类目', 'integration-category', 'dataforge/categoryintegration/index',
            'DataforgeIntegrationCategory', 'dataforge:integration-category', 10),
        ('数据开发类目', 'development-category', 'dataforge/categorydev/index',
            'DataforgeDevelopmentCategory', 'dataforge:dev-category', 20)
    ) pages(label, path, component, component_name, permission_base, sort) LOOP
        SELECT id INTO page_id FROM system_menu
        WHERE trim(both '/' from component) = entry.component AND deleted = 0 ORDER BY id LIMIT 1;
        IF page_id IS NULL THEN
            SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                (SELECT last_value FROM system_menu_id_seq)) + 1 INTO page_id FROM system_menu;
            INSERT INTO system_menu
                (id, name, permission, type, sort, parent_id, path, icon, component,
                 component_name, status, visible, keep_alive, always_show, creator, updater, deleted)
            VALUES (page_id, entry.label, entry.permission_base || ':query', 2, entry.sort,
                category_id, entry.path, 'lucide:folder-tree', entry.component, entry.component_name,
                0, true, true, true, 'migration:0216', 'migration:0216', 0);
        ELSE
            UPDATE system_menu SET parent_id = category_id,
                permission = entry.permission_base || ':query', updater = 'migration:0216',
                update_time = CURRENT_TIMESTAMP WHERE id = page_id;
        END IF;
        granted_ids := array_append(granted_ids, page_id);
        FOR action IN SELECT * FROM (VALUES
            ('create', '新增类目', 1), ('update', '修改及切换状态', 2), ('delete', '删除及批量删除', 3)
        ) permissions(code, label, sort) LOOP
            SELECT id INTO action_id FROM system_menu
            WHERE parent_id = page_id AND permission = entry.permission_base || ':' || action.code
                AND deleted = 0 ORDER BY id LIMIT 1;
            IF action_id IS NULL THEN
                SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
                    (SELECT last_value FROM system_menu_id_seq)) + 1 INTO action_id FROM system_menu;
                INSERT INTO system_menu
                    (id, name, permission, type, sort, parent_id, path, status, visible,
                     keep_alive, always_show, creator, updater, deleted)
                VALUES (action_id, action.label, entry.permission_base || ':' || action.code,
                    3, action.sort, page_id, '', 0, true, true, true,
                    'migration:0216', 'migration:0216', 0);
            END IF;
            granted_ids := array_append(granted_ids, action_id);
        END LOOP;
    END LOOP;

    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_menu_seq),
        (SELECT last_value FROM system_menu_id_seq)) INTO high_id FROM system_menu;
    PERFORM setval('system_menu_seq', high_id);
    PERFORM setval('system_menu_id_seq', high_id);

    -- 仅沿用既有超级管理员角色及其脚手架租户归属，不添加业务租户概念。
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
        INTO high_id FROM system_role_menu;
    INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id, creator, updater, deleted)
    SELECT high_id + row_number() OVER (ORDER BY role.id, role.tenant_id, menu.id),
        role.id, menu.id, role.tenant_id, 'migration:0216', 'migration:0216', 0
    FROM system_role role CROSS JOIN system_menu menu
    WHERE role.code = 'super_admin' AND role.status = 0 AND role.deleted = 0
        AND menu.id = ANY(granted_ids) AND menu.deleted = 0
        AND NOT EXISTS (SELECT 1 FROM system_role_menu old
            WHERE old.role_id = role.id AND old.tenant_id = role.tenant_id
                AND old.menu_id = menu.id AND old.deleted = 0);
    SELECT greatest(coalesce(max(id), 0), (SELECT last_value FROM system_role_menu_seq))
        INTO high_id FROM system_role_menu;
    PERFORM setval('system_role_menu_seq', high_id);
END $menus$;

COMMIT;

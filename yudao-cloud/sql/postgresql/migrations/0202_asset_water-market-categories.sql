-- Migration: 0202 水利业务数据集市类目增量填充。
-- Purpose: 补齐参考站的十一组、七十八条集市类目，按父级和名称复用有效路径，保留现有资产挂载及人工维护内容。
-- Depends on: 0201_governance_water-quality-categories.sql 已执行；0176_asset_market-catalog-and-portal.sql 的集市表、序列和唯一索引已就绪。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql。
-- Repeatable: 是；仅插入缺失的有效路径，不覆盖既有类目的描述、状态、排序、审计字段或资产挂载。
-- Rollback: 单事务失败完整回滚，包括序列重启；提交后应先备份，再仅删除本迁移新增且没有子类及资产引用的类目，不回退已被使用的序列。
-- 事务边界：先取得与集市类目服务相同的树锁，再锁类目表；阻塞并发类目写入，超时即整体失败。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

SELECT pg_advisory_xact_lock(1500, 28);
LOCK TABLE dh_asset_market_category IN SHARE ROW EXCLUSIVE MODE;

-- 模板键仅表达本文件内的父子顺序，不使用参考站编号作为本地业务主键。
CREATE TEMP TABLE water_market_category_seed (
    seed_key integer PRIMARY KEY,
    parent_key integer NOT NULL,
    name varchar(50) NOT NULL,
    description varchar(256) NOT NULL,
    sort integer NOT NULL,
    resolved_id bigint
) ON COMMIT DROP;
INSERT INTO water_market_category_seed (seed_key, parent_key, name, description, sort) VALUES
    (1, 0, '综合决策', '汇聚水利行业跨业务领域的综合分析成果，为领导决策、运行监管、重点任务督办、综合评价和专题会商提供统一数据支撑。', 0),
    (2, 0, '河道水情', '围绕河流、河段和控制断面的水位、流量、流速及水势变化组织分析成果，支撑水情监视、趋势研判和信息发布。', 1),
    (3, 0, '水资源', '围绕水资源数量、开发利用、节约保护、供需关系和配置调度形成专题数据成果，服务水资源刚性约束和精细化监管。', 2),
    (4, 0, '防洪减灾', '围绕雨水情监测、洪水预报、风险研判、防洪调度和应急处置组织专题成果，服务汛情会商和防洪应急决策。', 3),
    (5, 0, '灌区', '围绕灌溉计划、需配水、计量监测、渠道调度和农业用水效率形成应用成果，支撑灌区精细化配水和节水管理。', 4),
    (6, 0, '农村供水', '围绕农村供水水源、供水工程、运行状态、水质安全和用户服务形成专题成果，支撑农村供水保障监管。', 5),
    (7, 0, '水利工程', '围绕水库、水闸、泵站、堤防、渠道等工程的运行、安全、养护和评价组织成果，支撑工程全生命周期监管。', 6),
    (8, 0, '水土保持', '围绕水土流失监测、综合治理、生产建设项目监管和遥感监督组织成果，服务水土保持监管与评价。', 7),
    (9, 0, '河湖生态', '围绕河湖健康、生态流量、水域岸线、水质生态和河湖问题监管形成专题成果，服务河湖生态保护与修复。', 8),
    (10, 0, '空间专题', '面向水利一张图和空间分析应用，组织水系、工程、站网、风险和资源分区等空间成果，提供统一地图数据支撑。', 9),
    (11, 0, '统计共享', '面向统计上报、跨部门交换、社会开放和成果归档组织可交付的数据产品，支撑规范化共享和成果复用。', 10),
    (12, 1, '综合态势', '汇聚水情、工情、旱情、水资源和河湖生态等信息，形成全域水利运行态势，支撑跨业务综合监视和会商研判。', 0),
    (13, 1, '领导驾驶舱', '组织核心指标、重点工程、风险预警、资源保障和任务进展等数据，为领导快速掌握总体情况和重点问题提供支撑。', 1),
    (14, 1, '重点监管', '集中展示重点河湖、重点工程、重点取用水户、重点风险对象及其监管状态，支撑分级分类监管和问题跟踪。', 2),
    (15, 1, '任务督办', '汇总重点工作计划、责任单位、执行进度、问题清单、整改要求和办结情况，支撑任务全过程督办和闭环管理。', 3),
    (16, 1, '综合评价', '承载区域、部门和重点业务的评价指标、计算结果、等级排名及变化趋势，为管理绩效分析提供依据。', 4),
    (17, 1, '专题成果', '归集专项调查、规划分析、联合会商和专题研究形成的数据成果，便于成果统一管理、查询和复用。', 5),
    (18, 2, '实时水情', '提供河流、河段和测站的最新水位、流量、流速、水势及变化趋势，支撑实时监视和异常情况快速发现。', 0),
    (19, 2, '基础数据', '提供河流、河段、测站、水文站等基础数据。', 0),
    (20, 2, '洪水过程', '汇集洪水场次、洪峰流量、最高水位、峰现时间、涨落过程和传播过程等分析成果。', 1),
    (21, 2, '历史特征', '提供历年同期、历史极值、多年均值、频率分析和典型水文过程等统计成果，支撑趋势对比和规律分析。', 2),
    (22, 2, '来水下泄', '展示上游来水、水库下泄、区间汇流、断面过水和上下游水量变化情况，支撑水量过程研判。', 3),
    (23, 2, '异常识别', '归集水位突变、流量异常、数据中断、倒流和水势异常等识别结果，支撑异常复核和问题处置。', 4),
    (24, 2, '水情报表', '提供水情日报、旬报、月报、汛期报表和重要水情简报所需的汇总数据与发布成果。', 5),
    (25, 3, '资源总量', '提供地表水、地下水、降水量、水资源总量及可利用量等统计评价成果，反映区域水资源禀赋。', 0),
    (26, 3, '取用水监管', '汇集取水许可、计划用水、实际取水、计量上报、超许可和超计划分析数据，支撑取用水全过程监管。', 1),
    (27, 3, '节约用水', '提供万元产值用水量、农田灌溉水有效利用系数、节水载体和节水目标完成情况等成果。', 2),
    (28, 3, '供需平衡', '分析不同区域、行业和时段的可供水量、需水量、实际供水量及供需缺口，支撑供需形势研判。', 3),
    (29, 3, '配置调度', '承载水资源配置方案、年度调度计划、调度指令、执行过程和配置成效等数据成果。', 4),
    (30, 3, '承载能力', '提供水资源承载状态、超载程度、主要限制因素、管控分区和改善趋势等综合评价成果。', 5),
    (31, 4, '监测预警', '汇聚降雨、水位、流量、积水和山洪等监测数据及预警信息，支撑汛情快速感知和预警发布。', 0),
    (32, 4, '风险研判', '提供风险区、危险点、影响范围、影响人口、可能损失和防御重点等综合研判成果。', 1),
    (33, 4, '预报成果', '承载降雨预报、洪水预报、洪峰预测、演进过程和淹没分析等模型计算与会商成果。', 2),
    (34, 4, '调度决策', '提供水库、水闸、泵站和蓄滞洪区联合调度方案、效益分析及执行反馈数据。', 3),
    (35, 4, '灾情险情', '汇集洪涝灾害、工程险情、受灾范围、受灾人口、人员转移和经济损失等信息。', 4),
    (36, 4, '应急保障', '提供防汛物资、抢险队伍、专家、仓库、避险场所和应急响应状态等保障数据。', 5),
    (37, 5, '灌溉计划', '提供年度、季度、月度和轮次灌溉计划，以及灌溉面积、作物结构和计划调整信息。', 0),
    (38, 5, '需配水', '汇集作物需水、灌溉需水、可供水量、渠系配水和供需缺口等分析结果。', 1),
    (39, 5, '计量监测', '提供渠首、干渠、支渠和末级渠系的水位、流量、累计水量及计量设备运行情况。', 2),
    (40, 5, '调度运行', '展示闸门启闭、泵站运行、渠道输水、轮灌安排、调度指令和执行反馈等数据。', 3),
    (41, 5, '用水效率', '提供亩均用水量、渠系水利用系数、灌溉水有效利用系数和节水量等分析成果。', 4),
    (42, 5, '综合评价', '评价灌区供水保障程度、工程运行状况、节水成效、管理水平和综合效益。', 5),
    (43, 6, '供水保障', '统计供水人口、供水规模、供水保证率、正常供水时长、停水事件和影响范围等情况。', 0),
    (44, 6, '水源保障', '提供水源类型、可供水量、水源稳定性、保护状况和备用水源配置情况。', 1),
    (45, 6, '运行监测', '展示水厂、泵站、调蓄设施和管网的运行状态、压力、流量、液位及关键设备工况。', 2),
    (46, 6, '水质安全', '汇集水源水、出厂水、管网水和末梢水检测结果、达标情况及超标处置记录。', 3),
    (47, 6, '用水分析', '分析区域、工程和用户的供水量、售水量、用水量、产销差及变化趋势。', 4),
    (48, 6, '服务评价', '汇总报修、投诉、停复水通知、处置时效、回访结果和用户满意度等服务数据。', 5),
    (49, 7, '工程概览', '形成工程数量、类型、等级、规模、空间分布、管理单位和运行状态的综合画像。', 0),
    (50, 7, '运行监测', '提供水位、流量、闸位、机组状态、启停记录和运行工况等工程运行数据。', 1),
    (51, 7, '安全监测', '汇集渗流、位移、沉降、应力、裂缝和振动等安全监测数据及变化趋势。', 2),
    (52, 7, '巡检养护', '归集巡检计划、巡检记录、缺陷问题、养护任务、维修过程和完成情况。', 3),
    (53, 7, '风险隐患', '提供风险部位、隐患等级、问题描述、整改责任、整改进度和复查销号结果。', 4),
    (54, 7, '运行评价', '评价工程安全状态、运行效率、管护水平、调度能力和社会经济效益。', 5),
    (55, 8, '流失现状', '展示水土流失面积、强度、类型、空间分布、影响因素及年度变化情况。', 0),
    (56, 8, '治理成效', '统计治理面积、治理措施、投资完成、水土保持率和生态经济效益等成果。', 1),
    (57, 8, '项目监管', '汇集生产建设项目方案审批、措施实施、监测监理、补偿费和设施验收等数据。', 2),
    (58, 8, '监测评价', '提供监测站点、监测指标、区域评价、重点区域变化和年度动态监测结果。', 3),
    (59, 8, '遥感监管', '归集扰动图斑、疑似违法图斑、现场核查、问题认定和整改销号等数据。', 4),
    (60, 8, '监督执法', '提供监督检查、违法问题、行政处置、责任单位、整改要求和闭环办理情况。', 5),
    (61, 9, '河湖态势', '综合展示河湖空间分布、管理责任、水域岸线、监测状态和主要问题等信息。', 0),
    (62, 9, '河湖健康', '提供河湖健康评价指标、评价结果、健康等级、主要问题和年度变化趋势。', 1),
    (63, 9, '生态流量', '展示控制断面目标值、实测流量、达标时段、达标率和连续不达标情况。', 2),
    (64, 9, '水域岸线', '分析水域面积、岸线长度、岸线功能分区、开发利用和管理范围变化情况。', 3),
    (65, 9, '水质生态', '汇集水质类别、营养状态、水生生物、生境状况和生态环境评价成果。', 4),
    (66, 9, '问题监管', '归集河湖四乱、排污问题、问题来源、整改责任、处置进度和销号结果。', 5),
    (67, 10, '水系空间', '提供流域、水系、河流、河段、湖泊和控制断面的空间位置、范围及拓扑关系成果。', 0),
    (68, 10, '工程空间', '提供水库、水闸、泵站、堤防、渠道和引调水工程等空间分布数据。', 1),
    (69, 10, '站网空间', '展示水文、水质、地下水、墒情、生态流量和视频站点的空间布局及覆盖范围。', 2),
    (70, 10, '风险区划', '汇集洪水风险区、山洪危险区、蓄滞洪区、防洪保护区和风险隐患点空间数据。', 3),
    (71, 10, '资源分区', '提供水资源分区、水功能区、供水分区、管理范围和保护范围等专题成果。', 4),
    (72, 10, '遥感影像', '归集基础影像、专题影像、变化检测、遥感解译和时序对比等空间成果。', 5),
    (73, 11, '综合报表', '提供覆盖多个水利领域的综合统计报表、核心指标和区域汇总数据。', 0),
    (74, 11, '行业报表', '提供水资源、防洪、供水、工程、水土保持和河湖生态等专业统计报表。', 1),
    (75, 11, '专题数据包', '按照专题、区域、时间和使用目的封装经过审核的可交付数据成果。', 2),
    (76, 11, '交换共享', '组织向上级平台、相关部门和业务系统交换共享的数据及交换结果。', 3),
    (77, 11, '开放数据', '归集经过审核、脱敏并允许面向社会开放的数据产品及其更新信息。', 4),
    (78, 11, '成果归档', '保存定期统计、专项调查、评估报告、历史发布版本和交付成果。', 5);

DO $fill_market$
DECLARE
    item record;
    parent_id_value bigint;
    local_id bigint;
    missing_count bigint;
    first_id numeric;
    sequence_value bigint;
    sequence_called boolean;
    sequence_increment bigint;
    sequence_max bigint;
    sequence_cycles boolean;
BEGIN
    -- 恢复既定从属关系，同时持有序列的事务锁；步长及高水位检查在锁内完成。
    ALTER SEQUENCE dh_asset_market_category_seq OWNED BY dh_asset_market_category.id;
    SELECT seqincrement, seqmax, seqcycle INTO sequence_increment, sequence_max, sequence_cycles
      FROM pg_sequence WHERE seqrelid = 'dh_asset_market_category_seq'::regclass;
    IF sequence_increment <> 1 OR sequence_cycles THEN
        RAISE EXCEPTION '集市类目序列必须使用步长一且禁止循环';
    END IF;

    UPDATE water_market_category_seed seed SET resolved_id = category.id
      FROM dh_asset_market_category category
     WHERE seed.parent_key = 0 AND category.parent_id = 0
       AND category.name = seed.name AND category.deleted = 0;
    UPDATE water_market_category_seed seed SET resolved_id = category.id
      FROM water_market_category_seed parent, dh_asset_market_category category
     WHERE seed.parent_key = parent.seed_key AND parent.resolved_id IS NOT NULL
       AND category.parent_id = parent.resolved_id
       AND category.name = seed.name AND category.deleted = 0;
    SELECT count(*) INTO missing_count FROM water_market_category_seed WHERE resolved_id IS NULL;

    IF missing_count > 0 THEN
        SELECT last_value, is_called INTO sequence_value, sequence_called FROM dh_asset_market_category_seq;
        SELECT greatest(coalesce(max(id), 0)::numeric + 1,
                        sequence_value::numeric + CASE WHEN sequence_called THEN 1 ELSE 0 END)
          INTO first_id FROM dh_asset_market_category;
        IF first_id + missing_count - 1 > sequence_max THEN
            RAISE EXCEPTION '集市类目序列剩余空间不足';
        END IF;
        -- 使用事务性重启而非非事务性 setval；任何插入失败均恢复迁移前序列状态。
        EXECUTE format('ALTER SEQUENCE dh_asset_market_category_seq RESTART WITH %s', first_id);
        FOR item IN SELECT * FROM water_market_category_seed ORDER BY parent_key, seed_key LOOP
            IF item.resolved_id IS NOT NULL THEN
                CONTINUE;
            END IF;
            parent_id_value := 0;
            IF item.parent_key <> 0 THEN
                SELECT resolved_id INTO parent_id_value FROM water_market_category_seed WHERE seed_key = item.parent_key;
                IF parent_id_value IS NULL THEN
                    RAISE EXCEPTION '集市类目模板缺少父级';
                END IF;
            END IF;
            INSERT INTO dh_asset_market_category
                (parent_id, name, description, status, sort, creator, updater, deleted)
            VALUES (parent_id_value, item.name, item.description, 0, item.sort, 'migration:0202', 'migration:0202', 0)
            RETURNING id INTO local_id;
            UPDATE water_market_category_seed SET resolved_id = local_id WHERE seed_key = item.seed_key;
        END LOOP;
    END IF;

    IF (SELECT count(*) FROM water_market_category_seed WHERE resolved_id IS NOT NULL) <> 78 THEN
        RAISE EXCEPTION '集市类目模板未完整填充';
    END IF;
END;
$fill_market$;

COMMIT;

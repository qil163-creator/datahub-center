-- Migration: 0219 qData 公开数据集市目录迁入。
-- Purpose: 迁入实测的十七条集市公开目录、标签与八十二条类目路径，补齐来源展示字段，保留本地独立授权边界。
-- Depends on: 0218_governance_metadata-display.sql 已执行；0176 的集市表及 0202 的水利集市类目状态已就绪，允许直接使用包含这些状态的当前全量基线作为前置。
-- Target: 已有 PostgreSQL 平台库；新库使用包含本迁移的 datahub-center.sql，禁止重复导入全量基线升级。
-- Repeatable: 是；相同来源编码复用已迁入目录，不覆盖人工修改和授权；若来源身份冲突或迁入目录已被人工删除则中止并要求人工核验。
-- Rollback: 单事务失败完整回滚；提交后删除迁入目录会丢失后续本地使用记录，须先备份并逐条核验引用，不能自动回滚；恢复技术字段非空约束前必须补齐真实绑定。
-- 风险：技术绑定投影允许为空，仅表示目录可见且交付未绑定，不为任何本地数据源、元数据、快照或授权伪造编号；结构锁最长等待十秒。
-- 来源：2026-10-22 经用户授权读取 qData 公开集市接口；仅保留公开目录字段，未迁入用户、联系方式、连接地址、申请、收藏、令牌与远端内部主键引用。
-- 展示评分为来源页面固定的 98.5 展示值，不写入资产质量健康分；源记录号只用于来源徽标，不作为本地鉴权或数据库引用。
-- 事务边界：类目树事务锁及相关业务表写锁覆盖整个增量；序列采用事务性重启，失败时数据与序列状态一并回滚。
BEGIN;
SET LOCAL search_path TO "datahub-center", pg_catalog;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

SELECT pg_advisory_xact_lock(1500, 28);
LOCK TABLE dh_asset_data_asset, dh_asset_catalog_version, dh_asset_market_listing,
    dh_asset_market_category, dh_asset_tag, dh_asset_tag_rel IN SHARE ROW EXCLUSIVE MODE;

-- 尚未绑定技术交付的公开目录不能借用零值或远端编号充当本地技术证据。
ALTER TABLE dh_asset_data_asset
    ALTER COLUMN resource_urn DROP NOT NULL,
    ALTER COLUMN published_snapshot_id DROP NOT NULL,
    ALTER COLUMN published_version DROP NOT NULL,
    ALTER COLUMN datasource_id DROP NOT NULL,
    ALTER COLUMN table_name DROP NOT NULL;
COMMENT ON COLUMN dh_asset_data_asset.resource_urn IS '真实来源技术对象 URN；仅公开目录且未绑定交付时为空';
COMMENT ON COLUMN dh_asset_data_asset.published_snapshot_id IS '真实本地技术快照编号；未绑定交付时为空，不能使用远端编号';
COMMENT ON COLUMN dh_asset_data_asset.published_version IS '真实技术快照版本；仅公开目录且未绑定交付时为空';
COMMENT ON COLUMN dh_asset_data_asset.datasource_id IS '真实本地 dataforge 数据源编号；未绑定交付时为空';
COMMENT ON COLUMN dh_asset_data_asset.table_name IS '公开物理表名称；文件等非表目录允许为空，不表示技术交付已绑定';

ALTER TABLE dh_asset_market_listing
    ADD COLUMN IF NOT EXISTS source_record_id varchar(64),
    ADD COLUMN IF NOT EXISTS file_name varchar(255),
    ADD COLUMN IF NOT EXISTS file_type varchar(128),
    ADD COLUMN IF NOT EXISTS service_identifier varchar(128),
    ADD COLUMN IF NOT EXISTS primary_delivery_type integer,
    ADD COLUMN IF NOT EXISTS display_score varchar(16);
COMMENT ON COLUMN dh_asset_market_listing.source_record_id IS '来源公开记录号，仅用于来源徽标，不作为本地授权或关联主键';
COMMENT ON COLUMN dh_asset_market_listing.file_name IS '来源公开文件名称展示，不包含存储路径或下载凭证';
COMMENT ON COLUMN dh_asset_market_listing.file_type IS '来源公开文件类型展示，不表示本地交付能力';
COMMENT ON COLUMN dh_asset_market_listing.service_identifier IS '来源页面公开服务标识展示，不作为本地供应接口编号';
COMMENT ON COLUMN dh_asset_market_listing.primary_delivery_type IS '来源页面首选展示形态：1数据库表、2接口、3文件，不表示本地交付能力';
COMMENT ON COLUMN dh_asset_market_listing.display_score IS '来源页面评分展示文本，与本地质量健康分和质量计算结果独立';

CREATE TEMP TABLE qdata_market_category_seed (
    source_id bigint PRIMARY KEY,
    parent_source_id bigint NOT NULL,
    source_code text NOT NULL UNIQUE,
    name varchar(50) NOT NULL,
    description varchar(256),
    sort integer NOT NULL,
    depth integer NOT NULL,
    resolved_id bigint
) ON COMMIT DROP;
INSERT INTO qdata_market_category_seed (source_id, parent_source_id, source_code, name, description, sort, depth)
SELECT source_id, parent_source_id, source_code, name, description, sort, depth
FROM jsonb_to_recordset($categories$
[{"source_id":79,"parent_source_id":0,"source_code":"A12","name":"部门分类","description":"","sort":0,"depth":0},{"source_id":60,"parent_source_id":9,"source_code":"A09A01","name":"河湖态势","description":"综合展示河湖空间分布、管理责任、水域岸线、监测状态和主要问题等信息。","sort":0,"depth":1},{"source_id":72,"parent_source_id":11,"source_code":"A11A01","name":"综合报表","description":"提供覆盖多个水利领域的综合统计报表、核心指标和区域汇总数据。","sort":0,"depth":1},{"source_id":30,"parent_source_id":4,"source_code":"A04A01","name":"监测预警","description":"汇聚降雨、水位、流量、积水和山洪等监测数据及预警信息，支撑汛情快速感知和预警发布。","sort":0,"depth":1},{"source_id":80,"parent_source_id":79,"source_code":"A12A01","name":"财务部门","description":"","sort":0,"depth":1},{"source_id":54,"parent_source_id":8,"source_code":"A08A01","name":"流失现状","description":"展示水土流失面积、强度、类型、空间分布、影响因素及年度变化情况。","sort":0,"depth":1},{"source_id":36,"parent_source_id":5,"source_code":"A05A01","name":"灌溉计划","description":"提供年度、季度、月度和轮次灌溉计划，以及灌溉面积、作物结构和计划调整信息。","sort":0,"depth":1},{"source_id":78,"parent_source_id":2,"source_code":"A02A07","name":"基础数据","description":"提供河流、河段、测站、水文站等基础数据。","sort":0,"depth":1},{"source_id":66,"parent_source_id":10,"source_code":"A10A01","name":"水系空间","description":"提供流域、水系、河流、河段、湖泊和控制断面的空间位置、范围及拓扑关系成果。","sort":0,"depth":1},{"source_id":82,"parent_source_id":79,"source_code":"A12A03","name":"后勤部门","description":"","sort":1,"depth":1},{"source_id":18,"parent_source_id":2,"source_code":"A02A01","name":"实时水情","description":"提供河流、河段和测站的最新水位、流量、流速、水势及变化趋势，支撑实时监视和异常情况快速发现。","sort":1,"depth":1},{"source_id":12,"parent_source_id":1,"source_code":"A01A01","name":"综合态势","description":"汇聚水情、工情、旱情、水资源和河湖生态等信息，形成全域水利运行态势，支撑跨业务综合监视和会商研判。","sort":0,"depth":1},{"source_id":42,"parent_source_id":6,"source_code":"A06A01","name":"供水保障","description":"统计供水人口、供水规模、供水保证率、正常供水时长、停水事件和影响范围等情况。","sort":0,"depth":1},{"source_id":48,"parent_source_id":7,"source_code":"A07A01","name":"工程概览","description":"形成工程数量、类型、等级、规模、空间分布、管理单位和运行状态的综合画像。","sort":0,"depth":1},{"source_id":24,"parent_source_id":3,"source_code":"A03A01","name":"资源总量","description":"提供地表水、地下水、降水量、水资源总量及可利用量等统计评价成果，反映区域水资源禀赋。","sort":0,"depth":1},{"source_id":81,"parent_source_id":79,"source_code":"A12A02","name":"教务部门","description":"","sort":2,"depth":1},{"source_id":1,"parent_source_id":0,"source_code":"A01","name":"综合决策","description":"汇聚水利行业跨业务领域的综合分析成果，为领导决策、运行监管、重点任务督办、综合评价和专题会商提供统一数据支撑。","sort":1,"depth":0},{"source_id":61,"parent_source_id":9,"source_code":"A09A02","name":"河湖健康","description":"提供河湖健康评价指标、评价结果、健康等级、主要问题和年度变化趋势。","sort":1,"depth":1},{"source_id":13,"parent_source_id":1,"source_code":"A01A02","name":"领导驾驶舱","description":"组织核心指标、重点工程、风险预警、资源保障和任务进展等数据，为领导快速掌握总体情况和重点问题提供支撑。","sort":1,"depth":1},{"source_id":67,"parent_source_id":10,"source_code":"A10A02","name":"工程空间","description":"提供水库、水闸、泵站、堤防、渠道和引调水工程等空间分布数据。","sort":1,"depth":1},{"source_id":49,"parent_source_id":7,"source_code":"A07A02","name":"运行监测","description":"提供水位、流量、闸位、机组状态、启停记录和运行工况等工程运行数据。","sort":1,"depth":1},{"source_id":43,"parent_source_id":6,"source_code":"A06A02","name":"水源保障","description":"提供水源类型、可供水量、水源稳定性、保护状况和备用水源配置情况。","sort":1,"depth":1},{"source_id":2,"parent_source_id":0,"source_code":"A02","name":"河道水情","description":"围绕河流、河段和控制断面的水位、流量、流速及水势变化组织分析成果，支撑水情监视、趋势研判和信息发布。","sort":2,"depth":0},{"source_id":19,"parent_source_id":2,"source_code":"A02A02","name":"洪水过程","description":"汇集洪水场次、洪峰流量、最高水位、峰现时间、涨落过程和传播过程等分析成果。","sort":2,"depth":1},{"source_id":73,"parent_source_id":11,"source_code":"A11A02","name":"行业报表","description":"提供水资源、防洪、供水、工程、水土保持和河湖生态等专业统计报表。","sort":1,"depth":1},{"source_id":55,"parent_source_id":8,"source_code":"A08A02","name":"治理成效","description":"统计治理面积、治理措施、投资完成、水土保持率和生态经济效益等成果。","sort":1,"depth":1},{"source_id":31,"parent_source_id":4,"source_code":"A04A02","name":"风险研判","description":"提供风险区、危险点、影响范围、影响人口、可能损失和防御重点等综合研判成果。","sort":1,"depth":1},{"source_id":37,"parent_source_id":5,"source_code":"A05A02","name":"需配水","description":"汇集作物需水、灌溉需水、可供水量、渠系配水和供需缺口等分析结果。","sort":1,"depth":1},{"source_id":25,"parent_source_id":3,"source_code":"A03A02","name":"取用水监管","description":"汇集取水许可、计划用水、实际取水、计量上报、超许可和超计划分析数据，支撑取用水全过程监管。","sort":1,"depth":1},{"source_id":68,"parent_source_id":10,"source_code":"A10A03","name":"站网空间","description":"展示水文、水质、地下水、墒情、生态流量和视频站点的空间布局及覆盖范围。","sort":2,"depth":1},{"source_id":56,"parent_source_id":8,"source_code":"A08A03","name":"项目监管","description":"汇集生产建设项目方案审批、措施实施、监测监理、补偿费和设施验收等数据。","sort":2,"depth":1},{"source_id":26,"parent_source_id":3,"source_code":"A03A03","name":"节约用水","description":"提供万元产值用水量、农田灌溉水有效利用系数、节水载体和节水目标完成情况等成果。","sort":2,"depth":1},{"source_id":50,"parent_source_id":7,"source_code":"A07A03","name":"安全监测","description":"汇集渗流、位移、沉降、应力、裂缝和振动等安全监测数据及变化趋势。","sort":2,"depth":1},{"source_id":62,"parent_source_id":9,"source_code":"A09A03","name":"生态流量","description":"展示控制断面目标值、实测流量、达标时段、达标率和连续不达标情况。","sort":2,"depth":1},{"source_id":44,"parent_source_id":6,"source_code":"A06A03","name":"运行监测","description":"展示水厂、泵站、调蓄设施和管网的运行状态、压力、流量、液位及关键设备工况。","sort":2,"depth":1},{"source_id":20,"parent_source_id":2,"source_code":"A02A03","name":"历史特征","description":"提供历年同期、历史极值、多年均值、频率分析和典型水文过程等统计成果，支撑趋势对比和规律分析。","sort":3,"depth":1},{"source_id":14,"parent_source_id":1,"source_code":"A01A03","name":"重点监管","description":"集中展示重点河湖、重点工程、重点取用水户、重点风险对象及其监管状态，支撑分级分类监管和问题跟踪。","sort":2,"depth":1},{"source_id":74,"parent_source_id":11,"source_code":"A11A03","name":"专题数据包","description":"按照专题、区域、时间和使用目的封装经过审核的可交付数据成果。","sort":2,"depth":1},{"source_id":38,"parent_source_id":5,"source_code":"A05A03","name":"计量监测","description":"提供渠首、干渠、支渠和末级渠系的水位、流量、累计水量及计量设备运行情况。","sort":2,"depth":1},{"source_id":32,"parent_source_id":4,"source_code":"A04A03","name":"预报成果","description":"承载降雨预报、洪水预报、洪峰预测、演进过程和淹没分析等模型计算与会商成果。","sort":2,"depth":1},{"source_id":3,"parent_source_id":0,"source_code":"A03","name":"水资源","description":"围绕水资源数量、开发利用、节约保护、供需关系和配置调度形成专题数据成果，服务水资源刚性约束和精细化监管。","sort":3,"depth":0},{"source_id":63,"parent_source_id":9,"source_code":"A09A04","name":"水域岸线","description":"分析水域面积、岸线长度、岸线功能分区、开发利用和管理范围变化情况。","sort":3,"depth":1},{"source_id":15,"parent_source_id":1,"source_code":"A01A04","name":"任务督办","description":"汇总重点工作计划、责任单位、执行进度、问题清单、整改要求和办结情况，支撑任务全过程督办和闭环管理。","sort":3,"depth":1},{"source_id":57,"parent_source_id":8,"source_code":"A08A04","name":"监测评价","description":"提供监测站点、监测指标、区域评价、重点区域变化和年度动态监测结果。","sort":3,"depth":1},{"source_id":27,"parent_source_id":3,"source_code":"A03A04","name":"供需平衡","description":"分析不同区域、行业和时段的可供水量、需水量、实际供水量及供需缺口，支撑供需形势研判。","sort":3,"depth":1},{"source_id":51,"parent_source_id":7,"source_code":"A07A04","name":"巡检养护","description":"归集巡检计划、巡检记录、缺陷问题、养护任务、维修过程和完成情况。","sort":3,"depth":1},{"source_id":45,"parent_source_id":6,"source_code":"A06A04","name":"水质安全","description":"汇集水源水、出厂水、管网水和末梢水检测结果、达标情况及超标处置记录。","sort":3,"depth":1},{"source_id":21,"parent_source_id":2,"source_code":"A02A04","name":"来水下泄","description":"展示上游来水、水库下泄、区间汇流、断面过水和上下游水量变化情况，支撑水量过程研判。","sort":4,"depth":1},{"source_id":39,"parent_source_id":5,"source_code":"A05A04","name":"调度运行","description":"展示闸门启闭、泵站运行、渠道输水、轮灌安排、调度指令和执行反馈等数据。","sort":3,"depth":1},{"source_id":4,"parent_source_id":0,"source_code":"A04","name":"防洪减灾","description":"围绕雨水情监测、洪水预报、风险研判、防洪调度和应急处置组织专题成果，服务汛情会商和防洪应急决策。","sort":4,"depth":0},{"source_id":75,"parent_source_id":11,"source_code":"A11A04","name":"交换共享","description":"组织向上级平台、相关部门和业务系统交换共享的数据及交换结果。","sort":3,"depth":1},{"source_id":69,"parent_source_id":10,"source_code":"A10A04","name":"风险区划","description":"汇集洪水风险区、山洪危险区、蓄滞洪区、防洪保护区和风险隐患点空间数据。","sort":3,"depth":1},{"source_id":33,"parent_source_id":4,"source_code":"A04A04","name":"调度决策","description":"提供水库、水闸、泵站和蓄滞洪区联合调度方案、效益分析及执行反馈数据。","sort":3,"depth":1},{"source_id":58,"parent_source_id":8,"source_code":"A08A05","name":"遥感监管","description":"归集扰动图斑、疑似违法图斑、现场核查、问题认定和整改销号等数据。","sort":4,"depth":1},{"source_id":28,"parent_source_id":3,"source_code":"A03A05","name":"配置调度","description":"承载水资源配置方案、年度调度计划、调度指令、执行过程和配置成效等数据成果。","sort":4,"depth":1},{"source_id":52,"parent_source_id":7,"source_code":"A07A05","name":"风险隐患","description":"提供风险部位、隐患等级、问题描述、整改责任、整改进度和复查销号结果。","sort":4,"depth":1},{"source_id":5,"parent_source_id":0,"source_code":"A05","name":"灌区","description":"围绕灌溉计划、需配水、计量监测、渠道调度和农业用水效率形成应用成果，支撑灌区精细化配水和节水管理。","sort":5,"depth":0},{"source_id":46,"parent_source_id":6,"source_code":"A06A05","name":"用水分析","description":"分析区域、工程和用户的供水量、售水量、用水量、产销差及变化趋势。","sort":4,"depth":1},{"source_id":22,"parent_source_id":2,"source_code":"A02A05","name":"异常识别","description":"归集水位突变、流量异常、数据中断、倒流和水势异常等识别结果，支撑异常复核和问题处置。","sort":5,"depth":1},{"source_id":40,"parent_source_id":5,"source_code":"A05A05","name":"用水效率","description":"提供亩均用水量、渠系水利用系数、灌溉水有效利用系数和节水量等分析成果。","sort":4,"depth":1},{"source_id":76,"parent_source_id":11,"source_code":"A11A05","name":"开放数据","description":"归集经过审核、脱敏并允许面向社会开放的数据产品及其更新信息。","sort":4,"depth":1},{"source_id":70,"parent_source_id":10,"source_code":"A10A05","name":"资源分区","description":"提供水资源分区、水功能区、供水分区、管理范围和保护范围等专题成果。","sort":4,"depth":1},{"source_id":34,"parent_source_id":4,"source_code":"A04A05","name":"灾情险情","description":"汇集洪涝灾害、工程险情、受灾范围、受灾人口、人员转移和经济损失等信息。","sort":4,"depth":1},{"source_id":64,"parent_source_id":9,"source_code":"A09A05","name":"水质生态","description":"汇集水质类别、营养状态、水生生物、生境状况和生态环境评价成果。","sort":4,"depth":1},{"source_id":16,"parent_source_id":1,"source_code":"A01A05","name":"综合评价","description":"承载区域、部门和重点业务的评价指标、计算结果、等级排名及变化趋势，为管理绩效分析提供依据。","sort":4,"depth":1},{"source_id":59,"parent_source_id":8,"source_code":"A08A06","name":"监督执法","description":"提供监督检查、违法问题、行政处置、责任单位、整改要求和闭环办理情况。","sort":5,"depth":1},{"source_id":29,"parent_source_id":3,"source_code":"A03A06","name":"承载能力","description":"提供水资源承载状态、超载程度、主要限制因素、管控分区和改善趋势等综合评价成果。","sort":5,"depth":1},{"source_id":53,"parent_source_id":7,"source_code":"A07A06","name":"运行评价","description":"评价工程安全状态、运行效率、管护水平、调度能力和社会经济效益。","sort":5,"depth":1},{"source_id":6,"parent_source_id":0,"source_code":"A06","name":"农村供水","description":"围绕农村供水水源、供水工程、运行状态、水质安全和用户服务形成专题成果，支撑农村供水保障监管。","sort":6,"depth":0},{"source_id":47,"parent_source_id":6,"source_code":"A06A06","name":"服务评价","description":"汇总报修、投诉、停复水通知、处置时效、回访结果和用户满意度等服务数据。","sort":5,"depth":1},{"source_id":23,"parent_source_id":2,"source_code":"A02A06","name":"水情报表","description":"提供水情日报、旬报、月报、汛期报表和重要水情简报所需的汇总数据与发布成果。","sort":6,"depth":1},{"source_id":41,"parent_source_id":5,"source_code":"A05A06","name":"综合评价","description":"评价灌区供水保障程度、工程运行状况、节水成效、管理水平和综合效益。","sort":5,"depth":1},{"source_id":77,"parent_source_id":11,"source_code":"A11A06","name":"成果归档","description":"保存定期统计、专项调查、评估报告、历史发布版本和交付成果。","sort":5,"depth":1},{"source_id":71,"parent_source_id":10,"source_code":"A10A06","name":"遥感影像","description":"归集基础影像、专题影像、变化检测、遥感解译和时序对比等空间成果。","sort":5,"depth":1},{"source_id":35,"parent_source_id":4,"source_code":"A04A06","name":"应急保障","description":"提供防汛物资、抢险队伍、专家、仓库、避险场所和应急响应状态等保障数据。","sort":5,"depth":1},{"source_id":17,"parent_source_id":1,"source_code":"A01A06","name":"专题成果","description":"归集专项调查、规划分析、联合会商和专题研究形成的数据成果，便于成果统一管理、查询和复用。","sort":5,"depth":1},{"source_id":65,"parent_source_id":9,"source_code":"A09A06","name":"问题监管","description":"归集河湖四乱、排污问题、问题来源、整改责任、处置进度和销号结果。","sort":5,"depth":1},{"source_id":7,"parent_source_id":0,"source_code":"A07","name":"水利工程","description":"围绕水库、水闸、泵站、堤防、渠道等工程的运行、安全、养护和评价组织成果，支撑工程全生命周期监管。","sort":7,"depth":0},{"source_id":8,"parent_source_id":0,"source_code":"A08","name":"水土保持","description":"围绕水土流失监测、综合治理、生产建设项目监管和遥感监督组织成果，服务水土保持监管与评价。","sort":8,"depth":0},{"source_id":9,"parent_source_id":0,"source_code":"A09","name":"河湖生态","description":"围绕河湖健康、生态流量、水域岸线、水质生态和河湖问题监管形成专题成果，服务河湖生态保护与修复。","sort":9,"depth":0},{"source_id":10,"parent_source_id":0,"source_code":"A10","name":"空间专题","description":"面向水利一张图和空间分析应用，组织水系、工程、站网、风险和资源分区等空间成果，提供统一地图数据支撑。","sort":10,"depth":0},{"source_id":11,"parent_source_id":0,"source_code":"A11","name":"统计共享","description":"面向统计上报、跨部门交换、社会开放和成果归档组织可交付的数据产品，支撑规范化共享和成果复用。","sort":11,"depth":0}]
$categories$::jsonb) AS item(source_id bigint, parent_source_id bigint, source_code text,
    name varchar(50), description varchar(256), sort integer, depth integer);

CREATE TEMP TABLE qdata_market_asset_seed (
    source_id varchar(64) PRIMARY KEY,
    asset_name varchar(256) NOT NULL,
    display_name varchar(256) NOT NULL,
    asset_type integer NOT NULL,
    primary_delivery_type integer NOT NULL,
    category_code text NOT NULL,
    description varchar(256),
    frequency varchar(64) NOT NULL,
    delivery_types varchar(32) NOT NULL,
    table_name varchar(200),
    file_name varchar(255),
    file_type varchar(128),
    service_identifier varchar(128),
    row_count bigint,
    field_count integer NOT NULL,
    layer varchar(20),
    subject_domain_name varchar(200),
    publish_time timestamp NOT NULL,
    tags jsonb NOT NULL
) ON COMMIT DROP;
INSERT INTO qdata_market_asset_seed
SELECT * FROM jsonb_to_recordset($assets$
[{"source_id":"23","asset_name":"河道水情_20260805","display_name":"qqq","asset_type":3,"primary_delivery_type":3,"category_code":"A02","description":null,"frequency":"每周 (Weekly)","delivery_types":"3","table_name":null,"file_name":"河道水情_20260805.csv","file_type":"CSV文件(.csv)","service_identifier":null,"row_count":0,"field_count":0,"layer":null,"subject_domain_name":"交易域","publish_time":"2026-10-22 22:31:08","tags":[]},{"source_id":"22","asset_name":"贴源逻辑表_河流维表","display_name":"河流维表","asset_type":1,"primary_delivery_type":1,"category_code":"A12","description":"这是河流维表信息","frequency":"实时(T+0)","delivery_types":"1,2","table_name":"ODS_RH_BO_ST_RV_B","file_name":null,"file_type":null,"service_identifier":null,"row_count":0,"field_count":16,"layer":"ODS","subject_domain_name":null,"publish_time":"2026-10-22 18:52:11","tags":["河道水情","河流"]},{"source_id":"17","asset_name":"贴源逻辑表_水位站","display_name":"呃呃","asset_type":1,"primary_delivery_type":1,"category_code":"A01","description":null,"frequency":"每天(T+1)","delivery_types":"1","table_name":"ods_rh_bo_st_z_stbprp_b","file_name":null,"file_type":null,"service_identifier":null,"row_count":0,"field_count":19,"layer":"ODS","subject_domain_name":null,"publish_time":"2026-10-22 15:04:00","tags":[]},{"source_id":"14","asset_name":"实时雨量","display_name":"lstest","asset_type":1,"primary_delivery_type":1,"category_code":"A01","description":"记录各雨量站最新降水监测数据，包括采集时间、时段雨量、累计雨量及状态信息，用于实时雨情展示、预警研判和应急调度。","frequency":"实时(T+0)","delivery_types":"1,2,3","table_name":"ODS_RH_RTW_ST_PPTN_R","file_name":null,"file_type":null,"service_identifier":null,"row_count":6,"field_count":11,"layer":"ODS","subject_domain_name":"水文监测","publish_time":"2026-10-22 09:42:30","tags":["河道水情","雨量指标","水位指标"]},{"source_id":"13","asset_name":"河流表","display_name":"河流表","asset_type":1,"primary_delivery_type":1,"category_code":"A02","description":"存储河流编码、名称、水系归属、行政区划及基础管理属性，为河段断面关联、水情数据汇聚和跨业务查询提供统一河流主数据。","frequency":"每小时(T+H)","delivery_types":"1,2,3","table_name":"ODS_RH_BO_ST_RV_B","file_name":null,"file_type":null,"service_identifier":null,"row_count":3,"field_count":16,"layer":"ODS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:37:30","tags":["河道水情","堤防","雨量指标"]},{"source_id":"12","asset_name":"断面表","display_name":"断面表","asset_type":1,"primary_delivery_type":2,"category_code":"A02A07","description":"记录河道监测断面的编码、名称、空间位置、所属河流及管理属性，为水位流量监测、断面关联查询和水情分析提供统一基础信息。","frequency":"每天(T+1)","delivery_types":"1,2,3","table_name":"ODS_RH_BO_ST_SEC_B","file_name":null,"file_type":null,"service_identifier":"49","row_count":12,"field_count":17,"layer":"ODS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:37:03","tags":["河道水情","基础主数据"]},{"source_id":"11","asset_name":"历史雨量","display_name":"历史雨量","asset_type":1,"primary_delivery_type":1,"category_code":"A02","description":"汇集各雨量站历史时段的降水观测记录，包括监测时间、累计雨量和站点标识，用于降雨过程回溯、同期对比及趋势研判。","frequency":"每天(T+1)","delivery_types":"1,3","table_name":"ODS_RH_MON_ST_PPTN_HIS_R","file_name":null,"file_type":null,"service_identifier":null,"row_count":100,"field_count":11,"layer":"ODS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:36:17","tags":["河道水情","雨量指标","水位指标"]},{"source_id":"10","asset_name":"河道水情综合态势","display_name":"河道水情综合态势","asset_type":1,"primary_delivery_type":1,"category_code":"A02","description":"整合河流、站点、水位、雨量和流量等核心指标，形成河道水情综合态势数据，为全局监控、风险研判和调度决策提供支撑。","frequency":"每天(T+1)","delivery_types":"1,2,3","table_name":"ADS_AP_API_APP_RV_WTROV_A","file_name":null,"file_type":null,"service_identifier":null,"row_count":6,"field_count":30,"layer":"ADS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:34:12","tags":["河道水情","水位指标","水量指标"]},{"source_id":"9","asset_name":"水情简报数据服务","display_name":"水情简报数据","asset_type":1,"primary_delivery_type":1,"category_code":"A02","description":"面向水情简报生成汇集水位、雨量、流量及涨落变化等指标，提供统一的数据服务口径，支撑简报编制、查询与对外共享。","frequency":"每天(T+1)","delivery_types":"1,3","table_name":"ADS_AP_API_APP_WTR_RPT_SVC_A","file_name":null,"file_type":null,"service_identifier":null,"row_count":0,"field_count":37,"layer":"ADS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:32:06","tags":["河道水情","水位指标","水量指标"]},{"source_id":"8","asset_name":"水情趋势概览","display_name":"水情趋势概览","asset_type":1,"primary_delivery_type":1,"category_code":"A02","description":"汇总重点站点水位、流量和雨量的阶段变化及对比指标，用于展示水情趋势概览，辅助快速识别异常波动和重点关注区域。","frequency":"每天(T+1)","delivery_types":"1,3","table_name":"ADS_AP_API_APP_WTR_TRD_A","file_name":null,"file_type":null,"service_identifier":null,"row_count":0,"field_count":30,"layer":"ADS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:31:41","tags":["河道水情","水情总览"]},{"source_id":"7","asset_name":"涨落趋势分析","display_name":"涨落趋势分析","asset_type":1,"primary_delivery_type":1,"category_code":"A02","description":"基于连续水位与流量监测数据计算涨落幅度、变化方向和持续时间，为趋势图表展示、异常波动识别及水情研判提供依据。","frequency":"实时(T+0)","delivery_types":"1,3","table_name":"ADS_AP_STA_APP_WTR_TRD_ANA_A","file_name":null,"file_type":null,"service_identifier":null,"row_count":0,"field_count":30,"layer":"ADS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:31:08","tags":["河道水情","堤防","水位指标"]},{"source_id":"6","asset_name":"年均流量","display_name":"年均流量","asset_type":1,"primary_delivery_type":3,"category_code":"A02","description":"按监测站点和年度汇总历史流量，形成年平均流量及相关统计指标，用于年际变化对比、长期趋势研判和水资源评价分析。","frequency":"实时(T+0)","delivery_types":"1,3","table_name":"DWS_RH_HIST_ST_Q_YEAR_S","file_name":"年均流量","file_type":null,"service_identifier":null,"row_count":0,"field_count":20,"layer":"DWS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:29:11","tags":["年度更新","流域范围","流量指标"]},{"source_id":"5","asset_name":"日均流量","display_name":"日均流量","asset_type":1,"primary_delivery_type":3,"category_code":"A02","description":"按监测站点和日期汇总流量观测数据，形成日平均流量及相关统计指标，用于日尺度变化分析、站点对比和水资源调度评估。","frequency":"实时(T+0)","delivery_types":"1,3","table_name":"DWS_RH_STAT_ST_Q_DAY_S","file_name":"日均流量","file_type":null,"service_identifier":null,"row_count":0,"field_count":20,"layer":"DWS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:28:42","tags":["每日更新","流量指标","流域范围"]},{"source_id":"4","asset_name":"日水位统计","display_name":"日水位统计","asset_type":1,"primary_delivery_type":1,"category_code":"A02","description":"按监测站点和日期汇总历史水位，形成日平均、最高、最低水位及变化指标，用于日尺度过程分析、站点对比和水情复盘。","frequency":"实时(T+0)","delivery_types":"1,3","table_name":"DWS_RH_HIST_ST_Z_DAY_S","file_name":null,"file_type":null,"service_identifier":null,"row_count":27304,"field_count":20,"layer":"DWS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:28:14","tags":["每日更新","河道水情","水位指标"]},{"source_id":"3","asset_name":"涨落趋势","display_name":"水位涨落趋势","asset_type":1,"primary_delivery_type":3,"category_code":"A02","description":"汇总连续时段的水位与流量变化，计算涨落方向、幅度、速率和持续时间，用于水情趋势监测、异常波动识别及风险研判。","frequency":"每小时(T+H)","delivery_types":"1,3","table_name":"DWS_RH_MON_ST_WTR_TRD_S","file_name":"水位涨落趋势","file_type":null,"service_identifier":null,"row_count":0,"field_count":23,"layer":"DWS","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:27:34","tags":["河道水情","防洪减灾","堤防"]},{"source_id":"2","asset_name":"河段维表","display_name":"河段维表","asset_type":1,"primary_delivery_type":1,"category_code":"A02A07","description":"统一河段编码、名称、起止位置、河流归属及管理属性等维度信息，支撑断面和站点关联、分段水情统计及河道空间分析。","frequency":"实时(T+0)","delivery_types":"1,2,3","table_name":"DIM_RH_BO_ST_RCH_B","file_name":null,"file_type":null,"service_identifier":null,"row_count":0,"field_count":13,"layer":"DIM","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:24:28","tags":["河道水情","河流"]},{"source_id":"1","asset_name":"流量站维表","display_name":"流量站维表","asset_type":1,"primary_delivery_type":1,"category_code":"A02A07","description":"统一流量站编码、名称、空间位置、河流归属、行政区划及运行状态等维度口径，支撑流量明细关联、站点查询和统计分析。","frequency":"每天(T+1)","delivery_types":"1,3","table_name":"DIM_RH_BO_ST_Q_STBPRP_B","file_name":null,"file_type":null,"service_identifier":null,"row_count":0,"field_count":19,"layer":"DIM","subject_domain_name":"水文监测","publish_time":"2026-08-05 13:23:38","tags":["流量指标","生态流量","河道水情"]}]
$assets$::jsonb) AS item(source_id varchar(64), asset_name varchar(256), display_name varchar(256),
    asset_type integer, primary_delivery_type integer, category_code text, description varchar(256),
    frequency varchar(64), delivery_types varchar(32), table_name varchar(200), file_name varchar(255),
    file_type varchar(128), service_identifier varchar(128), row_count bigint, field_count integer,
    layer varchar(20), subject_domain_name varchar(200), publish_time timestamp, tags jsonb);

DO $import$
DECLARE
    relation_name text;
    sequence_name text;
    sequence_value bigint;
    sequence_called boolean;
    sequence_increment bigint;
    sequence_max bigint;
    sequence_cycles boolean;
    first_id numeric;
    category record;
    item record;
    tag_name text;
    parent_id_value bigint;
    local_category_id bigint;
    local_asset_id bigint;
    local_version_id bigint;
    local_tag_id bigint;
    matching_count integer;
    existing_asset record;
    tag_snapshot jsonb;
    frozen_content text;
BEGIN
    -- 在写表锁内对齐六个真实序列，避免历史手工编号导致碰撞，不重用业务主键。
    FOREACH relation_name IN ARRAY ARRAY['dh_asset_market_category', 'dh_asset_data_asset',
        'dh_asset_catalog_version', 'dh_asset_tag', 'dh_asset_tag_rel'] LOOP
        sequence_name := relation_name || '_seq';
        SELECT seqincrement, seqmax, seqcycle INTO sequence_increment, sequence_max, sequence_cycles
          FROM pg_sequence WHERE seqrelid = sequence_name::regclass;
        IF sequence_increment IS DISTINCT FROM 1 OR sequence_cycles IS DISTINCT FROM false THEN
            RAISE EXCEPTION '目录迁入要求序列 % 使用步长一且禁止循环', sequence_name;
        END IF;
        EXECUTE format('SELECT last_value, is_called FROM %I', sequence_name)
           INTO sequence_value, sequence_called;
        EXECUTE format('SELECT greatest(coalesce(max(id), 0)::numeric + 1, $1::numeric + $2) FROM %I', relation_name)
           INTO first_id USING sequence_value, CASE WHEN sequence_called THEN 1 ELSE 0 END;
        IF first_id + 256 > sequence_max THEN
            RAISE EXCEPTION '目录迁入序列 % 剩余空间不足', sequence_name;
        END IF;
        EXECUTE format('ALTER SEQUENCE %I RESTART WITH %s', sequence_name, first_id);
    END LOOP;

    FOR category IN SELECT * FROM qdata_market_category_seed ORDER BY depth, parent_source_id, sort, source_id LOOP
        parent_id_value := 0;
        IF category.parent_source_id <> 0 THEN
            SELECT resolved_id INTO parent_id_value FROM qdata_market_category_seed
             WHERE source_id = category.parent_source_id;
            IF parent_id_value IS NULL THEN RAISE EXCEPTION '来源类目缺少可核验父级'; END IF;
        END IF;
        SELECT count(*), min(id) INTO matching_count, local_category_id FROM dh_asset_market_category
         WHERE parent_id = parent_id_value AND name = category.name AND deleted = 0;
        IF matching_count > 1 THEN RAISE EXCEPTION '本地集市类目路径不唯一'; END IF;
        IF matching_count = 0 THEN
            INSERT INTO dh_asset_market_category(parent_id, name, description, status, sort, creator, updater)
            VALUES (parent_id_value, category.name, category.description, 0, category.sort, 'migration:0219', 'migration:0219')
            RETURNING id INTO local_category_id;
        ELSE
            -- 只调整由旧类目种子原样维护的顺序；人工更新过的类目完全保留。
            UPDATE dh_asset_market_category SET sort = category.sort, updater = 'migration:0219'
             WHERE id = local_category_id AND creator IN ('migration:0202', 'migration:0219')
               AND updater IN ('migration:0202', 'migration:0219') AND sort <> category.sort;
        END IF;
        UPDATE qdata_market_category_seed SET resolved_id = local_category_id WHERE source_id = category.source_id;
    END LOOP;

    FOR item IN SELECT * FROM qdata_market_asset_seed ORDER BY publish_time, source_id LOOP
        SELECT count(*) INTO matching_count FROM dh_asset_data_asset WHERE asset_code = 'QDATA_MARKET_' || item.source_id;
        IF matching_count > 0 THEN
            SELECT * INTO existing_asset FROM dh_asset_data_asset
             WHERE asset_code = 'QDATA_MARKET_' || item.source_id ORDER BY id LIMIT 1;
            IF matching_count <> 1 OR existing_asset.external_code IS DISTINCT FROM 'qdata:market:' || item.source_id
                    OR existing_asset.deleted <> 0 THEN
                RAISE EXCEPTION '来源目录编码冲突或已人工删除，拒绝覆盖：%', item.source_id;
            END IF;
            CONTINUE;
        END IF;
        SELECT resolved_id INTO local_category_id FROM qdata_market_category_seed WHERE source_code = item.category_code;
        IF local_category_id IS NULL THEN RAISE EXCEPTION '来源集市目录缺少类目：%', item.category_code; END IF;

        INSERT INTO dh_asset_data_asset(asset_code, catalog_item_urn, asset_name, asset_type,
            table_name, layer, subject_domain_name, row_count, field_count, description, status,
            publish_time, view_count, share_attribute, share_attr, update_cycle,
            catalog_revision, tech_available, external_code, creator, updater, create_time, update_time)
        VALUES ('QDATA_MARKET_' || item.source_id, 'urn:datahub:catalog:qdata:market:' || item.source_id,
            item.asset_name, item.asset_type, item.table_name, item.layer, item.subject_domain_name,
            item.row_count, item.field_count, item.description, 1, item.publish_time, 0, 0, 0,
            item.frequency, 1, 0, 'qdata:market:' || item.source_id, 'migration:0219', 'migration:0219',
            item.publish_time, item.publish_time)
        RETURNING id INTO local_asset_id;

        tag_snapshot := '[]'::jsonb;
        FOR tag_name IN SELECT value FROM jsonb_array_elements_text(item.tags) LOOP
            SELECT id INTO local_tag_id FROM dh_asset_tag WHERE name = tag_name AND deleted = 0;
            IF local_tag_id IS NULL THEN
                INSERT INTO dh_asset_tag(name, color, status, creator, updater)
                VALUES (tag_name, '#4266f4', 0, 'migration:0219', 'migration:0219') RETURNING id INTO local_tag_id;
            END IF;
            INSERT INTO dh_asset_tag_rel(asset_id, tag_id, creator, updater)
            VALUES (local_asset_id, local_tag_id, 'migration:0219', 'migration:0219');
            tag_snapshot := tag_snapshot || jsonb_build_array(jsonb_build_object('id', local_tag_id, 'name', tag_name));
        END LOOP;
        -- 冻结的只是公开目录证据；技术快照、授权、审批人及远端身份均保持为空。
        frozen_content := jsonb_build_object('format', 1,
            'asset', jsonb_build_object('id', local_asset_id, 'assetCode', 'QDATA_MARKET_' || item.source_id,
                'catalogItemUrn', 'urn:datahub:catalog:qdata:market:' || item.source_id,
                'assetName', item.asset_name, 'assetType', item.asset_type, 'tableName', item.table_name,
                'layer', item.layer, 'subjectDomainName', item.subject_domain_name,
                'rowCount', item.row_count, 'fieldCount', item.field_count, 'description', item.description,
                'status', 1, 'shareAttr', 0, 'shareAttribute', 0, 'updateCycle', item.frequency,
                'catalogRevision', 1, 'techAvailable', 0, 'externalCode', 'qdata:market:' || item.source_id),
            'tags', tag_snapshot)::text;
        INSERT INTO dh_asset_catalog_version(asset_id, version_no, content_json, content_hash, status,
            publish_time, check_note, creator, updater, create_time, update_time)
        VALUES (local_asset_id, 1, frozen_content, encode(sha256(convert_to(frozen_content, 'UTF8')), 'hex'), 4,
            item.publish_time, '外部公开目录迁入；未绑定本地技术交付，不继承远端授权和审批身份',
            'migration:0219', 'migration:0219', item.publish_time, item.publish_time)
        RETURNING id INTO local_version_id;
        UPDATE dh_asset_data_asset SET published_catalog_version_id = local_version_id WHERE id = local_asset_id;

        INSERT INTO dh_asset_market_listing(asset_id, market_category_id, display_name, description,
            frequency, delivery_types, revision, source_record_id, file_name, file_type, service_identifier,
            primary_delivery_type, display_score, creator, updater, create_time, update_time)
        VALUES (local_asset_id, local_category_id, item.display_name, item.description, item.frequency,
            item.delivery_types, 1, item.source_id, item.file_name, item.file_type, item.service_identifier,
            item.primary_delivery_type, '98.5', 'migration:0219', 'migration:0219', item.publish_time, item.publish_time);
    END LOOP;

    IF (SELECT count(*) FROM qdata_market_category_seed WHERE resolved_id IS NOT NULL) <> 82 THEN
        RAISE EXCEPTION '集市类目未完整迁入';
    END IF;
    IF (SELECT count(*) FROM dh_asset_data_asset asset JOIN qdata_market_asset_seed seed
         ON asset.asset_code = 'QDATA_MARKET_' || seed.source_id AND asset.deleted = 0) <> 17 THEN
        RAISE EXCEPTION '公开集市目录未完整迁入';
    END IF;
END;
$import$;

COMMIT;

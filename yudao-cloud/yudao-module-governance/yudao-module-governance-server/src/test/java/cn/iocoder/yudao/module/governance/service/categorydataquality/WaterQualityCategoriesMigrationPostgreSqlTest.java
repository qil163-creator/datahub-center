package cn.iocoder.yudao.module.governance.service.categorydataquality;

import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.AuditRuleMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotNull;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离 PostgreSQL 验证质量类目替换、历史规则保留和真实读写契约。 */
@Testcontainers(disabledWithoutDocker = true)
class WaterQualityCategoriesMigrationPostgreSqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "0201_governance_water-quality-categories.sql";
    private static final String CATEGORY = "dh_governance_category_data_quality";
    private static final String SEQUENCE = "dh_foundation_category_data_quality_seq";
    private static final String AUDIT = "dh_governance_audit_rule";
    private static final String QUALITY = "dh_governance_quality_rule";
    private static final String LEGACY = "dh_governance_quality_audit_rule";
    private static final String TASK = "dh_governance_quality_task";
    private static final List<String> BUSINESS_TABLES = List.of(AUDIT, QUALITY, LEGACY, TASK);
    private static final List<String> SNAPSHOT_TABLES = List.of("dh_governance_quality_task_instance",
            "dh_governance_standard_element_version", "dh_governance_quality_task_evaluation");

    // 独立取自授权只读接口的完整树，未读取待测 SQL；远站编号只用于转换父子关系，不进入期望本地主键。
    // 原始脱敏树 SHA-256：aeca45178f7db44432b6476763b45fcc04076c8bb29ebcb77980025ed2fbb8af。
    // 每行依次为父类名称、名称、排序、状态、完整 description；远站 remark 为空，不能替代 description。
    private static final List<ExpectedCategory> EXPECTED = """
            |河道水情|0|0|用于管理河流、河段、断面和测站等基础对象，以及水位、流量、雨量、来水下泄、防洪预警、生态基流和历史统计相关数据的质量任务，重点保障监测数据连续、量值合理、上下游关系正确、统计口径统一和成果发布及时。
            |灌区管理|1|0|用于管理灌区、渠系、泵站、闸门和量测设施等对象，以及灌溉计划、取用水计量、配水调度、工程运行和统计分析相关数据的质量任务，重点保障对象关系完整、计量数据准确、计划执行一致和调度过程可追溯。
            |防洪减灾|2|0|用于管理防洪工程、雨水情监测、预警指标、灾情险情、防汛物资、抢险队伍和应急处置相关数据的质量任务，重点保障预警阈值有效、灾情信息准确、事件过程完整、应急资源可用和处置状态闭环。
            |农村供水|3|0|用于管理供水工程、水源地、供水管网、供水对象、取用水计量、水质监测、巡检养护和应急保障相关数据的质量任务，重点保障设施信息完整、供水计量准确、水质指标合规、巡检记录真实和风险处置及时。
            |水土保持|4|0|用于管理生产建设项目、水土流失监测、遥感图斑、治理工程、方案审批、监督检查和评价考核相关数据的质量任务，重点保障图斑位置准确、监测指标完整、项目关系一致、治理措施可核验和监督过程可追溯。
            |水资源管理|5|0|用于管理水资源分区、取水口、取用水户、取水许可、计量监测、地下水和水资源配置调度相关数据的质量任务，重点保障许可与对象关联正确、计量数据连续、取用水量口径一致、地下水数据合理和调度结果可核查。
            |水利工程管理|6|0|用于管理水库、堤防、水闸、泵站、渠道和机电设备等工程对象，以及安全监测、指标规则、巡检养护、风险隐患和运行调度相关数据的质量任务，重点保障工程档案完整、监测数据可信、运行状态准确和隐患处置闭环。
            |生态流量监管|7|0|用于管理生态流量控制断面、目标流量、实时监测、阈值规则、预警告警、达标评价和异常识别相关数据的质量任务，重点保障断面关系正确、目标值有效、监测过程连续、达标计算准确和监管结果一致。
            河道水情|基础对象|0|0|用于组织河道水情业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|实时水情|1|0|用于组织河道水情业务中实时水情相关数据的质量检查任务，重点检查测站编码、监测时间、时序连续性、水位流量范围、重复记录和数据上报及时性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|防洪预警|2|0|用于组织河道水情业务中防洪预警相关数据的质量检查任务，重点检查预警对象、预警指标、阈值规则、等级判定、发布时间、解除时间和处置状态一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|生态基流|3|0|用于组织河道水情业务中生态基流相关数据的质量检查任务，重点检查控制断面、目标流量、考核时段、实时流量、达标判定和连续不达标过程准确性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|来水下泄|4|0|用于组织河道水情业务中来水下泄相关数据的质量检查任务，重点检查来水量、下泄流量、上下游断面关系、时间对齐、水量平衡和变化趋势合理性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|历史统计|5|0|用于组织河道水情业务中历史统计相关数据的质量检查任务，重点检查统计周期、起止时间、汇总口径、历史序列完整性、同期对比和统计结果准确性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|异常识别|6|0|用于组织河道水情业务中异常识别相关数据的质量检查任务，重点检查异常类型、识别规则、影响对象、发生时间、异常状态、处置结果和闭环记录完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|数据看板|7|0|用于组织河道水情业务中数据看板相关数据的质量检查任务，重点检查指标口径、展示周期、维度筛选、汇总结果、数据刷新时间和页面展示一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            河道水情|非结构化数据|8|0|用于组织河道水情业务中非结构化数据相关数据的质量检查任务，重点检查文件名称、文件类型、版本信息、内容摘要、关联对象、存储地址和附件可用性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|基础对象|0|0|用于组织灌区管理业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|工程设施|1|0|用于组织灌区管理业务中工程设施相关数据的质量检查任务，重点检查工程编码、工程类型、建设状态、空间位置、管理单位、设计参数和设施关系完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|计划方案|2|0|用于组织灌区管理业务中计划方案相关数据的质量检查任务，重点检查计划编码、适用范围、执行周期、目标指标、审批状态、版本衔接和执行结果一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|取用水|3|0|用于组织灌区管理业务中取用水相关数据的质量检查任务，重点检查取水口、用水户、用途、取水水源、取水量、用水量、退水量和统计周期逻辑合理性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|计量监测|4|0|用于组织灌区管理业务中计量监测相关数据的质量检查任务，重点检查计量设施编码、设备状态、读数时间、瞬时流量、累计水量、量纲单位和数据连续性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|调度运行|5|0|用于组织灌区管理业务中调度运行相关数据的质量检查任务，重点检查调度指令、执行对象、计划值、执行值、启停状态、运行时段和调度反馈一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|统计分析|6|0|用于组织灌区管理业务中统计分析相关数据的质量检查任务，重点检查指标定义、统计维度、统计周期、计算公式、汇总范围、结果精度和跨报表口径一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|空间地理|7|0|用于组织灌区管理业务中空间地理相关数据的质量检查任务，重点检查空间坐标、坐标系、几何类型、拓扑关系、行政区归属、空间范围和图属一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|档案资料|8|0|用于组织灌区管理业务中档案资料相关数据的质量检查任务，重点检查档案编号、资料类型、形成时间、版本状态、关联对象、文件完整性和存储可访问性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            灌区管理|应用服务|9|0|用于组织灌区管理业务中应用服务相关数据的质量检查任务，重点检查服务编码、接口参数、返回结构、数据口径、更新时间、调用状态和服务结果完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|基础对象|0|0|用于组织防洪减灾业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|工程设施|1|0|用于组织防洪减灾业务中工程设施相关数据的质量检查任务，重点检查工程编码、工程类型、建设状态、空间位置、管理单位、设计参数和设施关系完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|雨水情|2|0|用于组织防洪减灾业务中雨水情相关数据的质量检查任务，重点检查雨量、水位、流量、监测时间、测站关系、时序变化、极值范围和数据上报及时性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|监测感知|3|0|用于组织防洪减灾业务中监测感知相关数据的质量检查任务，重点检查监测对象、设备编码、采集指标、采集时间、设备状态、量值范围和采集连续性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|指标规则|4|0|用于组织防洪减灾业务中指标规则相关数据的质量检查任务，重点检查指标编码、指标名称、计算口径、阈值范围、生效时间、适用对象和版本状态一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|预警告警|5|0|用于组织防洪减灾业务中预警告警相关数据的质量检查任务，重点检查告警对象、触发条件、告警等级、发生时间、推送状态、处置状态和解除条件完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|灾情险情|6|0|用于组织防洪减灾业务中灾情险情相关数据的质量检查任务，重点检查事件编号、发生位置、发生时间、受灾对象、损失数据、事件等级和处置进展准确性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|物资队伍|7|0|用于组织防洪减灾业务中物资队伍相关数据的质量检查任务，重点检查物资编码、库存数量、存放位置、有效期限、队伍人员、联系方式和可调度状态准确性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|应急处置|8|0|用于组织防洪减灾业务中应急处置相关数据的质量检查任务，重点检查事件来源、响应等级、处置任务、责任单位、处置过程、反馈结果和闭环时间完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|统计分析|9|0|用于组织防洪减灾业务中统计分析相关数据的质量检查任务，重点检查指标定义、统计维度、统计周期、计算公式、汇总范围、结果精度和跨报表口径一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|空间地理|10|0|用于组织防洪减灾业务中空间地理相关数据的质量检查任务，重点检查空间坐标、坐标系、几何类型、拓扑关系、行政区归属、空间范围和图属一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|档案资料|11|0|用于组织防洪减灾业务中档案资料相关数据的质量检查任务，重点检查档案编号、资料类型、形成时间、版本状态、关联对象、文件完整性和存储可访问性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            防洪减灾|应用服务|12|0|用于组织防洪减灾业务中应用服务相关数据的质量检查任务，重点检查服务编码、接口参数、返回结构、数据口径、更新时间、调用状态和服务结果完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|基础对象|0|0|用于组织农村供水业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|工程设施|1|0|用于组织农村供水业务中工程设施相关数据的质量检查任务，重点检查工程编码、工程类型、建设状态、空间位置、管理单位、设计参数和设施关系完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|取用水|2|0|用于组织农村供水业务中取用水相关数据的质量检查任务，重点检查取水口、用水户、用途、取水水源、取水量、用水量、退水量和统计周期逻辑合理性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|计量监测|3|0|用于组织农村供水业务中计量监测相关数据的质量检查任务，重点检查计量设施编码、设备状态、读数时间、瞬时流量、累计水量、量纲单位和数据连续性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|水质监测|4|0|用于组织农村供水业务中水质监测相关数据的质量检查任务，重点检查监测断面、采样时间、检测指标、检测方法、计量单位、标准限值和达标判定准确性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|巡检养护|5|0|用于组织农村供水业务中巡检养护相关数据的质量检查任务，重点检查巡检计划、巡检对象、执行人员、巡检时间、发现问题、养护措施和完成状态一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|风险隐患|6|0|用于组织农村供水业务中风险隐患相关数据的质量检查任务，重点检查隐患编号、风险等级、影响对象、发现时间、整改责任、整改期限和复核销号完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|应急处置|7|0|用于组织农村供水业务中应急处置相关数据的质量检查任务，重点检查事件来源、响应等级、处置任务、责任单位、处置过程、反馈结果和闭环时间完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|统计分析|8|0|用于组织农村供水业务中统计分析相关数据的质量检查任务，重点检查指标定义、统计维度、统计周期、计算公式、汇总范围、结果精度和跨报表口径一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|空间地理|9|0|用于组织农村供水业务中空间地理相关数据的质量检查任务，重点检查空间坐标、坐标系、几何类型、拓扑关系、行政区归属、空间范围和图属一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|档案资料|10|0|用于组织农村供水业务中档案资料相关数据的质量检查任务，重点检查档案编号、资料类型、形成时间、版本状态、关联对象、文件完整性和存储可访问性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            农村供水|应用服务|11|0|用于组织农村供水业务中应用服务相关数据的质量检查任务，重点检查服务编码、接口参数、返回结构、数据口径、更新时间、调用状态和服务结果完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|基础对象|0|0|用于组织水土保持业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|监测感知|1|0|用于组织水土保持业务中监测感知相关数据的质量检查任务，重点检查监测对象、设备编码、采集指标、采集时间、设备状态、量值范围和采集连续性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|遥感图斑|2|0|用于组织水土保持业务中遥感图斑相关数据的质量检查任务，重点检查图斑编号、影像时相、空间位置、图斑面积、变化类型、解译结果和核查状态准确性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|工程设施|3|0|用于组织水土保持业务中工程设施相关数据的质量检查任务，重点检查工程编码、工程类型、建设状态、空间位置、管理单位、设计参数和设施关系完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|计划方案|4|0|用于组织水土保持业务中计划方案相关数据的质量检查任务，重点检查计划编码、适用范围、执行周期、目标指标、审批状态、版本衔接和执行结果一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|巡检养护|5|0|用于组织水土保持业务中巡检养护相关数据的质量检查任务，重点检查巡检计划、巡检对象、执行人员、巡检时间、发现问题、养护措施和完成状态一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|监督检查|6|0|用于组织水土保持业务中监督检查相关数据的质量检查任务，重点检查检查对象、检查事项、检查时间、发现问题、整改要求、整改反馈和复核结论完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|评价考核|7|0|用于组织水土保持业务中评价考核相关数据的质量检查任务，重点检查评价对象、指标体系、考核周期、指标权重、计算过程、评价结果和结果发布一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|统计分析|8|0|用于组织水土保持业务中统计分析相关数据的质量检查任务，重点检查指标定义、统计维度、统计周期、计算公式、汇总范围、结果精度和跨报表口径一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|空间地理|9|0|用于组织水土保持业务中空间地理相关数据的质量检查任务，重点检查空间坐标、坐标系、几何类型、拓扑关系、行政区归属、空间范围和图属一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|档案资料|10|0|用于组织水土保持业务中档案资料相关数据的质量检查任务，重点检查档案编号、资料类型、形成时间、版本状态、关联对象、文件完整性和存储可访问性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水土保持|应用服务|11|0|用于组织水土保持业务中应用服务相关数据的质量检查任务，重点检查服务编码、接口参数、返回结构、数据口径、更新时间、调用状态和服务结果完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|基础对象|0|0|用于组织水资源管理业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|取用水|1|0|用于组织水资源管理业务中取用水相关数据的质量检查任务，重点检查取水口、用水户、用途、取水水源、取水量、用水量、退水量和统计周期逻辑合理性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|取水许可|2|0|用于组织水资源管理业务中取水许可相关数据的质量检查任务，重点检查许可证号、许可对象、取水水源、许可水量、许可期限、审批状态和变更记录一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|计量监测|3|0|用于组织水资源管理业务中计量监测相关数据的质量检查任务，重点检查计量设施编码、设备状态、读数时间、瞬时流量、累计水量、量纲单位和数据连续性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|地下水|4|0|用于组织水资源管理业务中地下水相关数据的质量检查任务，重点检查地下水井、监测层位、地下水位、取水量、监测时间、超采判定和空间分区合理性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|调度运行|5|0|用于组织水资源管理业务中调度运行相关数据的质量检查任务，重点检查调度指令、执行对象、计划值、执行值、启停状态、运行时段和调度反馈一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|统计分析|6|0|用于组织水资源管理业务中统计分析相关数据的质量检查任务，重点检查指标定义、统计维度、统计周期、计算公式、汇总范围、结果精度和跨报表口径一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|空间地理|7|0|用于组织水资源管理业务中空间地理相关数据的质量检查任务，重点检查空间坐标、坐标系、几何类型、拓扑关系、行政区归属、空间范围和图属一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|档案资料|8|0|用于组织水资源管理业务中档案资料相关数据的质量检查任务，重点检查档案编号、资料类型、形成时间、版本状态、关联对象、文件完整性和存储可访问性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水资源管理|应用服务|9|0|用于组织水资源管理业务中应用服务相关数据的质量检查任务，重点检查服务编码、接口参数、返回结构、数据口径、更新时间、调用状态和服务结果完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|基础对象|0|0|用于组织水利工程管理业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|工程设施|1|0|用于组织水利工程管理业务中工程设施相关数据的质量检查任务，重点检查工程编码、工程类型、建设状态、空间位置、管理单位、设计参数和设施关系完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|监测感知|2|0|用于组织水利工程管理业务中监测感知相关数据的质量检查任务，重点检查监测对象、设备编码、采集指标、采集时间、设备状态、量值范围和采集连续性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|指标规则|3|0|用于组织水利工程管理业务中指标规则相关数据的质量检查任务，重点检查指标编码、指标名称、计算口径、阈值范围、生效时间、适用对象和版本状态一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|巡检养护|4|0|用于组织水利工程管理业务中巡检养护相关数据的质量检查任务，重点检查巡检计划、巡检对象、执行人员、巡检时间、发现问题、养护措施和完成状态一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|风险隐患|5|0|用于组织水利工程管理业务中风险隐患相关数据的质量检查任务，重点检查隐患编号、风险等级、影响对象、发现时间、整改责任、整改期限和复核销号完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|调度运行|6|0|用于组织水利工程管理业务中调度运行相关数据的质量检查任务，重点检查调度指令、执行对象、计划值、执行值、启停状态、运行时段和调度反馈一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|统计分析|7|0|用于组织水利工程管理业务中统计分析相关数据的质量检查任务，重点检查指标定义、统计维度、统计周期、计算公式、汇总范围、结果精度和跨报表口径一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|空间地理|8|0|用于组织水利工程管理业务中空间地理相关数据的质量检查任务，重点检查空间坐标、坐标系、几何类型、拓扑关系、行政区归属、空间范围和图属一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|档案资料|9|0|用于组织水利工程管理业务中档案资料相关数据的质量检查任务，重点检查档案编号、资料类型、形成时间、版本状态、关联对象、文件完整性和存储可访问性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            水利工程管理|应用服务|10|0|用于组织水利工程管理业务中应用服务相关数据的质量检查任务，重点检查服务编码、接口参数、返回结构、数据口径、更新时间、调用状态和服务结果完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|基础对象|0|0|用于组织生态流量监管业务中基础对象相关数据的质量检查任务，重点检查对象编码唯一性、名称规范性、层级归属、关联关系、空间位置和关键属性完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|监测感知|1|0|用于组织生态流量监管业务中监测感知相关数据的质量检查任务，重点检查监测对象、设备编码、采集指标、采集时间、设备状态、量值范围和采集连续性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|指标规则|2|0|用于组织生态流量监管业务中指标规则相关数据的质量检查任务，重点检查指标编码、指标名称、计算口径、阈值范围、生效时间、适用对象和版本状态一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|预警告警|3|0|用于组织生态流量监管业务中预警告警相关数据的质量检查任务，重点检查告警对象、触发条件、告警等级、发生时间、推送状态、处置状态和解除条件完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|统计分析|4|0|用于组织生态流量监管业务中统计分析相关数据的质量检查任务，重点检查指标定义、统计维度、统计周期、计算公式、汇总范围、结果精度和跨报表口径一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|评价考核|5|0|用于组织生态流量监管业务中评价考核相关数据的质量检查任务，重点检查评价对象、指标体系、考核周期、指标权重、计算过程、评价结果和结果发布一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|异常识别|6|0|用于组织生态流量监管业务中异常识别相关数据的质量检查任务，重点检查异常类型、识别规则、影响对象、发生时间、异常状态、处置结果和闭环记录完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|空间地理|7|0|用于组织生态流量监管业务中空间地理相关数据的质量检查任务，重点检查空间坐标、坐标系、几何类型、拓扑关系、行政区归属、空间范围和图属一致性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|档案资料|8|0|用于组织生态流量监管业务中档案资料相关数据的质量检查任务，重点检查档案编号、资料类型、形成时间、版本状态、关联对象、文件完整性和存储可访问性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            生态流量监管|应用服务|9|0|用于组织生态流量监管业务中应用服务相关数据的质量检查任务，重点检查服务编码、接口参数、返回结构、数据口径、更新时间、调用状态和服务结果完整性，支撑质量问题发现、责任定位、整改跟踪和结果评价。
            """.lines().map(line -> {
                String[] fields = line.split("\\|", -1);
                return new ExpectedCategory(fields[0], fields[1], Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), fields[4]);
            }).toList();

    private Connection connection;

    @BeforeEach
    void initializePreMigrationSchema() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // 仅操作此测试自建容器，夹具保留迁移前非空约束和五表的领域数据。
        execute("""
                DROP SCHEMA IF EXISTS "datahub-center" CASCADE;
                CREATE SCHEMA "datahub-center";
                SET search_path TO "datahub-center";
                CREATE SEQUENCE dh_foundation_category_data_quality_seq START 5000;
                CREATE TABLE dh_governance_category_data_quality (
                    id bigint PRIMARY KEY, parent_id bigint NOT NULL DEFAULT 0,
                    category_name varchar(128) NOT NULL, category_code varchar(128) NOT NULL,
                    sort integer NOT NULL DEFAULT 0, status smallint NOT NULL DEFAULT 0, remark text,
                    creator varchar(64), updater varchar(64),
                    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted smallint NOT NULL DEFAULT 0
                );
                INSERT INTO dh_governance_category_data_quality
                    (id,parent_id,category_name,category_code,status,deleted) VALUES
                    (10,0,'旧完整性','0,10',0,0), (20,10,'旧删除子类','0,10,20',0,1),
                    (30,0,'旧准确性','0,30',0,0), (40,0,'旧一致性','0,40',0,0),
                    (9000,0,'旧停用类','0,9000',1,0), (50,0,'旧资产编码冲突','ASSET',0,0),
                    (60,0,'旧加工编码冲突','ETL',0,0), (70,0,'旧空白编码',' ',0,0);
                CREATE TABLE dh_governance_audit_rule (
                    id bigint PRIMARY KEY, name varchar(100) NOT NULL, dimension_id bigint NOT NULL,
                    rule_level smallint NOT NULL DEFAULT 1, code varchar(32), strategy_key varchar(64),
                    description varchar(500), use_case varchar(500), example varchar(2000),
                    status smallint NOT NULL DEFAULT 0
                );
                COMMENT ON COLUMN dh_governance_audit_rule.dimension_id IS '原质量维度，必填';
                CREATE TABLE dh_governance_quality_rule (
                    id bigint PRIMARY KEY, category_id bigint, rule_name varchar(128) NOT NULL,
                    rule_code varchar(128), quality_dim varchar(32) NOT NULL, check_level varchar(32),
                    rule_type varchar(32), strategy_key varchar(128), param_schema jsonb, rule_content text,
                    severity varchar(16), apply_layer varchar(64), collect_issue_detail smallint,
                    use_case varchar(512), example varchar(1024), status smallint, builtin smallint,
                    description varchar(1024), remark varchar(512), standard_element_id bigint,
                    element_version integer, constraint_hash varchar(64)
                );
                CREATE TABLE dh_governance_quality_audit_rule (
                    id bigint PRIMARY KEY, category_id bigint, rule_name varchar(128) NOT NULL,
                    quality_dim varchar(32) NOT NULL, check_level varchar(32), rule_type varchar(32),
                    strategy_key varchar(128), param_schema jsonb, use_case varchar(512), example varchar(1024),
                    status smallint, description varchar(1024), remark varchar(512)
                );
                CREATE TABLE dh_governance_quality_task (
                    id bigint PRIMARY KEY, task_name varchar(128) NOT NULL, task_code varchar(128),
                    task_category varchar(128), execute_strategy varchar(16), schedule_type varchar(16),
                    cron_expression varchar(128), task_status smallint, priority varchar(16), retry_count integer,
                    rule_set_id bigint, owner_user_id bigint, description varchar(1024), remark varchar(512)
                );
                """);
        for (String table : BUSINESS_TABLES) {
            execute("ALTER TABLE " + table + " ADD COLUMN creator varchar(64) NOT NULL DEFAULT 'original-author',"
                    + " ADD COLUMN updater varchar(64) NOT NULL DEFAULT 'original-editor',"
                    + " ADD COLUMN create_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-01-01 00:00:00',"
                    + " ADD COLUMN update_time timestamp NOT NULL DEFAULT TIMESTAMP '2025-02-01 00:00:00',"
                    + " ADD COLUMN deleted smallint NOT NULL DEFAULT 0");
        }
        execute("""
                INSERT INTO dh_governance_audit_rule
                    (id,name,dimension_id,rule_level,code,strategy_key,description,use_case,example,status,deleted)
                SELECT n, '历史规则' || n,
                    CASE WHEN n=27 THEN 7777 ELSE (ARRAY[10,20,30,40,9000])[1+(n-1)%5] END,
                    1+n%2, 'RULE_' || n, 'LENGTH_VALIDATION', '保留说明' || n,
                    '保留场景' || n, '保留示例' || n, n%2, CASE WHEN n=28 THEN 1 ELSE 0 END
                FROM generate_series(1,28) n;
                INSERT INTO dh_governance_quality_rule
                    (id,category_id,rule_name,rule_code,quality_dim,check_level,rule_type,strategy_key,
                     param_schema,rule_content,severity,apply_layer,collect_issue_detail,use_case,example,
                     status,builtin,description,remark,standard_element_id,element_version,constraint_hash,deleted)
                SELECT 200+n, (ARRAY[10,20,NULL,7777])[n], '质量规则' || n, 'QUALITY_' || n,
                    'COMPLETENESS','FIELD','NOT_NULL','NOT_NULL_VALIDATION', '{"required":true}'::jsonb,
                    'value IS NOT NULL','HIGH','DWD',1,'保留场景','保留示例',0,n%2,'保留说明','保留备注',
                    8000+n,3,repeat('a',64),CASE WHEN n=2 THEN 1 ELSE 0 END FROM generate_series(1,4) n;
                INSERT INTO dh_governance_quality_audit_rule
                    (id,category_id,rule_name,quality_dim,check_level,rule_type,strategy_key,param_schema,
                     use_case,example,status,description,remark,deleted)
                SELECT 300+n, (ARRAY[10,20,NULL,7777])[n], '旧稽查规则' || n,
                    'ACCURACY','FIELD','RANGE','RANGE_VALIDATION','{"minimum":0}'::jsonb,
                    '保留场景','保留示例',0,'保留说明','保留备注',CASE WHEN n=2 THEN 1 ELSE 0 END
                FROM generate_series(1,4) n;
                INSERT INTO dh_governance_quality_task
                    (id,task_name,task_code,task_category,execute_strategy,schedule_type,cron_expression,
                     task_status,priority,retry_count,rule_set_id,owner_user_id,description,remark,deleted)
                SELECT 400+n, '质量任务' || n, 'TASK_' || n, category, 'SERIAL','MANUAL',NULL,
                    1,'HIGH',2,500+n,600+n,'保留说明','保留备注',CASE WHEN n=2 THEN 1 ELSE 0 END
                FROM (VALUES (1,'0,10'), (2,'0,10,20'), (3,'ASSET'), (4,'ETL'), (5,'SPECIAL'),
                    (6,'0,10,999'), (7,' 0,10 '), (8,NULL), (9,''), (10,'0,9000'), (11,'0,1'), (12,' ')) v(n,category);
                CREATE TABLE dh_governance_quality_task_instance (
                    id bigint PRIMARY KEY, task_id bigint, execution_user_id bigint, definition_snapshot text
                );
                INSERT INTO dh_governance_quality_task_instance VALUES
                    (701,401,601,'{ "taskCategory":"0,10", "dimensionId":10, "strategyKey":"LENGTH_VALIDATION" }');
                CREATE TABLE dh_governance_standard_element_version (
                    id bigint PRIMARY KEY, element_id bigint, snapshot text, rule_bindings_snapshot jsonb
                );
                INSERT INTO dh_governance_standard_element_version VALUES
                    (801,8001,'{ "categoryId":10, "version":3 }','[{"ruleId":201,"categoryId":10}]');
                CREATE TABLE dh_governance_quality_task_evaluation (
                    id bigint PRIMARY KEY, task_id bigint, rule_code varchar(64), strategy_key varchar(64),
                    quality_dim varchar(32), config jsonb, creator varchar(64)
                );
                INSERT INTO dh_governance_quality_task_evaluation VALUES
                    (901,401,'RULE_1','LENGTH_VALIDATION','COMPLETENESS','{"minLength":1}', 'original-owner');
                """);
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null) connection.close();
    }

    @Test
    void installsAllNinetyFiveSourceCategoriesWithExactDescriptionsAndLocalPaths() throws Exception {
        migrate();

        assertExactHierarchy();
        assertEquals(9001, scalar("SELECT min(id) FROM " + CATEGORY));
        assertEquals(9095, scalar("SELECT max(id) FROM " + CATEGORY));
        assertEquals(0, scalar("SELECT count(*) FROM " + CATEGORY + " WHERE id IN (10,20,30,40,50,60,70,9000)"));
        assertEquals(new SequenceState(9095, true), sequenceState());
        assertEquals(9096, scalar("SELECT nextval('" + SEQUENCE + "')"));
        assertEquals("YES", dimensionNullable());
        assertTrue(dimensionComment().contains("未分类"));
    }

    @Test
    void detachesAuditDimensionsAndPreservesRulesOtherCatalogsOwnershipAndSnapshots() throws Exception {
        Map<String, List<String>> stable = businessRowsWithoutCategoryEdit();
        Map<String, List<String>> snapshots = rowsForTables(SNAPSHOT_TABLES);
        Map<String, List<String>> otherCatalogs = rowsForTables(List.of(QUALITY, LEGACY));

        migrate();

        assertEquals(stable, businessRowsWithoutCategoryEdit(), "策略、编码、评分维度、参数和所有权保持不变");
        assertEquals(snapshots, rowsForTables(SNAPSHOT_TABLES), "历史定义及标准规则快照保留旧编号与原文");
        assertEquals(28, scalar("SELECT count(*) FROM " + AUDIT));
        assertEquals(0, scalar("SELECT count(*) FROM " + AUDIT + " WHERE dimension_id IS NOT NULL"));
        assertEquals(28, updatedCount(AUDIT));
        assertEquals(otherCatalogs, rowsForTables(List.of(QUALITY, LEGACY)),
                "其他树即使编号与旧质量类目相同，也不能据此解除引用或改动审计字段");
    }

    @Test
    void taskCategoryClearingUsesExactOldCodesAndProtectsSpecialValuesEvenWhenCodesCollide() throws Exception {
        List<String> unaffected = queryStrings("SELECT to_jsonb(record_row)::text FROM " + TASK
                + " record_row WHERE id NOT IN (401,402,410) ORDER BY id");

        migrate();

        assertEquals(12, scalar("SELECT count(*) FROM " + TASK));
        assertEquals(List.of("401", "402", "410"), queryStrings("SELECT id::text FROM " + TASK
                + " WHERE updater='migration:0201' AND task_category IS NULL ORDER BY id"));
        assertEquals(3, updatedCount(TASK));
        assertEquals(unaffected, queryStrings("SELECT to_jsonb(record_row)::text FROM " + TASK
                + " record_row WHERE id NOT IN (401,402,410) ORDER BY id"));
    }

    @Test
    void usesSequenceHighWaterMarkEvenWhenItHasNotBeenCalled() throws Exception {
        execute("SELECT setval('" + SEQUENCE + "',20000,false)");

        migrate();

        assertExactHierarchy();
        assertEquals(20001, scalar("SELECT min(id) FROM " + CATEGORY));
        assertEquals(20095, scalar("SELECT max(id) FROM " + CATEGORY));
        assertEquals(new SequenceState(20095, true), sequenceState());
        assertEquals(20096, scalar("SELECT nextval('" + SEQUENCE + "')"));
    }

    @Test
    void replayRejectsBeforeTouchingNewBindingsOrSequence() throws Exception {
        migrate();
        long categoryId = scalar("SELECT id FROM " + CATEGORY + " WHERE parent_id=0 AND sort=0");
        execute("UPDATE " + AUDIT + " SET dimension_id=" + categoryId + " WHERE id=1");
        for (String table : List.of(QUALITY, LEGACY)) execute("UPDATE " + table + " SET category_id=" + categoryId);
        execute("UPDATE " + TASK + " SET task_category='0," + categoryId + "' WHERE id=401");
        Map<String, List<String>> before = allRows();
        SequenceState beforeSequence = sequenceState();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("P0001", failure.getSQLState());
        assertTrue(failure.getMessage().contains("0201"));
        execute("ROLLBACK");

        assertEquals(before, allRows());
        assertEquals(beforeSequence, sequenceState());
        assertEquals("YES", dimensionNullable());
    }

    @Test
    void lateInsertFailureRollsBackDataNullabilityCommentAndSequence() throws Exception {
        execute("ALTER TABLE " + CATEGORY + " ADD CONSTRAINT fixture_reject_late_seed"
                + " CHECK (category_name <> '生态流量监管')");
        Map<String, List<String>> before = allRows();
        SequenceState beforeSequence = sequenceState();
        String beforeComment = dimensionComment();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("23514", failure.getSQLState());
        assertTrue(failure.getMessage().contains("fixture_reject_late_seed"));
        execute("ROLLBACK");

        assertEquals(before, allRows());
        assertEquals(beforeSequence, sequenceState());
        assertEquals("NO", dimensionNullable());
        assertEquals(beforeComment, dimensionComment());
        execute("ALTER TABLE " + CATEGORY + " DROP CONSTRAINT fixture_reject_late_seed");
        migrate();
        assertExactHierarchy();
    }

    @Test
    void invalidSequenceIncrementRejectsBeforeSchemaOrDataChanges() throws Exception {
        execute("ALTER SEQUENCE " + SEQUENCE + " INCREMENT BY 2");
        Map<String, List<String>> before = allRows();
        SequenceState beforeSequence = sequenceState();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("P0001", failure.getSQLState());
        execute("ROLLBACK");

        assertEquals(before, allRows());
        assertEquals(beforeSequence, sequenceState());
        assertEquals("NO", dimensionNullable());
        assertEquals(2, scalar("SELECT seqincrement FROM pg_sequence WHERE seqrelid='" + SEQUENCE + "'::regclass"));
    }

    @Test
    void insufficientIdentifierSpaceRejectsWithoutDetachingRules() throws Exception {
        execute("SELECT setval('" + SEQUENCE + "',9223372036854775713,true)");
        Map<String, List<String>> before = allRows();
        SequenceState beforeSequence = sequenceState();

        SQLException failure = assertThrows(SQLException.class, this::migrate);
        assertEquals("P0001", failure.getSQLState());
        execute("ROLLBACK");

        assertEquals(before, allRows());
        assertEquals(beforeSequence, sequenceState());
        assertEquals("NO", dimensionNullable());
    }

    @Test
    void historicalMigrationNullDimensionsRemainReadableAndRequestValidationStaysRequired() throws Exception {
        migrate();
        AuditRuleMapper mapper = actualAuditMapper();
        var pageRequest = new AuditRulePageReqVO();
        pageRequest.setPageSize(10);
        var firstPage = mapper.selectPage(pageRequest, pageRequest.getDimensionId() == null ? null : List.of(pageRequest.getDimensionId()));
        assertEquals(27L, firstPage.getTotal());
        assertEquals(10, firstPage.getList().size());
        assertTrue(firstPage.getList().stream().allMatch(rule -> rule.getDimensionId() == null));
        assertEquals(List.of(27L,26L,25L,24L,23L,22L,21L,20L,19L,18L),
                firstPage.getList().stream().map(AuditRuleDO::getId).toList());
        pageRequest.setPageNo(3);
        assertEquals(7, mapper.selectPage(pageRequest, pageRequest.getDimensionId() == null ? null : List.of(pageRequest.getDimensionId())).getList().size());
        assertEquals(27L, mapper.selectPage(pageRequest, pageRequest.getDimensionId() == null ? null : List.of(pageRequest.getDimensionId())).getTotal());
        assertNull(mapper.selectById(1L).getDimensionId());
        assertEquals("RULE_1", mapper.selectById(1L).getCode());
        assertNull(mapper.selectById(28L), "逻辑删除规则不出现在详情中");
        pageRequest.setDimensionId(scalar("SELECT id FROM " + CATEGORY + " WHERE parent_id=0 AND sort=0"));
        assertEquals(0L, mapper.selectPage(pageRequest, pageRequest.getDimensionId() == null ? null : List.of(pageRequest.getDimensionId())).getTotal());

        var save = new AuditRuleSaveReqVO();
        save.setName("新规则");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (boolean update : List.of(false, true)) {
                save.setId(update ? 1L : null);
                var violations = factory.getValidator().validate(save);
                assertEquals(1, violations.size());
                var violation = violations.iterator().next();
                assertEquals("dimensionId", violation.getPropertyPath().toString());
                assertEquals(NotNull.class, violation.getConstraintDescriptor().getAnnotation().annotationType());
            }
            save.setDimensionId(pageRequest.getDimensionId());
            assertTrue(factory.getValidator().validate(save).isEmpty());
        }
    }

    private AuditRuleMapper actualAuditMapper() {
        String url = POSTGRES.getJdbcUrl();
        var source = new UnpooledDataSource("org.postgresql.Driver", url
                + (url.contains("?") ? "&" : "?") + "currentSchema=datahub-center",
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setDefaultStatementTimeout(10);
        config.setEnvironment(new Environment("quality-category-migration", new JdbcTransactionFactory(), source));
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(pagination);
        config.addMapper(AuditRuleMapper.class);
        return new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(AuditRuleMapper.class);
    }

    private void assertExactHierarchy() throws SQLException {
        assertEquals(95, EXPECTED.size());
        Map<Long, CategoryRow> actual = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT id,parent_id,category_name,category_code,sort,status,remark FROM " + CATEGORY + " ORDER BY id")) {
            while (rows.next()) {
                var row = new CategoryRow(rows.getLong(1), rows.getLong(2), rows.getString(3),
                        rows.getString(4), rows.getInt(5), rows.getInt(6), rows.getString(7));
                actual.put(row.id(), row);
            }
        }
        assertEquals(95, actual.size());
        assertEquals(8, actual.values().stream().filter(row -> row.parentId() == 0).count());
        assertEquals(87, actual.values().stream().filter(row -> row.parentId() != 0).count());
        Map<String, ExpectedCategory> byPath = new LinkedHashMap<>();
        for (CategoryRow row : actual.values()) {
            CategoryRow parent = row.parentId() == 0 ? null : actual.get(row.parentId());
            if (row.parentId() != 0) {
                assertNotNull(parent, row.name());
                assertEquals(0L, parent.parentId(), "只允许两层类目");
            }
            String parentName = parent == null ? "" : parent.name();
            assertEquals(parent == null ? "0," + row.id() : "0," + parent.id() + "," + row.id(), row.code());
            assertNull(byPath.put(parentName + "/" + row.name(), new ExpectedCategory(parentName,
                    row.name(), row.sort(), row.status(), row.remark())), "同一父类下不应重复名称");
        }
        Map<String, ExpectedCategory> expected = new LinkedHashMap<>();
        for (ExpectedCategory row : EXPECTED) assertNull(expected.put(row.parentName() + "/" + row.name(), row));
        assertEquals(expected, byPath, "逐项核对完整名称、父子关系、说明、排序及状态");
        assertEquals(95, scalar("SELECT count(*) FROM " + CATEGORY
                + " WHERE deleted=0 AND creator='migration:0201' AND updater='migration:0201'"));
    }

    private Map<String, List<String>> businessRowsWithoutCategoryEdit() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            result.put(table, queryStrings("SELECT (to_jsonb(record_row)"
                    + " - ARRAY['dimension_id','category_id','task_category','updater','update_time'])::text"
                    + " FROM " + table + " record_row ORDER BY id"));
        }
        return result;
    }

    private long updatedCount(String table) throws SQLException {
        return scalar("SELECT count(*) FROM " + table
                + " WHERE updater='migration:0201' AND update_time > TIMESTAMP '2025-02-01 00:00:00'");
    }

    private String dimensionNullable() throws SQLException {
        return queryStrings("SELECT is_nullable FROM information_schema.columns WHERE table_schema='datahub-center'"
                + " AND table_name='dh_governance_audit_rule' AND column_name='dimension_id'").getFirst();
    }

    private String dimensionComment() throws SQLException {
        return queryStrings("SELECT col_description(attrelid,attnum) FROM pg_attribute WHERE attrelid='"
                + AUDIT + "'::regclass AND attname='dimension_id'").getFirst();
    }

    private Map<String, List<String>> allRows() throws SQLException {
        List<String> tables = new ArrayList<>(BUSINESS_TABLES);
        tables.add(CATEGORY);
        tables.addAll(SNAPSHOT_TABLES);
        return rowsForTables(tables);
    }

    private Map<String, List<String>> rowsForTables(List<String> tables) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : tables) result.put(table,
                queryStrings("SELECT to_jsonb(record_row)::text FROM " + table + " record_row ORDER BY id"));
        return result;
    }

    private SequenceState sequenceState() throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT last_value,is_called FROM " + SEQUENCE)) {
            assertTrue(row.next());
            return new SequenceState(row.getLong(1), row.getBoolean(2));
        }
    }

    private List<String> queryStrings(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void migrate() throws Exception {
        String relativePath = "sql/postgresql/migrations/" + MIGRATION;
        for (Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve(relativePath))) {
                execute(Files.readString(root.resolve(relativePath)));
                return;
            }
        }
        throw new IllegalStateException("缺少编号迁移：" + relativePath);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute(sql);
        }
    }

    private record ExpectedCategory(String parentName, String name, int sort, int status, String description) { }
    private record CategoryRow(long id, long parentId, String name, String code, int sort, int status, String remark) { }
    private record SequenceState(long lastValue, boolean called) { }
}

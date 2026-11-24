package cn.iocoder.yudao.module.dataforge.framework.operatelog.config;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.operatelog.core.function.CodeLabelParseFunction;
import cn.iocoder.yudao.framework.dict.core.DictFrameworkUtils;
import cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileRunDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileUploadDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseAlarmRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableRuleDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fullsync.FullSyncWizardDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.JobCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.BatchFileRunMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.BatchFileTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.BatchFileUploadMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseAlarmRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableColumnMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableRuleMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fullsync.FullSyncWizardMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.JobCategoryMapper;
import cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.mzt.logapi.service.IParseFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.function.Function;

import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.loader;
import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.via;

/**
 * 数据开发模块的操作日志「编号 → 名称」翻译函数，模板写法如 {@code {getFillPlanName{#planId}}}。
 * <p>
 * 执行实例、批次、提交这类没有独立名称的记录，显示其所属任务 / 计划 / 流程的名称。
 */
@Configuration(proxyBeanMethods = false)
public class DataforgeOperateLogConfiguration {

    // ========== 用户 ==========

    @Bean
    public EntityNameParseFunction dataforgeUserNameParseFunction(AdminUserApi adminUserApi) {
        return new EntityNameParseFunction("getDataforgeUserName",
                loader(ids -> adminUserApi.getUserList(ids).getCheckedData(),
                        AdminUserRespDTO::getId, AdminUserRespDTO::getNickname));
    }

    /** 治理模块的质量工单 */
    @Bean
    public EntityNameParseFunction dataforgeQualityWorkOrderNameParseFunction(GovernanceEtlApi governanceEtlApi) {
        return new EntityNameParseFunction("getQualityWorkOrderName",
                ids -> governanceEtlApi.getQualityWorkOrderNames(ids).getCheckedData());
    }

    // ========== 数据库监控 / 数据同步 / 整库同步 ==========

    @Bean
    public EntityNameParseFunction databaseAlarmRecordNameParseFunction(DatabaseAlarmRecordMapper mapper) {
        return new EntityNameParseFunction("getDatabaseAlarmRecordName",
                loader(mapper::selectBatchIds, DatabaseAlarmRecordDO::getId, DatabaseAlarmRecordDO::getRuleName));
    }

    /** 数据同步执行实例：显示同步任务名称 */
    @Bean
    public EntityNameParseFunction dataAccessRunNameParseFunction(DataAccessTaskMapper mapper) {
        return new EntityNameParseFunction("getDataAccessRunName",
                loader(mapper::selectBatchIds, DataAccessTaskDO::getId, DataAccessTaskDO::getTaskName));
    }

    /** 整库同步向导：显示向导里填写的任务名称 */
    @Bean
    public EntityNameParseFunction fullSyncWizardNameParseFunction(FullSyncWizardMapper mapper) {
        return new EntityNameParseFunction("getFullSyncWizardName",
                loader(mapper::selectBatchIds, FullSyncWizardDO::getId, row -> StrUtil.isBlank(row.getConfigJson())
                        || !JSONUtil.isTypeJSONObject(row.getConfigJson()) ? null
                        : JSONUtil.parseObj(row.getConfigJson()).getStr("taskName")));
    }

    // ========== 数据填报 ==========

    @Bean
    public EntityNameParseFunction fillPlanNameParseFunction(FillPlanMapper mapper) {
        return new EntityNameParseFunction("getFillPlanName",
                loader(mapper::selectBatchIds, FillPlanDO::getId, FillPlanDO::getName));
    }

    @Bean
    public EntityNameParseFunction fillPlanCategoryNameParseFunction(FillPlanCategoryMapper mapper) {
        return new EntityNameParseFunction("getFillPlanCategoryName",
                loader(mapper::selectBatchIds, FillPlanCategoryDO::getId, FillPlanCategoryDO::getName));
    }

    /** 模板版本：显示「模板名称 V版本号」 */
    @Bean
    public EntityNameParseFunction fillTableVersionNameParseFunction(FillTableVersionMapper mapper) {
        return new EntityNameParseFunction("getFillTableVersionName",
                loader(mapper::selectBatchIds, FillTableVersionDO::getId,
                        row -> row.getVersionNo() == null ? row.getName() : row.getName() + " V" + row.getVersionNo()));
    }

    @Bean
    public EntityNameParseFunction fillTableNameParseFunction(FillTableMapper mapper) {
        return new EntityNameParseFunction("getFillTableName",
                loader(mapper::selectBatchIds, FillTableDO::getId,
                        row -> StrUtil.blankToDefault(row.getTableCnName(), row.getTableEngName())));
    }

    @Bean
    public EntityNameParseFunction fillTableColumnNameParseFunction(FillTableColumnMapper mapper) {
        return new EntityNameParseFunction("getFillTableColumnName",
                loader(mapper::selectBatchIds, FillTableColumnDO::getId,
                        row -> StrUtil.blankToDefault(row.getCnName(), row.getName())));
    }

    @Bean
    public EntityNameParseFunction fillTableRuleNameParseFunction(FillTableRuleMapper mapper) {
        return new EntityNameParseFunction("getFillTableRuleName",
                loader(mapper::selectBatchIds, FillTableRuleDO::getId, FillTableRuleDO::getName));
    }

    /** 填报工作项：显示「采集任务名称（组织名称）」 */
    @Bean
    public EntityNameParseFunction fillWorkItemNameParseFunction(FillWorkItemMapper workItemMapper,
                                                                 FillTaskMapper taskMapper) {
        return new EntityNameParseFunction("getFillWorkItemName", workItemNames(workItemMapper, taskMapper));
    }

    /** 填报提交：显示所属工作项 */
    @Bean
    public EntityNameParseFunction fillSubmissionNameParseFunction(FillSubmissionMapper mapper,
                                                                   FillWorkItemMapper workItemMapper,
                                                                   FillTaskMapper taskMapper) {
        return new EntityNameParseFunction("getFillSubmissionName", via(mapper::selectBatchIds,
                FillSubmissionDO::getId, FillSubmissionDO::getWorkItemId, workItemNames(workItemMapper, taskMapper)));
    }

    /** 数据更正：显示所属工作项 */
    @Bean
    public EntityNameParseFunction fillCorrectionNameParseFunction(FillCorrectionMapper mapper,
                                                                   FillWorkItemMapper workItemMapper,
                                                                   FillTaskMapper taskMapper) {
        return new EntityNameParseFunction("getFillCorrectionName", via(mapper::selectBatchIds,
                FillCorrectionDO::getId, FillCorrectionDO::getWorkItemId, workItemNames(workItemMapper, taskMapper)));
    }

    /** 合并批次：显示所属工作项 */
    @Bean
    public EntityNameParseFunction fillMergeBatchNameParseFunction(FillMergeBatchMapper mapper,
                                                                   FillWorkItemMapper workItemMapper,
                                                                   FillTaskMapper taskMapper) {
        return new EntityNameParseFunction("getFillMergeBatchName", via(mapper::selectBatchIds,
                FillMergeBatchDO::getId, FillMergeBatchDO::getWorkItemId, workItemNames(workItemMapper, taskMapper)));
    }

    private static Function<Collection<Long>, Map<Long, String>> workItemNames(FillWorkItemMapper workItemMapper,
                                                                              FillTaskMapper taskMapper) {
        return ids -> {
            List<FillWorkItemDO> items = workItemMapper.selectBatchIds(ids);
            Set<Long> taskIds = new HashSet<>();
            items.forEach(item -> {
                if (item.getTaskId() != null) {
                    taskIds.add(item.getTaskId());
                }
            });
            Map<Long, String> taskNames = new HashMap<>();
            if (!taskIds.isEmpty()) {
                taskMapper.selectBatchIds(taskIds).forEach(task -> taskNames.put(task.getId(), task.getName()));
            }
            Map<Long, String> result = new HashMap<>();
            for (FillWorkItemDO item : items) {
                String task = taskNames.get(item.getTaskId());
                if (task == null) {
                    result.put(item.getId(), item.getOrgName());
                } else {
                    result.put(item.getId(), StrUtil.isBlank(item.getOrgName()) ? task : task + "（" + item.getOrgName() + "）");
                }
            }
            return result;
        };
    }

    // ========== 集成 / 作业 / 项目类目 ==========

    @Bean
    public EntityNameParseFunction integrationCategoryNameParseFunction(IntegrationCategoryMapper mapper) {
        return new EntityNameParseFunction("getIntegrationCategoryName",
                loader(mapper::selectBatchIds, IntegrationCategoryDO::getId, IntegrationCategoryDO::getName));
    }

    @Bean
    public EntityNameParseFunction jobCategoryNameParseFunction(JobCategoryMapper mapper) {
        return new EntityNameParseFunction("getJobCategoryName",
                loader(mapper::selectBatchIds, JobCategoryDO::getId, JobCategoryDO::getName));
    }

    /**
     * 项目类目统一管理开发目录与集成类目，模板写法：{@code {getProjectCategoryName{#request.kind + ':' + #request.parentId}}}。
     */
    @Bean
    public IParseFunction projectCategoryNameParseFunction(DevDirMapper devDirMapper,
                                                           IntegrationCategoryMapper integrationMapper) {
        EntityNameParseFunction devDir = new EntityNameParseFunction("getDevDirName",
                loader(devDirMapper::selectBatchIds, DevDirDO::getId, DevDirDO::getName));
        EntityNameParseFunction integration = new EntityNameParseFunction("getIntegrationCategoryName",
                loader(integrationMapper::selectBatchIds, IntegrationCategoryDO::getId, IntegrationCategoryDO::getName));
        return new IParseFunction() {
            @Override
            public boolean executeBefore() {
                return true;
            }

            @Override
            public String functionName() {
                return "getProjectCategoryName";
            }

            @Override
            public String apply(Object value) {
                String text = String.valueOf(value);
                String id = StrUtil.subAfter(text, ':', true);
                if (StrUtil.isBlank(id) || "null".equals(id)) {
                    return "无";
                }
                return text.startsWith("integration:") ? integration.apply(id) : devDir.apply(id);
            }
        };
    }

    // ========== 数据开发任务 ==========

    @Bean
    public EntityNameParseFunction devTaskNameParseFunction(DevTaskMapper mapper) {
        return new EntityNameParseFunction("getDevTaskName",
                loader(mapper::selectBatchIds, DevTaskDO::getId, DevTaskDO::getName));
    }

    @Bean
    public EntityNameParseFunction devDirNameParseFunction(DevDirMapper mapper) {
        return new EntityNameParseFunction("getDevDirName",
                loader(mapper::selectBatchIds, DevDirDO::getId, DevDirDO::getName));
    }

    /** 开发任务执行实例：显示任务名称 */
    @Bean
    public EntityNameParseFunction devRecordNameParseFunction(DevRecordMapper mapper) {
        return new EntityNameParseFunction("getDevRecordName",
                loader(mapper::selectBatchIds, DevRecordDO::getId, DevRecordDO::getTaskName));
    }

    /** 补数据批次：显示任务名称 */
    @Bean
    public EntityNameParseFunction devBackfillNameParseFunction(DevBackfillMapper mapper) {
        return new EntityNameParseFunction("getDevBackfillName",
                loader(mapper::selectBatchIds, DevBackfillDO::getId, DevBackfillDO::getTaskName));
    }

    @Bean
    public EntityNameParseFunction devTaskVersionNameParseFunction(DevTaskVersionMapper mapper) {
        return new EntityNameParseFunction("getDevTaskVersionName",
                loader(mapper::selectBatchIds, DevTaskVersionDO::getId,
                        row -> row.getVersion() == null ? null : "V" + row.getVersion()));
    }

    // ========== ETL ==========

    @Bean
    public EntityNameParseFunction etlPipelineNameParseFunction(EtlPipelineMapper mapper) {
        return new EntityNameParseFunction("getEtlPipelineName",
                loader(mapper::selectBatchIds, EtlPipelineDO::getId, EtlPipelineDO::getName));
    }

    @Bean
    public EntityNameParseFunction etlEnvironmentNameParseFunction(EtlEnvironmentMapper mapper) {
        return new EntityNameParseFunction("getEtlEnvironmentName",
                loader(mapper::selectBatchIds, EtlEnvironmentDO::getId, EtlEnvironmentDO::getName));
    }

    @Bean
    public EntityNameParseFunction etlBindingNameParseFunction(EtlEnvironmentBindingMapper mapper) {
        return new EntityNameParseFunction("getEtlBindingName",
                loader(mapper::selectBatchIds, EtlEnvironmentBindingDO::getId, EtlEnvironmentBindingDO::getLogicalKey));
    }

    /** ETL 执行实例：显示执行记录名称 */
    @Bean
    public EntityNameParseFunction etlRecordNameParseFunction(EtlRecordMapper mapper) {
        return new EntityNameParseFunction("getEtlRecordName",
                loader(mapper::selectBatchIds, EtlRecordDO::getId, EtlRecordDO::getName));
    }

    /** ETL 目标批次：显示所属执行实例名称 */
    @Bean
    public EntityNameParseFunction etlTargetBatchNameParseFunction(EtlTargetBatchMapper mapper,
                                                                   EtlRecordMapper recordMapper) {
        return new EntityNameParseFunction("getEtlTargetBatchName", via(mapper::selectBatchIds,
                EtlTargetBatchDO::getId, EtlTargetBatchDO::getRecordId,
                loader(recordMapper::selectBatchIds, EtlRecordDO::getId, EtlRecordDO::getName)));
    }

    /** ETL 发布版本：显示「流程名称 修订X」 */
    @Bean
    public EntityNameParseFunction etlVersionNameParseFunction(EtlVersionMapper mapper, EtlPipelineMapper pipelineMapper) {
        return new EntityNameParseFunction("getEtlVersionName", ids -> {
            List<EtlVersionDO> versions = mapper.selectBatchIds(ids);
            Set<Long> pipelineIds = new HashSet<>();
            versions.forEach(version -> {
                if (version.getPipelineId() != null) {
                    pipelineIds.add(version.getPipelineId());
                }
            });
            Map<Long, String> pipelineNames = new HashMap<>();
            if (!pipelineIds.isEmpty()) {
                pipelineMapper.selectBatchIds(pipelineIds).forEach(p -> pipelineNames.put(p.getId(), p.getName()));
            }
            Map<Long, String> result = new HashMap<>();
            for (EtlVersionDO version : versions) {
                String pipeline = Objects.toString(pipelineNames.get(version.getPipelineId()), "流程");
                result.put(version.getId(), version.getRevision() == null ? pipeline : pipeline + " 第" + version.getRevision() + "版");
            }
            return result;
        });
    }

    @Bean
    public EntityNameParseFunction etlFileOutputNameParseFunction(EtlFileOutputMapper mapper) {
        return new EntityNameParseFunction("getEtlFileOutputName",
                loader(mapper::selectBatchIds, EtlFileOutputDO::getId, EtlFileOutputDO::getFileName));
    }

    // ========== 批量文件入库 ==========

    @Bean
    public EntityNameParseFunction batchFileUploadNameParseFunction(BatchFileUploadMapper mapper) {
        return new EntityNameParseFunction("getBatchFileUploadName",
                loader(mapper::selectBatchIds, BatchFileUploadDO::getId, BatchFileUploadDO::getName));
    }

    /** 批量入库执行实例：显示入库任务名称 */
    @Bean
    public EntityNameParseFunction batchFileRunNameParseFunction(BatchFileRunMapper mapper, BatchFileTaskMapper taskMapper) {
        return new EntityNameParseFunction("getBatchFileRunName", via(mapper::selectBatchIds,
                BatchFileRunDO::getId, BatchFileRunDO::getTaskId,
                loader(taskMapper::selectBatchIds, BatchFileTaskDO::getId, BatchFileTaskDO::getTaskName)));
    }

    // ========== 业务代码翻译（类型、模式、级别等），未收录的值原样输出 ==========

    @Bean
    public CodeLabelParseFunction dataforgeAlarmMetricLabelParseFunction() {
        return new CodeLabelParseFunction("getAlarmMetricLabel", Map.ofEntries(Map.entry("CONNECTION_STATUS", "连接状态"), Map.entry("RESPONSE_TIME", "响应时间")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeAlarmOperatorLabelParseFunction() {
        return new CodeLabelParseFunction("getAlarmOperatorLabel", Map.ofEntries(Map.entry("EQ", "等于"), Map.entry("GT", "大于"), Map.entry("GTE", "大于等于"), Map.entry("LT", "小于"), Map.entry("LTE", "小于等于")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeAlarmLevelLabelParseFunction() {
        return new CodeLabelParseFunction("getAlarmLevelLabel", Map.ofEntries(Map.entry("GENERAL", "一般"), Map.entry("SERIOUS", "严重"), Map.entry("URGENT", "紧急")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeAccessEngineTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getAccessEngineTypeLabel", Map.ofEntries(Map.entry("1", "离线同步"), Map.entry("2", "实时同步"), Map.entry("3", "API 接入")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeAccessWriteModeLabelParseFunction() {
        return new CodeLabelParseFunction("getAccessWriteModeLabel", Map.ofEntries(Map.entry("CREATE_NEW", "新建目标表"), Map.entry("STRICT_COPY", "严格对拷")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeFillImportModeLabelParseFunction() {
        return new CodeLabelParseFunction("getFillImportModeLabel", Map.ofEntries(Map.entry("1", "追加到现有草稿"), Map.entry("2", "替换全部草稿行")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeFillOperationTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getFillOperationTypeLabel", Map.ofEntries(Map.entry("SET_CELL", "修改单元格"), Map.entry("ADD_ROW", "新增行"), Map.entry("REMOVE_ROW", "删除行")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeFillSubmissionTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getFillSubmissionTypeLabel", Map.ofEntries(Map.entry("1", "正常报送"), Map.entry("2", "零上报")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeFillPeriodTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getFillPeriodTypeLabel", Map.ofEntries(Map.entry("1", "一次性"), Map.entry("2", "每日"), Map.entry("3", "每周"), Map.entry("4", "每月"), Map.entry("5", "每季度")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeFillFieldTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getFillFieldTypeLabel", Map.ofEntries(Map.entry("1", "小数"), Map.entry("2", "字符串"), Map.entry("3", "日期"), Map.entry("5", "整数"), Map.entry("6", "长整型"), Map.entry("9", "日期时间"), Map.entry("10", "时间")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeFillRuleScopeLabelParseFunction() {
        return new CodeLabelParseFunction("getFillRuleScopeLabel", Map.ofEntries(Map.entry("ROW", "行级"), Map.entry("TABLE", "表级"), Map.entry("PERIOD", "跨期")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeIntegrationModeLabelParseFunction() {
        return new CodeLabelParseFunction("getIntegrationModeLabel", Map.ofEntries(Map.entry("OFFLINE", "离线"), Map.entry("REALTIME", "实时")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeEtlScheduleTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getEtlScheduleTypeLabel", Map.ofEntries(Map.entry("1", "一次性"), Map.entry("2", "周期性")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeProjectCategoryKindLabelParseFunction() {
        return new CodeLabelParseFunction("getProjectCategoryKindLabel", Map.ofEntries(Map.entry("integration", "集成"), Map.entry("dev", "开发")));
    }

    @Bean
    public CodeLabelParseFunction dataforgeDevTaskTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getDevTaskTypeLabel", Map.ofEntries(Map.entry("SQL", "SQL"),
                Map.entry("SPARK_SQL", "Spark SQL"), Map.entry("FLINK_SQL", "Flink SQL"), Map.entry("LEGACY_SQL", "旧版 SQL"),
                Map.entry("PROCEDURE", "存储过程"), Map.entry("PYTHON", "Python"), Map.entry("SHELL", "Shell")));
    }

    /** 来源系统类型：按字典 system_source_type 翻译，与字典维护保持一致 */
    @Bean
    public IParseFunction dataforgeSystemSourceTypeLabelParseFunction() {
        return new IParseFunction() {
            @Override
            public String functionName() {
                return "getSystemSourceTypeLabel";
            }

            @Override
            public String apply(Object value) {
                if (StrUtil.isEmptyIfStr(value)) {
                    return "未设置";
                }
                String code = String.valueOf(value);
                try {
                    return StrUtil.blankToDefault(DictFrameworkUtils.parseDictDataLabel("system_source_type", code), code);
                } catch (RuntimeException ex) {
                    // 字典读取失败时保留原值，不影响业务结果。
                    return code;
                }
            }
        };
    }

}

package cn.iocoder.yudao.module.governance.framework.operatelog.config;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.operatelog.core.function.CodeLabelParseFunction;
import cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categoryterm.CategoryTermDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterCatalogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterCodeRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDataChangeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDomainDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterMatchGroupDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterPushCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaFileDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryFailureDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityIssueDataDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderNotifyDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyAccessAlertDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyKeyRefDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeMappingDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categoryterm.CategoryTermMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCatalogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCodeRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDataChangeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterMatchGroupMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterPushCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectTaskMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaFileMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryFailureMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderNotifyMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyAccessAlertMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyKeyRefMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeMappingMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.whitelist.SafetyWhitelistMapper;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.system.api.permission.RoleApi;
import cn.iocoder.yudao.module.system.api.permission.dto.RoleRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.mzt.logapi.service.IParseFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.loader;
import static cn.iocoder.yudao.framework.operatelog.core.function.EntityNameParseFunction.via;

/**
 * 操作日志「编号 → 名称」翻译函数，模板写法如 {@code {getCategoryTermName{#id}}}。
 * <p>
 * 由各业务对象的 Mapper 按编号批量读取名称；没有独立名称的记录（通知、失败记录、运行记录等）显示其所属对象的名称。
 */
@Configuration(proxyBeanMethods = false)
public class GovernanceOperateLogConfiguration {

    @Bean
    public EntityNameParseFunction governanceCategoryTermNameParseFunction(CategoryTermMapper categoryTermMapper) {
        return new EntityNameParseFunction("getCategoryTermName",
                loader(categoryTermMapper::selectBatchIds, CategoryTermDO::getId, row -> row.getCategoryName()));
    }

    @Bean
    public EntityNameParseFunction governanceCategoryCleanRuleNameParseFunction(CategoryCleanRuleMapper categoryCleanRuleMapper) {
        return new EntityNameParseFunction("getCategoryCleanRuleName",
                loader(categoryCleanRuleMapper::selectBatchIds, CategoryCleanRuleDO::getId, row -> row.getCategoryName()));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyFieldTagNameParseFunction(SafetyFieldTagMapper safetyFieldTagMapper) {
        return new EntityNameParseFunction("getSafetyFieldTagName",
                loader(safetyFieldTagMapper::selectBatchIds, SafetyFieldTagDO::getId, row -> StrUtil.isBlank(row.getColumnName()) ? row.getTableName() : row.getTableName() + "." + row.getColumnName()));
    }

    @Bean
    public EntityNameParseFunction governanceStandardElementNameParseFunction(StandardElementMapper standardElementMapper) {
        return new EntityNameParseFunction("getStandardElementName",
                loader(standardElementMapper::selectBatchIds, StandardElementDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceStandardElementRuleNameParseFunction(StandardElementRuleMapper standardElementRuleMapper) {
        return new EntityNameParseFunction("getStandardElementRuleName",
                loader(standardElementRuleMapper::selectBatchIds, StandardElementRuleDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceStandardMappingNameParseFunction(StandardMappingMapper standardMappingMapper) {
        return new EntityNameParseFunction("getStandardMappingName",
                loader(standardMappingMapper::selectBatchIds, StandardMappingDO::getId, row -> row.getTargetNameSnapshot()));
    }

    @Bean
    public EntityNameParseFunction governanceStandardCodeSetNameParseFunction(StandardCodeSetMapper standardCodeSetMapper) {
        return new EntityNameParseFunction("getStandardCodeSetName",
                loader(standardCodeSetMapper::selectBatchIds, StandardCodeSetDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceStandardCodeItemNameParseFunction(StandardCodeItemMapper standardCodeItemMapper) {
        return new EntityNameParseFunction("getStandardCodeItemName",
                loader(standardCodeItemMapper::selectBatchIds, StandardCodeItemDO::getId, row -> StrUtil.blankToDefault(row.getLabel(), row.getValue())));
    }

    @Bean
    public EntityNameParseFunction governanceStandardCodeMappingNameParseFunction(StandardCodeMappingMapper standardCodeMappingMapper) {
        return new EntityNameParseFunction("getStandardCodeMappingName",
                loader(standardCodeMappingMapper::selectBatchIds, StandardCodeMappingDO::getId, row -> row.getOriginalValue() == null ? null : "原值" + row.getOriginalValue()));
    }

    @Bean
    public EntityNameParseFunction governanceStandardDocNameParseFunction(StandardDocMapper standardDocMapper) {
        return new EntityNameParseFunction("getStandardDocName",
                loader(standardDocMapper::selectBatchIds, StandardDocDO::getId, row -> StrUtil.blankToDefault(row.getNameCn(), row.getFileName())));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyWhitelistNameParseFunction(SafetyWhitelistMapper safetyWhitelistMapper) {
        return new EntityNameParseFunction("getSafetyWhitelistName",
                loader(safetyWhitelistMapper::selectBatchIds, SafetyWhitelistDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyCategoryNameParseFunction(SafetyCategoryMapper safetyCategoryMapper) {
        return new EntityNameParseFunction("getSafetyCategoryName",
                loader(safetyCategoryMapper::selectBatchIds, SafetyCategoryDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyLevelNameParseFunction(SafetyLevelMapper safetyLevelMapper) {
        return new EntityNameParseFunction("getSafetyLevelName",
                loader(safetyLevelMapper::selectBatchIds, SafetyLevelDO::getId, row -> row.getSafetyName()));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyRuleNameParseFunction(SafetyRuleMapper safetyRuleMapper) {
        return new EntityNameParseFunction("getSafetyRuleName",
                loader(safetyRuleMapper::selectBatchIds, SafetyRuleDO::getId, row -> row.getRuleName()));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyKeyRefNameParseFunction(SafetyKeyRefMapper safetyKeyRefMapper) {
        return new EntityNameParseFunction("getSafetyKeyRefName",
                loader(safetyKeyRefMapper::selectBatchIds, SafetyKeyRefDO::getId, row -> row.getKeyName()));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyStaticTaskNameParseFunction(SafetyStaticTaskMapper safetyStaticTaskMapper) {
        return new EntityNameParseFunction("getSafetyStaticTaskName",
                loader(safetyStaticTaskMapper::selectBatchIds, SafetyStaticTaskDO::getId, row -> row.getTaskName()));
    }

    @Bean
    public EntityNameParseFunction governanceSafetyAccessAlertNameParseFunction(SafetyAccessAlertMapper safetyAccessAlertMapper) {
        return new EntityNameParseFunction("getSafetyAccessAlertName",
                loader(safetyAccessAlertMapper::selectBatchIds, SafetyAccessAlertDO::getId, row -> accessAlertName(row.getUserName(), row.getRuleCode())));
    }

    @Bean
    public EntityNameParseFunction governanceQualityRuleNameParseFunction(QualityRuleMapper qualityRuleMapper) {
        return new EntityNameParseFunction("getQualityRuleName",
                loader(qualityRuleMapper::selectBatchIds, QualityRuleDO::getId, row -> row.getRuleName()));
    }

    @Bean
    public EntityNameParseFunction governanceQualityRuleSetNameParseFunction(QualityRuleSetMapper qualityRuleSetMapper) {
        return new EntityNameParseFunction("getQualityRuleSetName",
                loader(qualityRuleSetMapper::selectBatchIds, QualityRuleSetDO::getId, row -> row.getSetName()));
    }

    @Bean
    public EntityNameParseFunction governanceQualityAuditLogNameParseFunction(QualityAuditLogMapper qualityAuditLogMapper) {
        return new EntityNameParseFunction("getQualityAuditLogName",
                loader(qualityAuditLogMapper::selectBatchIds, QualityAuditLogDO::getId, row -> StrUtil.isBlank(row.getTableName()) ? row.getRuleName() : row.getRuleName() + "（" + row.getTableName() + "）"));
    }

    @Bean
    public EntityNameParseFunction governanceQualityIssueDataNameParseFunction(QualityIssueDataMapper qualityIssueDataMapper) {
        return new EntityNameParseFunction("getQualityIssueDataName",
                loader(qualityIssueDataMapper::selectBatchIds, QualityIssueDataDO::getId, row -> row.getTableName()));
    }

    @Bean
    public EntityNameParseFunction governanceQualityWorkOrderNameParseFunction(QualityWorkOrderMapper qualityWorkOrderMapper) {
        return new EntityNameParseFunction("getQualityWorkOrderName",
                loader(qualityWorkOrderMapper::selectBatchIds, QualityWorkOrderDO::getId, row -> row.getOrderName()));
    }

    @Bean
    public EntityNameParseFunction governanceQualityWorkOrderNotifyNameParseFunction(QualityWorkOrderNotifyMapper qualityWorkOrderNotifyMapper, QualityWorkOrderMapper qualityWorkOrderMapper) {
        return new EntityNameParseFunction("getQualityWorkOrderNotifyName",
                via(qualityWorkOrderNotifyMapper::selectBatchIds, QualityWorkOrderNotifyDO::getId, QualityWorkOrderNotifyDO::getWorkOrderId,
                loader(qualityWorkOrderMapper::selectBatchIds, QualityWorkOrderDO::getId, row -> row.getOrderName())));
    }

    @Bean
    public EntityNameParseFunction governanceQualityExpiryFailureNameParseFunction(QualityExceptionExpiryFailureMapper qualityExceptionExpiryFailureMapper, QualityWorkOrderMapper qualityWorkOrderMapper) {
        return new EntityNameParseFunction("getQualityExpiryFailureName",
                via(qualityExceptionExpiryFailureMapper::selectBatchIds, QualityExceptionExpiryFailureDO::getId, QualityExceptionExpiryFailureDO::getWorkOrderId,
                loader(qualityWorkOrderMapper::selectBatchIds, QualityWorkOrderDO::getId, row -> row.getOrderName())));
    }

    @Bean
    public EntityNameParseFunction governanceMasterCatalogNameParseFunction(MasterCatalogMapper masterCatalogMapper) {
        return new EntityNameParseFunction("getMasterCatalogName",
                loader(masterCatalogMapper::selectBatchIds, MasterCatalogDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceMasterModelNameParseFunction(MasterModelMapper masterModelMapper) {
        return new EntityNameParseFunction("getMasterModelName",
                loader(masterModelMapper::selectBatchIds, MasterModelDO::getId, row -> StrUtil.blankToDefault(row.getTableCn(), row.getTableName())));
    }

    @Bean
    public EntityNameParseFunction governanceMasterMatchGroupNameParseFunction(MasterMatchGroupMapper masterMatchGroupMapper, MasterModelMapper masterModelMapper) {
        return new EntityNameParseFunction("getMasterMatchGroupName",
                via(masterMatchGroupMapper::selectBatchIds, MasterMatchGroupDO::getId, MasterMatchGroupDO::getModelId,
                loader(masterModelMapper::selectBatchIds, MasterModelDO::getId, row -> StrUtil.blankToDefault(row.getTableCn(), row.getTableName()))));
    }

    @Bean
    public EntityNameParseFunction governanceMasterChangeNameParseFunction(MasterDataChangeMapper masterDataChangeMapper, MasterModelMapper masterModelMapper) {
        return new EntityNameParseFunction("getMasterChangeName",
                via(masterDataChangeMapper::selectBatchIds, MasterDataChangeDO::getId, MasterDataChangeDO::getModelId,
                loader(masterModelMapper::selectBatchIds, MasterModelDO::getId, row -> StrUtil.blankToDefault(row.getTableCn(), row.getTableName()))));
    }

    @Bean
    public EntityNameParseFunction governanceMasterDomainNameParseFunction(MasterDomainMapper masterDomainMapper) {
        return new EntityNameParseFunction("getMasterDomainName",
                loader(masterDomainMapper::selectBatchIds, MasterDomainDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceMasterPushCategoryNameParseFunction(MasterPushCategoryMapper masterPushCategoryMapper) {
        return new EntityNameParseFunction("getMasterPushCategoryName",
                loader(masterPushCategoryMapper::selectBatchIds, MasterPushCategoryDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceMasterCodeRuleNameParseFunction(MasterCodeRuleMapper masterCodeRuleMapper) {
        return new EntityNameParseFunction("getMasterCodeRuleName",
                loader(masterCodeRuleMapper::selectBatchIds, MasterCodeRuleDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceMasterDistributeNameParseFunction(MasterDistributeMapper masterDistributeMapper) {
        return new EntityNameParseFunction("getMasterDistributeName",
                loader(masterDistributeMapper::selectBatchIds, MasterDistributeDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceMasterDistributeRunNameParseFunction(MasterDistributeLogMapper masterDistributeLogMapper, MasterDistributeMapper masterDistributeMapper) {
        return new EntityNameParseFunction("getMasterDistributeRunName",
                via(masterDistributeLogMapper::selectBatchIds, MasterDistributeLogDO::getId, MasterDistributeLogDO::getDistributeId,
                loader(masterDistributeMapper::selectBatchIds, MasterDistributeDO::getId, row -> row.getName())));
    }

    @Bean
    public EntityNameParseFunction governanceMetaCollectTaskNameParseFunction(MetaCollectTaskMapper metaCollectTaskMapper) {
        return new EntityNameParseFunction("getMetaCollectTaskName",
                loader(metaCollectTaskMapper::selectBatchIds, MetaCollectTaskDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceMetaTagNameParseFunction(MetaTagMapper metaTagMapper) {
        return new EntityNameParseFunction("getMetaTagName",
                loader(metaTagMapper::selectBatchIds, MetaTagDO::getId, row -> row.getName()));
    }

    @Bean
    public EntityNameParseFunction governanceMetaDbNameParseFunction(MetaDbMapper metaDbMapper) {
        return new EntityNameParseFunction("getMetaDbName",
                loader(metaDbMapper::selectBatchIds, MetaDbDO::getId, row -> StrUtil.isBlank(row.getSchemaName()) ? row.getDbName() : row.getDbName() + "." + row.getSchemaName()));
    }

    @Bean
    public EntityNameParseFunction governanceMetaTableNameParseFunction(MetaTableMapper metaTableMapper) {
        return new EntityNameParseFunction("getMetaTableName",
                loader(metaTableMapper::selectBatchIds, MetaTableDO::getId, row -> StrUtil.blankToDefault(row.getBusinessName(), row.getTableName())));
    }

    @Bean
    public EntityNameParseFunction governanceMetaColumnNameParseFunction(MetaColumnMapper metaColumnMapper) {
        return new EntityNameParseFunction("getMetaColumnName",
                loader(metaColumnMapper::selectBatchIds, MetaColumnDO::getId, row -> StrUtil.blankToDefault(row.getBusinessName(), row.getColumnName())));
    }

    @Bean
    public EntityNameParseFunction governanceMetaFileNameParseFunction(MetaFileMapper metaFileMapper) {
        return new EntityNameParseFunction("getMetaFileName",
                loader(metaFileMapper::selectBatchIds, MetaFileDO::getId, row -> StrUtil.blankToDefault(row.getBusinessName(), row.getName())));
    }

    /**
     * 元数据对象（库 / 表 / 字段），模板写法：{@code {getMetaObjectName{#objectType + ':' + #objectId}}}。
     */
    @Bean
    public IParseFunction governanceMetaObjectNameParseFunction(MetaDbMapper metaDbMapper, MetaTableMapper metaTableMapper,
                                                                MetaColumnMapper metaColumnMapper) {
        EntityNameParseFunction db = new EntityNameParseFunction("getMetaDbName", loader(metaDbMapper::selectBatchIds,
                MetaDbDO::getId, row -> StrUtil.isBlank(row.getSchemaName()) ? row.getDbName() : row.getDbName() + "." + row.getSchemaName()));
        EntityNameParseFunction table = new EntityNameParseFunction("getMetaTableName", loader(metaTableMapper::selectBatchIds,
                MetaTableDO::getId, row -> StrUtil.blankToDefault(row.getBusinessName(), row.getTableName())));
        EntityNameParseFunction column = new EntityNameParseFunction("getMetaColumnName", loader(metaColumnMapper::selectBatchIds,
                MetaColumnDO::getId, row -> StrUtil.blankToDefault(row.getBusinessName(), row.getColumnName())));
        return new IParseFunction() {
            @Override
            public boolean executeBefore() {
                return true;
            }

            @Override
            public String functionName() {
                return "getMetaObjectName";
            }

            @Override
            public String apply(Object value) {
                String text = String.valueOf(value);
                String type = StrUtil.subBefore(text, ':', false);
                String id = StrUtil.subAfter(text, ':', false);
                return switch (type) {
                    case "1" -> "库 " + db.apply(id);
                    case "2" -> "表 " + table.apply(id);
                    case "3" -> "字段 " + column.apply(id);
                    default -> db.apply(id);
                };
            }
        };
    }

    // ========== 系统模块：用户、部门、角色 ==========

    @Bean
    public EntityNameParseFunction governanceUserNameParseFunction(AdminUserApi adminUserApi) {
        return new EntityNameParseFunction("getGovernanceUserName",
                loader(ids -> adminUserApi.getUserList(ids).getCheckedData(),
                        AdminUserRespDTO::getId, AdminUserRespDTO::getNickname));
    }

    @Bean
    public EntityNameParseFunction governanceDeptNameParseFunction(DeptApi deptApi) {
        return new EntityNameParseFunction("getGovernanceDeptName",
                loader(ids -> deptApi.getDeptList(ids).getCheckedData(), DeptRespDTO::getId, DeptRespDTO::getName));
    }

    @Bean
    public EntityNameParseFunction governanceRoleNameParseFunction(RoleApi roleApi) {
        return new EntityNameParseFunction("getGovernanceRoleName",
                loader(ids -> roleApi.getRoleList(ids).getCheckedData(), RoleRespDTO::getId, RoleRespDTO::getName));
    }

    /** 异常访问告警：显示「用户 的 规则」 */
    private static String accessAlertName(String userName, String ruleCode) {
        String rule = switch (StrUtil.nullToEmpty(ruleCode)) {
            case "VOLUME" -> "大批量访问";
            case "PLAINTEXT" -> "明文访问";
            case "OFF_HOURS" -> "非工作时间访问";
            case "TABLE_SPREAD" -> "跨表扩散访问";
            default -> ruleCode;
        };
        return StrUtil.isBlank(userName) ? rule : userName + " 的" + rule;
    }

    // ========== 业务代码翻译（类型、模式、级别等），未收录的值原样输出 ==========

    @Bean
    public CodeLabelParseFunction governanceWorkOrderSubmissionTypeLabelParseFunction() {
        return new CodeLabelParseFunction("getWorkOrderSubmissionTypeLabel", Map.ofEntries(Map.entry("SOURCE_FIX", "源端整改"), Map.entry("PLATFORM_REPAIR", "平台修复"), Map.entry("EXPLAIN", "情况说明"), Map.entry("EXCEPTION", "例外申请")));
    }

    @Bean
    public CodeLabelParseFunction governanceWorkOrderConfirmActionLabelParseFunction() {
        return new CodeLabelParseFunction("getWorkOrderConfirmActionLabel", Map.ofEntries(Map.entry("VERIFY_PASS", "复检通过"), Map.entry("RETURN", "退回"), Map.entry("ACCEPT_EXPLAIN", "接受说明"), Map.entry("ACCEPT_EXCEPTION", "接受例外")));
    }

}

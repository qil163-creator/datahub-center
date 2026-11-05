package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTableVersionPublishReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTableVersionMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableColumnMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillMergeStrategyEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkflowModeEnum;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-模板版本 Service 实现类
 *
 * 版本发布只固化元数据，不创建目标物理表；物理表的创建/校验由任务发布环节
 * 按 targetMapping 执行（复用现有 DBSwitch 建表能力）
 *
 * @author Li
 */
@Service
@Validated
public class FillTableVersionServiceImpl implements FillTableVersionService {

    /** 当前锁定的前端表格引擎版本，随依赖升级同步修改 */
    private static final String UNIVER_SDK_VERSION = "0.25.1";

    private static final String ADAPTER_PROTOCOL_VERSION = "1.0";

    private static final String SNAPSHOT_SCHEMA_VERSION = "1.0";

    private static final Integer VERSION_STATUS_RELEASED = 1;

    private static final Integer VERSION_STATUS_DISABLED = 2;

    @Resource
    private FillTableMapper fillTableMapper;

    @Resource
    private FillTableColumnMapper columnMapper;

    @Resource
    private DictDataApi dictDataApi;

    @Resource
    private FillTableVersionMapper versionMapper;

    /**
     * 从填报表当前字段配置固化一个不可变模板版本
     *
     *  固化内容：字段结构（fieldCode/类型/字典/必填）、目标映射（数据源/表/列映射）、
     *  基础校验规则、合并策略与业务键、内容哈希；发布后模板进入审批流工作模式
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishVersion(FillTableVersionPublishReqVO reqVO) {
        // 1. 校验模板与字段配置
        FillTableDO table = fillTableMapper.selectById(reqVO.getTableId());
        if (table == null) {
            throw exception(FILL_TABLE_NOT_EXISTS);
        }
        List<FillTableColumnDO> columns = columnMapper.selectListByTableId(reqVO.getTableId());
        if (CollUtil.isEmpty(columns)) {
            throw exception(FILL_TABLE_NO_COLUMN);
        }
        columns.forEach(column -> {
            if (StrUtil.isBlank(column.getFieldCode())) {
                throw exception(FILL_TABLE_FIELD_CODE_MISSING, column.getCnName());
            }
        });

        // 2. 校验合并策略与业务键
        FillMergeStrategyEnum strategy = FillMergeStrategyEnum.of(reqVO.getMergeStrategy());
        if (strategy == null) {
            throw exception(FILL_MERGE_STRATEGY_INVALID, reqVO.getMergeStrategy());
        }
        Set<String> fieldCodes = columns.stream()
                .map(FillTableColumnDO::getFieldCode).collect(Collectors.toSet());
        if (strategy == FillMergeStrategyEnum.UPSERT) {
            if (CollUtil.isEmpty(reqVO.getBusinessKeyFields())) {
                throw exception(FILL_BUSINESS_KEY_REQUIRED);
            }
            reqVO.getBusinessKeyFields().forEach(key -> {
                if (!fieldCodes.contains(key)) {
                    throw exception(FILL_BUSINESS_KEY_FIELD_INVALID, key);
                }
            });
        }

        // 3. 固化 schema
        String fieldSchemaJson = JsonUtils.toJsonString(buildFieldSchema(columns));
        String targetMappingJson = JsonUtils.toJsonString(buildTargetMapping(table, columns));
        String validationSchemaJson = JsonUtils.toJsonString(buildValidationSchema(columns));
        // 公式定义由模板设计器（阶段 2）维护，发布时先固化空结构
        String formulaSchemaJson = "{\"engineVersion\":\"1.0\",\"formulas\":[]}";
        String businessKeyFields = CollUtil.isEmpty(reqVO.getBusinessKeyFields())
                ? null : String.join(",", reqVO.getBusinessKeyFields());
        String contentHash = DigestUtil.sha256Hex(fieldSchemaJson + "|" + targetMappingJson + "|"
                + validationSchemaJson + "|" + strategy.getStrategy() + "|" + StrUtil.nullToEmpty(businessKeyFields));

        // 4. 生成版本号并落库
        FillTableVersionDO latest = versionMapper.selectLatestByTableId(reqVO.getTableId());
        FillTableVersionDO version = FillTableVersionDO.builder()
                .tableId(reqVO.getTableId())
                .versionNo(latest == null ? 1 : latest.getVersionNo() + 1)
                .name(table.getTableCnName())
                .status(VERSION_STATUS_RELEASED)
                .fieldSchema(fieldSchemaJson)
                .formulaSchema(formulaSchemaJson)
                .validationSchema(validationSchemaJson)
                .targetMapping(targetMappingJson)
                .mergeStrategy(strategy.getStrategy())
                .businessKeyFields(businessKeyFields)
                .contentHash(contentHash)
                .univerSdkVersion(UNIVER_SDK_VERSION)
                .adapterProtocolVersion(ADAPTER_PROTOCOL_VERSION)
                .snapshotSchemaVersion(SNAPSHOT_SCHEMA_VERSION)
                .releaseUserId(SecurityFrameworkUtils.getLoginUserId())
                .releaseTime(LocalDateTime.now())
                .build();
        versionMapper.insert(version);

        // 5. 模板指向当前版本并进入审批流工作模式（新任务禁止直写正式表）
        FillTableDO update = new FillTableDO();
        update.setId(table.getId());
        update.setCurrentVersionId(version.getId());
        update.setWorkflowMode(FillWorkflowModeEnum.APPROVAL_FLOW.getMode());
        fillTableMapper.updateById(update);
        return version.getId();
    }

    /**
     * 获得模板版本（不存在抛异常）
     *
     * @param id 记录编号
     * @return 填报表版本
     */
    @Override
    public FillTableVersionDO getTableVersion(Long id) {
        FillTableVersionDO version = versionMapper.selectById(id);
        if (version == null) {
            throw exception(FILL_TABLE_VERSION_NOT_EXISTS);
        }
        return version;
    }

    /**
     * 获得模板版本，并校验可被新任务引用（不存在或已停用抛异常）
     *
     * @param id 记录编号
     * @return 填报表版本
     */
    @Override
    public FillTableVersionDO getActiveTableVersion(Long id) {
        FillTableVersionDO version = getTableVersion(id);
        if (VERSION_STATUS_DISABLED.equals(version.getStatus())) {
            throw exception(FILL_TABLE_VERSION_DISABLED);
        }
        return version;
    }

    /**
     * 获得填报表的版本列表（按版本号倒序）
     *
     * @param tableId 表编号
     * @return 填报表的版本列表（按版本号倒序）结果集合
     */
    @Override
    public List<FillTableVersionDO> getTableVersionListByTableId(Long tableId) {
        return versionMapper.selectListByTableId(tableId);
    }

    /**
     * 停用模板版本（仅禁止新任务引用，不影响进行中任务）
     *
     * @param id 记录编号
     */
    @Override
    public void disableTableVersion(Long id) {
        FillTableVersionDO version = getTableVersion(id);
        FillTableVersionDO update = new FillTableVersionDO();
        update.setId(version.getId());
        update.setStatus(VERSION_STATUS_DISABLED);
        versionMapper.updateById(update);
    }

    // ---------------------------------------------------------------- schema 构建

    /**
     * 构造字段结构。
     *
     * @param columns 列集合
     * @return 构造字段结构结果集合
     */
    private List<Map<String, Object>> buildFieldSchema(List<FillTableColumnDO> columns) {
        List<Map<String, Object>> fields = new ArrayList<>(columns.size());
        for (FillTableColumnDO column : columns) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("fieldCode", column.getFieldCode());
            field.put("name", column.getCnName());
            field.put("targetColumnName", column.getName());
            field.put("fieldType", column.getFieldType());
            field.put("fillType", column.getFillType());
            field.put("dictOptions", resolveOptions(column));
            field.put("hidden", isTrue(column.getFillHide()));
            field.put("required", column.getNullable() != null && column.getNullable() == 0);
            field.put("pk", isTrue(column.getPk()));
            field.put("pkType", column.getPkType());
            field.put("length", column.getFieldLength());
            field.put("scale", column.getFieldScale());
            // 取值范围、格式、唯一与规则级别随版本固化，提交和导入按此校验
            field.put("min", StrUtil.emptyToNull(StrUtil.trim(column.getMinValue())));
            field.put("max", StrUtil.emptyToNull(StrUtil.trim(column.getMaxValue())));
            field.put("regex", StrUtil.emptyToNull(StrUtil.trim(column.getRegexPattern())));
            field.put("regexMessage", StrUtil.emptyToNull(StrUtil.trim(column.getRegexMessage())));
            field.put("unique", isTrue(column.getUniqueFlag()));
            field.put("ruleSeverity", column.getRuleSeverity() == null
                    ? FillFieldRuleValidator.SEVERITY_ERROR : column.getRuleSeverity());
            field.put("sort", column.getSort());
            // 一期字段均为输入字段；公式列（editable=false）由公式 schema 标注后覆盖
            field.put("editable", true);
            fields.add(field);
        }
        return fields;
    }

    /**
     * 构造目标映射。
     *
     * @param table 表
     * @param columns 列集合
     * @return 处理后的键值映射
     */
    private Map<String, Object> buildTargetMapping(FillTableDO table, List<FillTableColumnDO> columns) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("databaseId", table.getDatabaseId());
        mapping.put("tableName", table.getTableEngName());
        mapping.put("columns", columns.stream().map(column -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fieldCode", column.getFieldCode());
            item.put("targetColumnName", column.getName());
            return item;
        }).collect(Collectors.toList()));
        return mapping;
    }

    /** 从字段配置推导基础校验规则；跨字段/业务规则由模板设计器（阶段 2）维护 */
    private Map<String, Object> buildValidationSchema(List<FillTableColumnDO> columns) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (FillTableColumnDO column : columns) {
            if (column.getNullable() != null && column.getNullable() == 0) {
                rules.add(rule("REQUIRED", column.getFieldCode(), null));
            }
            List<String> dictOptions = resolveOptions(column);
            if (CollUtil.isNotEmpty(dictOptions)) {
                rules.add(rule("DICT", column.getFieldCode(), dictOptions));
            }
            if (StrUtil.isNotBlank(column.getMinValue())) {
                rules.add(rule("MIN_VALUE", column.getFieldCode(), column.getMinValue().trim()));
            }
            if (StrUtil.isNotBlank(column.getMaxValue())) {
                rules.add(rule("MAX_VALUE", column.getFieldCode(), column.getMaxValue().trim()));
            }
            if (StrUtil.isNotBlank(column.getRegexPattern())) {
                rules.add(rule("REGEX", column.getFieldCode(), column.getRegexPattern().trim()));
            }
            if (isTrue(column.getUniqueFlag())) {
                rules.add(rule("UNIQUE", column.getFieldCode(), null));
            }
            if (column.getFieldLength() != null && column.getFieldLength() > 0) {
                rules.add(rule("MAX_LENGTH", column.getFieldCode(), column.getFieldLength()));
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("rules", rules);
        return schema;
    }

    /**
     * 读取填报表版本规则。
     *
     * @param ruleCode 规则编码
     * @param fieldCode 字段编码
     * @param param 参数
     * @return 处理后的键值映射
     */
    private Map<String, Object> rule(String ruleCode, String fieldCode, Object param) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", ruleCode);
        rule.put("fieldCode", fieldCode);
        if (param != null) {
            rule.put("param", param);
        }
        return rule;
    }

    /**
     * 解析选择值：绑定系统字典时固化当前启用的字典项名称，否则使用手工维护的选择值。
     */
    private List<String> resolveOptions(FillTableColumnDO column) {
        if (StrUtil.isBlank(column.getDictType())) {
            return parseDictOptions(column);
        }
        var items = dictDataApi.getDictDataList(column.getDictType().trim()).getCheckedData();
        List<String> labels = items == null ? List.of() : items.stream()
                .filter(item -> item.getStatus() != null && item.getStatus() == 0)
                .map(item -> StrUtil.trim(item.getLabel()))
                .filter(StrUtil::isNotEmpty)
                .distinct()
                .collect(Collectors.toList());
        if (labels.isEmpty()) {
            throw exception(FILL_TABLE_COLUMN_DICT_INVALID, column.getDictType());
        }
        return labels;
    }

    /**
     * 解析字典选项。
     *
     * @param column 列
     * @return 解析字典选项结果集合
     */
    private List<String> parseDictOptions(FillTableColumnDO column) {
        if (column.getFillType() == null || column.getFillType() != 2
                || StrUtil.isBlank(column.getFillValues())) {
            return List.of();
        }
        return StrUtil.split(column.getFillValues(), ',').stream()
                .map(String::trim).filter(StrUtil::isNotEmpty).collect(Collectors.toList());
    }

    /**
     * 判断是否满足真值条件。
     *
     * @param value 待处理的值
     * @return 判断结果
     */
    private boolean isTrue(Integer value) {
        return value != null && value == 1;
    }

}

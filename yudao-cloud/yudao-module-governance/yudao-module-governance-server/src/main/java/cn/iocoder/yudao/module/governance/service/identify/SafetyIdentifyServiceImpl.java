package cn.iocoder.yudao.module.governance.service.identify;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyScanRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyIdentifyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyScanRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 敏感数据识别与字段打标 Service 实现类
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class SafetyIdentifyServiceImpl implements SafetyIdentifyService {

    @Resource
    private SafetyIdentifyRuleMapper ruleMapper;

    @Resource
    private SafetyScanRunMapper runMapper;

    @Resource
    private SafetyFieldTagMapper tagMapper;

    @Resource
    private SafetyCategoryMapper categoryMapper;

    @Resource
    private MetaTableMapper metaTableMapper;

    @Resource
    private MetaColumnMapper metaColumnMapper;

    @Resource
    private SafetyScanExecutor scanExecutor;

    // ==================== 识别规则 ====================

    /**
     * 校验名称、分类及匹配配置后创建识别规则，新规则统一标记为非内置规则。
     */
    @Override
    public Long createRule(SafetyIdentifyRuleSaveReqVO createReqVO) {
        validateRule(createReqVO);
        SafetyIdentifyRuleDO rule = BeanUtils.toBean(createReqVO, SafetyIdentifyRuleDO.class);
        rule.setBuiltinFlag(0);
        ruleMapper.insert(rule);
        return rule.getId();
    }

    /**
     * 校验目标规则存在并重新核验名称、分类及匹配配置后，更新规则定义。
     */
    @Override
    public void updateRule(SafetyIdentifyRuleSaveReqVO updateReqVO) {
        validateRuleExists(updateReqVO.getId());
        validateRule(updateReqVO);
        var update = new LambdaUpdateWrapper<SafetyIdentifyRuleDO>()
                .eq(SafetyIdentifyRuleDO::getId, updateReqVO.getId())
                .set(updateReqVO.getLevelId() == null, SafetyIdentifyRuleDO::getLevelId, null)
                .set(updateReqVO.getDescription() == null, SafetyIdentifyRuleDO::getDescription, null);
        ruleMapper.update(BeanUtils.toBean(updateReqVO, SafetyIdentifyRuleDO.class), update);
    }

    /**
     * 删除已存在的非内置识别规则，内置规则直接拒绝删除。
     */
    @Override
    public void deleteRule(Long id) {
        SafetyIdentifyRuleDO rule = validateRuleExists(id);
        if (rule.getBuiltinFlag() != null && rule.getBuiltinFlag() == 1) {
            throw exception(IDENTIFY_RULE_BUILTIN_CANNOT_DELETE);
        }
        ruleMapper.deleteById(id);
    }

    /**
     * 按编号读取识别规则，记录不存在时返回业务错误。
     */
    @Override
    public SafetyIdentifyRuleDO getRule(Long id) {
        return validateRuleExists(id);
    }

    /**
     * 按名称、匹配方式、分类和启用状态分页查询识别规则。
     */
    @Override
    public PageResult<SafetyIdentifyRuleDO> getRulePage(SafetyIdentifyRulePageReqVO pageReqVO) {
        return ruleMapper.selectPage(pageReqVO);
    }

    /**
     * 校验敏感识别的规则记录存在，不存在时抛出业务异常。
     */
    private SafetyIdentifyRuleDO validateRuleExists(Long id) {
        SafetyIdentifyRuleDO rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw exception(IDENTIFY_RULE_NOT_EXISTS);
        }
        return rule;
    }

    /**
     * 校验名称在排除当前记录后唯一、分类存在，并编译非注释关键词方式的匹配正则。
     */
    private void validateRule(SafetyIdentifyRuleSaveReqVO reqVO) {
        // 名称唯一
        SafetyIdentifyRuleDO exists = ruleMapper.selectByRuleName(reqVO.getRuleName());
        if (exists != null && !exists.getId().equals(reqVO.getId())) {
            throw exception(IDENTIFY_RULE_NAME_DUPLICATE);
        }
        // 分类存在
        if (categoryMapper.selectById(reqVO.getCategoryId()) == null) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        // 正则合法（COLUMN_NAME / DATA_REGEX）
        if (!SafetyIdentifyRuleDO.MATCH_TYPE_COLUMN_COMMENT.equals(reqVO.getMatchType())) {
            try {
                Pattern.compile(reqVO.getPattern());
            } catch (PatternSyntaxException ex) {
                throw exception(IDENTIFY_RULE_PATTERN_INVALID, StrUtil.maxLength(ex.getMessage(), 200));
            }
        }
    }

    // ==================== 扫描执行 ====================

    /**
     * 使用当前启用规则异步扫描指定数据源及表范围。
     *
     * 本实例扫描注册表或数据库运行记录已存在进行中扫描时拒绝提交；
     * 提交时再次认领失败也按扫描进行中返回，成功后返回扫描记录编号。
     *
     * @return 新受理的扫描记录编号
     */
    @Override
    public Long triggerScan(SafetyScanReqVO reqVO) {
        List<SafetyIdentifyRuleDO> rules = ruleMapper.selectEnabledList();
        if (CollUtil.isEmpty(rules)) {
            throw exception(SCAN_NO_ENABLED_RULE);
        }
        if (scanExecutor.isRunning(reqVO.getDatabaseId())
                || runMapper.selectRunningByDatabaseId(reqVO.getDatabaseId()) != null) {
            throw exception(SCAN_RUNNING);
        }
        Long runId = scanExecutor.submit(reqVO.getDatabaseId(), reqVO.getTableNames(), rules);
        if (runId == null) {
            throw exception(SCAN_RUNNING);
        }
        return runId;
    }

    /**
     * 按数据源和运行状态分页查询扫描记录，最新记录优先返回。
     */
    @Override
    public PageResult<SafetyScanRunDO> getScanRunPage(SafetyScanRunPageReqVO pageReqVO) {
        return runMapper.selectPage(pageReqVO);
    }

    // ==================== 字段打标 ====================

    /**
     * 校验物理字段尚未打标且分类存在，创建直接确认的人工标签并回写元数据。
     * 未显式指定等级时沿用分类的安全等级。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTag(SafetyFieldTagSaveReqVO createReqVO) {
        if (tagMapper.selectByColumn(createReqVO.getDatabaseId(),
                createReqVO.getTableName(), createReqVO.getColumnName()) != null) {
            throw exception(FIELD_TAG_DUPLICATE);
        }
        SafetyCategoryDO category = categoryMapper.selectById(createReqVO.getCategoryId());
        if (category == null) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        Long levelId = createReqVO.getLevelId() != null ? createReqVO.getLevelId()
                : category.getDataLevelId() == null ? null : category.getDataLevelId().longValue();
        SafetyFieldTagDO tag = SafetyFieldTagDO.builder()
                .databaseId(createReqVO.getDatabaseId())
                .tableName(createReqVO.getTableName())
                .columnName(createReqVO.getColumnName())
                .categoryId(createReqVO.getCategoryId())
                .levelId(levelId)
                .source(SafetyFieldTagDO.SOURCE_MANUAL)
                .tagStatus(SafetyFieldTagDO.TAG_STATUS_CONFIRMED)
                .confirmTime(LocalDateTime.now())
                .remark(createReqVO.getRemark())
                .build();
        tagMapper.insert(tag);
        // 人工打标视为已确认，直接回写元数据
        writeBackMetaColumn(tag);
        return tag.getId();
    }

    /**
     * 在同一事务内批量确认标签，并回写元数据字段的安全等级与敏感标记。
     * 已确认标签直接跳过，其余标签记录本次确认时间。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmTags(List<Long> ids) {
        for (Long id : ids) {
            SafetyFieldTagDO tag = validateTagExists(id);
            if (SafetyFieldTagDO.TAG_STATUS_CONFIRMED.equals(tag.getTagStatus())) {
                continue;
            }
            tagMapper.updateById(SafetyFieldTagDO.builder()
                    .id(id).tagStatus(SafetyFieldTagDO.TAG_STATUS_CONFIRMED)
                    .confirmTime(LocalDateTime.now())
                    .build());
            writeBackMetaColumn(tag);
        }
    }

    /**
     * 逐条将待处理或已驳回标签置为驳回状态并记录时间，已确认标签禁止驳回。
     */
    @Override
    public void rejectTags(List<Long> ids) {
        for (Long id : ids) {
            SafetyFieldTagDO tag = validateTagExists(id);
            if (SafetyFieldTagDO.TAG_STATUS_CONFIRMED.equals(tag.getTagStatus())) {
                throw exception(FIELD_TAG_STATUS_INVALID);
            }
            tagMapper.updateById(SafetyFieldTagDO.builder()
                    .id(id).tagStatus(SafetyFieldTagDO.TAG_STATUS_REJECTED)
                    .confirmTime(LocalDateTime.now())
                    .build());
        }
    }

    /**
     * 校验字段标签存在后删除打标记录。
     */
    @Override
    public void deleteTag(Long id) {
        validateTagExists(id);
        tagMapper.deleteById(id);
    }

    /**
     * 按数据源、表字段、分类、状态、来源及扫描批次分页查询字段标签。
     */
    @Override
    public PageResult<SafetyFieldTagDO> getTagPage(SafetyFieldTagPageReqVO pageReqVO) {
        return tagMapper.selectPage(pageReqVO);
    }

    /**
     * 校验敏感识别的标签记录存在，不存在时抛出业务异常。
     */
    private SafetyFieldTagDO validateTagExists(Long id) {
        SafetyFieldTagDO tag = tagMapper.selectById(id);
        if (tag == null) {
            throw exception(FIELD_TAG_NOT_EXISTS);
        }
        return tag;
    }

    /**
     * 回写字段安全等级及字段、表的敏感标记；未采集对应表或字段时跳过。
     * 回写异常仅记录告警，调用方的标签处理继续执行。
     */
    private void writeBackMetaColumn(SafetyFieldTagDO tag) {
        try {
            MetaTableDO metaTable = metaTableMapper.selectByDatasourceIdAndName(
                    tag.getDatabaseId(), tag.getTableName());
            if (metaTable == null) {
                return;
            }
            MetaColumnDO metaColumn = metaColumnMapper.selectByTableIdAndName(
                    metaTable.getId(), tag.getColumnName());
            if (metaColumn == null) {
                return;
            }
            MetaColumnDO update = new MetaColumnDO();
            update.setId(metaColumn.getId());
            update.setSensitiveFlag(1);
            if (tag.getLevelId() != null) {
                update.setSafetyLevelId(tag.getLevelId());
            }
            metaColumnMapper.updateById(update);
            // 表级敏感标记一并点亮
            if (metaTable.getSensitiveFlag() == null || metaTable.getSensitiveFlag() != 1) {
                MetaTableDO tableUpdate = new MetaTableDO();
                tableUpdate.setId(metaTable.getId());
                tableUpdate.setSensitiveFlag(1);
                metaTableMapper.updateById(tableUpdate);
            }
        } catch (Exception ex) {
            log.warn("[writeBackMetaColumn][打标({}) 回写元数据失败：{}]", tag.getId(), ex.getMessage());
        }
    }

}

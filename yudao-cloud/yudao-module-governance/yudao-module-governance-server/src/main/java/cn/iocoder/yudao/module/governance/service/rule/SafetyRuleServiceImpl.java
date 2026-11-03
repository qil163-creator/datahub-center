package cn.iocoder.yudao.module.governance.service.rule;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyReplaceItemVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.replaceitem.SafetyReplaceItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rulever.SafetyRuleVerDO;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyFieldPermissionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.replaceitem.SafetyReplaceItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rulever.SafetyRuleVerMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据安全--脱敏规则 Service 实现类
 *
 * 规则参数（config）由版本表 dh_governance_safety_rule_ver 承载：
 * 创建规则时同时发布 v1 版本；修改参数时发布新版本并回写 current_ver_id，历史版本不可变，支撑审计回溯
 *
 * @author Li
 */
@Service
@Validated
public class SafetyRuleServiceImpl implements SafetyRuleService {

    /** 规则来源：内置（不可删除） */
    private static final String SOURCE_TYPE_BUILTIN = "BUILTIN";

    /** 规则来源：自定义 */
    private static final String SOURCE_TYPE_CUSTOM = "CUSTOM";

    @Resource
    private SafetyRuleMapper ruleMapper;

    @Resource
    private SafetyRuleVerMapper ruleVerMapper;

    @Resource
    private SafetyReplaceItemMapper replaceItemMapper;

    @Resource
    private SafetyFieldPermissionMapper fieldPermissionMapper;

    @Resource
    private SafetyStaticTaskMapper staticTaskMapper;

    @Resource
    private SafetyCategoryMapper categoryMapper;

    /**
     * 创建数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRule(SafetyRuleSaveReqVO createReqVO) {
        // 校验参数合法
        SafetyRuleConfigValidator.validate(createReqVO.getMethodCode(), createReqVO.getConfig(),
                createReqVO.getReplaceItems());

        // 插入规则（来源固定为自定义，内置规则仅由初始化脚本产生）
        SafetyRuleDO rule = BeanUtils.toBean(createReqVO, SafetyRuleDO.class);
        rule.setId(null);
        rule.setSourceType(SOURCE_TYPE_CUSTOM);
        ruleMapper.insert(rule);

        // 发布 v1 版本并回写 current_ver_id
        Long verId = publishVersion(rule.getId(), 1, createReqVO.getConfig(), createReqVO.getReplaceItems());
        ruleMapper.updateById(SafetyRuleDO.builder().id(rule.getId()).currentVerId(verId).build());
        return rule.getId();
    }

    /**
     * 更新数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRule(SafetyRuleSaveReqVO updateReqVO) {
        // 校验存在
        SafetyRuleDO rule = lockRule(updateReqVO.getId());
        if (updateReqVO.getExpectedVersionId() != null
                && !Objects.equals(updateReqVO.getExpectedVersionId(), rule.getCurrentVerId())) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
        if (updateReqVO.getExpectedRevisionToken() != null
                && !Objects.equals(updateReqVO.getExpectedRevisionToken(), revisionToken(rule))) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
        // 算法类型不允许跨版本变更
        if (StrUtil.isNotEmpty(updateReqVO.getMethodCode())
                && ObjectUtil.notEqual(updateReqVO.getMethodCode(), rule.getMethodCode())) {
            throw exception(RULE_METHOD_CODE_CANNOT_CHANGE);
        }
        SafetyRuleConfigValidator.validate(rule.getMethodCode(), updateReqVO.getConfig(),
                updateReqVO.getReplaceItems());
        if (updateReqVO.getStatus() != null
                && Objects.equals(updateReqVO.getStatus().intValue(), CommonStatusEnum.DISABLE.getStatus())
                && !Objects.equals(rule.getStatus() == null ? null : rule.getStatus().intValue(),
                CommonStatusEnum.DISABLE.getStatus())) {
            lockRule(rule.getId());
            assertNotReferenced(rule.getId());
        }

        // 更新主表（来源不允许修改；current_ver_id 仅由发布流程回写）
        SafetyRuleDO updateObj = BeanUtils.toBean(updateReqVO, SafetyRuleDO.class);
        updateObj.setSourceType(null);
        updateObj.setCurrentVerId(null);
        ruleMapper.update(updateObj, new LambdaUpdateWrapper<SafetyRuleDO>()
                .eq(SafetyRuleDO::getId, rule.getId())
                .set(SafetyRuleDO::getSourceUpdaterName, null)
                .set(SafetyRuleDO::getSourceUpdateTime, null));

        // 参数有变化时发布新版本（已发布版本不可变，历史保留用于审计回溯）
        if (isConfigChanged(rule, updateReqVO)) {
            SafetyRuleVerDO latestVer = ruleVerMapper.selectLatestByRuleId(rule.getId());
            int nextVersionNo = latestVer == null ? 1 : latestVer.getVersionNo() + 1;
            Long verId = publishVersion(rule.getId(), nextVersionNo, updateReqVO.getConfig(),
                    updateReqVO.getReplaceItems());
            ruleMapper.updateById(SafetyRuleDO.builder().id(rule.getId()).currentVerId(verId).build());
        }
    }

    /**
     * 删除数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long id) {
        SafetyRuleDO rule = lockRule(id);
        validateRuleNotBuiltin(rule);
        assertNotReferenced(id);
        assertNoCategoryReference(id);
        ruleMapper.deleteById(id);
    }

    /**
     * 批量删除数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param ids 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRuleListByIds(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        ids.stream().filter(Objects::nonNull).distinct().sorted().forEach(id -> {
            SafetyRuleDO rule = lockRule(id);
            validateRuleNotBuiltin(rule);
            assertNotReferenced(id);
            assertNoCategoryReference(id);
        });
        ruleMapper.deleteByIds(ids);
    }

    /**
     * 校验规则记录存在，不存在时抛出业务异常。
     */
    private SafetyRuleDO validateRuleExists(Long id) {
        SafetyRuleDO rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw exception(RULE_NOT_EXISTS);
        }
        return rule;
    }

    /**
     * 校验脱敏规则的规则非内置，不满足约束时拒绝当前操作。
     */
    private void validateRuleNotBuiltin(SafetyRuleDO rule) {
        if (SOURCE_TYPE_BUILTIN.equals(rule.getSourceType())) {
            throw exception(RULE_BUILTIN_CANNOT_DELETE, rule.getRuleName());
        }
    }

    /**
     * 发布规则版本：插入版本（PUBLISHED，不可变）+ 替换明细（随版本固化）
     *
     * @return 版本编号
     */
    private Long publishVersion(Long ruleId, int versionNo, Map<String, Object> config,
            List<SafetyReplaceItemVO> replaceItems) {
        String configJson = ruleVerMapper.normalizeConfig(JsonUtils.toJsonString(
                config != null ? config : Collections.emptyMap()));
        SafetyRuleVerDO ver = SafetyRuleVerDO.builder()
                .ruleId(ruleId)
                .versionNo(versionNo)
                .schemaVersion(1)
                .configJson(configJson)
                .configChecksum(DigestUtil.sha256Hex(configJson))
                .status(SafetyRuleVerDO.STATUS_PUBLISHED)
                .publishedBy(StrUtil.blankToDefault(SecurityFrameworkUtils.getLoginUserNickname(),
                        String.valueOf(SecurityFrameworkUtils.getLoginUserId())))
                .publishedTime(LocalDateTime.now())
                .build();
        ruleVerMapper.insert(ver);
        if (CollUtil.isNotEmpty(replaceItems)) {
            List<SafetyReplaceItemDO> items = new ArrayList<>();
            for (int i = 0; i < replaceItems.size(); i++) {
                SafetyReplaceItemVO item = replaceItems.get(i);
                items.add(SafetyReplaceItemDO.builder()
                        .ruleVerId(ver.getId())
                        .sequenceNo(i + 1)
                        .matchValue(item.getMatchValue())
                        .replacementValue(StrUtil.nullToEmpty(item.getReplacementValue()))
                        .build());
            }
            replaceItemMapper.insertBatch(items);
        }
        return ver.getId();
    }

    /**
     * 判断参数是否变化（config 摘要或替换明细内容不同）
     */
    private boolean isConfigChanged(SafetyRuleDO rule, SafetyRuleSaveReqVO updateReqVO) {
        SafetyRuleVerDO currentVer = rule.getCurrentVerId() != null
                ? ruleVerMapper.selectById(rule.getCurrentVerId()) : null;
        if (currentVer == null) {
            return true;
        }
        String newConfigJson = ruleVerMapper.normalizeConfig(JsonUtils.toJsonString(
                updateReqVO.getConfig() != null ? updateReqVO.getConfig() : Collections.emptyMap()));
        if (ObjectUtil.notEqual(DigestUtil.sha256Hex(newConfigJson), currentVer.getConfigChecksum())) {
            return true;
        }
        // 比较替换明细（按顺序比较匹配值与替换值）
        List<SafetyReplaceItemDO> oldItems = replaceItemMapper.selectListByRuleVerId(currentVer.getId());
        List<SafetyReplaceItemVO> newItems = updateReqVO.getReplaceItems() != null
                ? updateReqVO.getReplaceItems() : Collections.emptyList();
        if (oldItems.size() != newItems.size()) {
            return true;
        }
        for (int i = 0; i < oldItems.size(); i++) {
            if (ObjectUtil.notEqual(oldItems.get(i).getMatchValue(), newItems.get(i).getMatchValue())
                    || ObjectUtil.notEqual(oldItems.get(i).getReplacementValue(),
                    StrUtil.nullToEmpty(newItems.get(i).getReplacementValue()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获得数据安全--脱敏规则（主表）；参数演进由版本表承载
     *
     *  @param id 编号
     *  @return 数据安全--脱敏规则（主表）；参数演进由版本表承载
     */
    @Override
    public SafetyRuleDO getRule(Long id) {
        return ruleMapper.selectById(id);
    }

    /**
     * 获得数据安全--脱敏规则（主表）；参数演进由版本表承载分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据安全--脱敏规则（主表）；参数演进由版本表承载分页
     */
    @Override
    public PageResult<SafetyRuleDO> getRulePage(SafetyRulePageReqVO pageReqVO) {
        return ruleMapper.selectPage(pageReqVO);
    }

    // ==================== 规则版本 / 替换明细 ====================

    /**
     * 获得规则版本
     *
     *  @param verId 版本编号
     *  @return 规则版本，不存在时返回 null
     */
    @Override
    public SafetyRuleVerDO getRuleVer(Long verId) {
        return verId != null ? ruleVerMapper.selectById(verId) : null;
    }

    /**
     * 获得指定版本的替换明细（按匹配顺序）
     *
     *  @param ruleVerId 版本编号
     *  @return 替换明细列表
     */
    @Override
    public List<SafetyReplaceItemDO> getReplaceItemListByRuleVerId(Long ruleVerId) {
        return replaceItemMapper.selectListByRuleVerId(ruleVerId);
    }

    /**
     * 配置保存/删除时锁定规则并要求存在已发布、启用的当前版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SafetyMaskingSpec requireUsableMaskingSpec(Long ruleId) {
        lockRule(ruleId);
        SafetyMaskingSpec spec = resolveUsableMaskingSpec(ruleId);
        if (spec == null) {
            throw exception(RULE_VERSION_UNAVAILABLE);
        }
        return spec;
    }

    /**
     * 查询开始时冻结规则版本；不可用时返回 null，由调用方失败关闭，不得回退明文。
     */
    @Override
    public SafetyMaskingSpec resolveUsableMaskingSpec(Long ruleId) {
        if (ruleId == null || ruleId <= 0) {
            return null;
        }
        SafetyRuleDO rule = ruleMapper.selectById(ruleId);
        if (rule == null || rule.getCurrentVerId() == null) {
            return null;
        }
        if (rule.getStatus() != null
                && Objects.equals(rule.getStatus().intValue(), CommonStatusEnum.DISABLE.getStatus())) {
            return null;
        }
        SafetyRuleVerDO ver = ruleVerMapper.selectById(rule.getCurrentVerId());
        if (ver == null || !Objects.equals(ver.getRuleId(), rule.getId())
                || !SafetyRuleVerDO.STATUS_PUBLISHED.equals(ver.getStatus())) {
            return null;
        }
        String configJson = StrUtil.nullToEmpty(ver.getConfigJson());
        if (StrUtil.isNotBlank(ver.getConfigChecksum())
                && !Objects.equals(ver.getConfigChecksum(), DigestUtil.sha256Hex(configJson))) {
            return null;
        }
        Map<String, Object> config = StrUtil.isBlank(configJson)
                ? Map.of()
                : JsonUtils.parseObject(configJson, new TypeReference<Map<String, Object>>() {
        });
        List<SafetyReplaceItemDO> replaceItems = SafetyRuleConfigValidator.useReplaceItems(rule.getMethodCode())
                ? replaceItemMapper.selectListByRuleVerId(ver.getId())
                : List.of();
        return SafetyMaskingSpec.builder()
                .ruleId(rule.getId())
                .versionId(ver.getId())
                .versionNo(ver.getVersionNo())
                .methodCode(rule.getMethodCode())
                .config(config == null ? Map.of() : config)
                .replaceItems(replaceItems == null ? List.of() : replaceItems)
                .build();
    }

    /**
     * 一次查询固定实际规则版本，避免逐行追读当前指针。
     */
    @Override
    public Map<Long, SafetyMaskingSpec> freezeUsableMaskingSpecs(Collection<Long> ruleIds) {
        Map<Long, SafetyMaskingSpec> result = new LinkedHashMap<>();
        if (CollUtil.isEmpty(ruleIds)) {
            return result;
        }
        for (Long ruleId : ruleIds) {
            if (ruleId == null || result.containsKey(ruleId)) {
                continue;
            }
            SafetyMaskingSpec spec = resolveUsableMaskingSpec(ruleId);
            if (spec != null) {
                result.put(ruleId, spec);
            }
        }
        return result;
    }

    /**
     * 删除或停用前锁定规则并检查字段权限、静态任务引用。
     */
    @Override
    public void assertNotReferenced(Long ruleId) {
        Long fieldRefs = fieldPermissionMapper.countActiveMaskByRuleId(ruleId);
        Long taskRefs = staticTaskMapper.countByRuleId(ruleId);
        if ((fieldRefs != null && fieldRefs > 0) || (taskRefs != null && taskRefs > 0)) {
            throw exception(RULE_REFERENCED);
        }
    }

    /**
     * 锁定脱敏规则的规则。
     */
    private SafetyRuleDO lockRule(Long id) {
        SafetyRuleDO rule = id == null ? null : ruleMapper.selectForUpdate(id);
        if (rule == null) {
            throw exception(RULE_NOT_EXISTS);
        }
        return rule;
    }

    /**
     * 校验脱敏规则的无分类引用，不满足约束时拒绝当前操作。
     */
    private void assertNoCategoryReference(Long id) {
        if (categoryMapper.countByRule(id) > 0) {
            throw exception(RULE_REFERENCED);
        }
    }

    /** 分类表单的规则并发凭证，包含未发布新配置版本的元信息修改；客户端不做时间往返。 */
    public static String revisionToken(SafetyRuleDO rule) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("id", rule.getId());
        content.put("versionId", rule.getCurrentVerId());
        content.put("ruleName", rule.getRuleName());
        content.put("methodCode", rule.getMethodCode());
        content.put("status", rule.getStatus());
        content.put("sort", rule.getSort());
        content.put("description", rule.getDescription());
        content.put("updateTime", rule.getUpdateTime() == null ? null : rule.getUpdateTime().toString());
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(content));
    }

}

package cn.iocoder.yudao.module.governance.service.rule;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleWorkbenchMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleWorkbenchRow;
import cn.iocoder.yudao.module.governance.service.category.SafetyCategoryMaskService;
import cn.iocoder.yudao.module.governance.service.category.SafetyCategoryServiceImpl;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.type.TypeReference;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 规则工作台只编排安全投影和分类关联，规则发布及执行复用原治理能力。 */
@Service
@Validated
public class SafetyRuleWorkbenchService {

    @Resource
    private SafetyRuleWorkbenchMapper workbenchMapper;

    @Resource
    private SafetyCategoryMapper categoryMapper;

    @Resource
    private CategoryDataMapper directoryMapper;

    @Resource
    private SafetyRuleMapper ruleMapper;

    @Resource
    private SafetyRuleService ruleService;

    @Resource
    private SafetyCategoryMaskService categoryMasks;

    @Resource
    private AdminUserApi users;

    @Resource
    private cn.iocoder.yudao.module.asset.api.sensitive.AssetSensitivePolicyApi sensitiveAssets;

    /**
     * 获得展示脱敏规则分页。
     */
    public PageResult<SafetyRuleWorkbenchRespVO> page(@Valid SafetyRuleWorkbenchPageReqVO request) {
        long offset = (long) (request.getPageNo() - 1) * request.getPageSize();
        List<SafetyRuleWorkbenchRow> rows = workbenchMapper.selectPage(request, offset);
        var result = rows.stream().map(this::project).toList();
        enrichActors(rows, result);
        return new PageResult<>(result, workbenchMapper.count(request));
    }

    /**
     * 获得展示脱敏规则详情。
     */
    public SafetyRuleWorkbenchRespVO get(Long id) {
        var row = row(id);
        var result = project(row);
        enrichActors(List.of(row), List.of(result));
        return result;
    }

    /**
     * 新建展示脱敏规则并关联分类。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(@Valid SafetyRuleWorkbenchSaveReqVO request) {
        if (request.getId() != null || request.getRuleVersionId() != null || request.getRuleRevisionToken() != null) {
            throw exception(SAFETY_MASK_INVALID, "新增不能指定已有规则版本");
        }
        categoryMapper.lockDefinitions();
        var category = categoryMapper.selectForUpdate(request.getCategoryId());
        if (category == null) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        SafetyCategoryServiceImpl.checkRevision(request.getCategoryRevision(), category.getRevision());
        if (category.getRuleId() != null) {
            throw exception(SAFETY_MASK_INVALID, "该分类已经配置脱敏规则");
        }
        categoryMasks.save(maskRequest(request));
        return categoryMapper.selectById(category.getId()).getRuleId();
    }

    /**
     * 修改展示脱敏规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(@Valid SafetyRuleWorkbenchSaveReqVO request) {
        categoryMapper.lockDefinitions();
        var row = row(request.getId());
        if (!Objects.equals(row.getCategoryId(), request.getCategoryId())) {
            throw exception(SAFETY_MASK_INVALID, "修改规则时不能改变数据分类");
        }
        categoryMasks.save(maskRequest(request));
    }

    /**
     * 修改展示脱敏规则状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(@Valid SafetyRuleWorkbenchStatusReqVO request) {
        categoryMapper.lockDefinitions();
        var row = row(request.getId());
        lockCategory(row, request.getCategoryRevision());
        var save = new SafetyRuleSaveReqVO();
        save.setId(row.getId());
        save.setRuleName(row.getRuleName());
        save.setMethodCode("MASK");
        save.setSort(row.getSort());
        save.setStatus(request.getStatus());
        save.setDescription(row.getDescription());
        save.setConfig(config(row));
        save.setExpectedVersionId(request.getRuleVersionId());
        save.setExpectedRevisionToken(request.getRuleRevisionToken());
        // 完整保留真实配置，仅修改状态；原版本服务会校验当前版本和实际执行引用。
        ruleService.updateRule(save);
    }

    /**
     * 删除展示脱敏规则并解除自身分类关联。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(@Valid SafetyRuleWorkbenchVersionReqVO request) {
        categoryMapper.lockDefinitions();
        deleteLocked(request);
    }

    /**
     * 批量删除展示脱敏规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteList(@Valid SafetyRuleWorkbenchBatchReqVO request) {
        categoryMapper.lockDefinitions();
        var ids = new HashSet<Long>();
        for (var item : request.getItems()) {
            if (!ids.add(item.getId())) {
                throw exception(SAFETY_MASK_INVALID, "批量记录不能重复");
            }
        }
        for (var item : request.getItems().stream().sorted(Comparator.comparing(SafetyRuleWorkbenchVersionReqVO::getId))
                .toList()) {
            deleteLocked(item);
        }
    }

    /**
     * 删除已锁定。
     */
    private void deleteLocked(SafetyRuleWorkbenchVersionReqVO request) {
        var row = row(request.getId());
        var category = lockCategory(row, request.getCategoryRevision());
        var locked = ruleMapper.selectForUpdate(row.getId());
        if (locked == null || !Objects.equals(locked.getCurrentVerId(), request.getRuleVersionId())
                || !Objects.equals(SafetyRuleServiceImpl.revisionToken(locked), request.getRuleRevisionToken())) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
        if (!Objects.equals(locked.getStatus(), (short) 1)) {
            throw exception(SAFETY_MASK_INVALID, "启用的规则不能删除，请先停用");
        }
        // 由明确管理操作解除当前分类关联；真实字段权限和静态任务引用仍由原规则服务阻止删除。
        if (categoryMapper.update(null,
                SafetyCategoryServiceImpl.changed(category).set(SafetyCategoryDO::getRuleId, null)) != 1) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
        ruleService.deleteRule(row.getId());
    }

    /**
     * 锁定分类。
     */
    private SafetyCategoryDO lockCategory(SafetyRuleWorkbenchRow row, Long revision) {
        var category = categoryMapper.selectForUpdate(row.getCategoryId());
        if (category == null || !Objects.equals(category.getRuleId(), row.getId())) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
        SafetyCategoryServiceImpl.checkRevision(revision, category.getRevision());
        return category;
    }

    /**
     * 组装单条脱敏规则工作台数据。
     */
    private SafetyRuleWorkbenchRow row(Long id) {
        var row = id == null ? null : workbenchMapper.get(id);
        if (row == null) {
            throw exception(RULE_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 将工作台输入转换为脱敏规则保存请求。
     */
    private static SafetyCategoryMaskReqVO maskRequest(SafetyRuleWorkbenchSaveReqVO request) {
        var mask = BeanUtils.toBean(request, SafetyCategoryMaskReqVO.class);
        mask.setId(request.getCategoryId());
        mask.setRevision(request.getCategoryRevision());
        return mask;
    }

    /**
     * 获得明确绑定此规则的真实资产敏感记录。
     */
    public PageResult<cn.iocoder.yudao.module.asset.api.sensitive.dto.AssetSensitiveFactRespDTO> sensitivePage(
            @Valid cn.iocoder.yudao.module.asset.api.sensitive.dto.AssetRuleSensitivePageReqDTO request) {
        row(request.getRuleId());
        return sensitiveAssets.getRulePage(request).getCheckedData();
    }

    /**
     * 获得规则工作台分类树及实际可选配置。
     */
    public SafetyRuleWorkbenchOptionsRespVO options() {
        var result = new SafetyRuleWorkbenchOptionsRespVO();
        result.setDirectories(BeanUtils.toBean(directoryMapper.selectList(), CategoryDataRespVO.class));
        var categories = categoryMapper.selectList();
        var projected = BeanUtils.toBean(categories, SafetyCategoryRespVO.class);
        for (int i = 0; i < projected.size(); i++) {
            projected.get(i).setConfigured(categories.get(i).getRuleId() != null);
        }
        result.setCategories(projected);
        result.setApplicationScenes(
                List.of(option("1", "数据资产", false), option("2", "数据查询", false), option("3", "数据服务", true)));
        result.setMaskTypes(List.of(option("1", "底层脱敏", true), option("2", "展示脱敏", false)));
        result.setReplaceRules(List.of(option("1", "自定义掩码", false), option("2", "自定义映射", true)));
        return result;
    }

    /**
     * 将脱敏规则工作台记录投影为工作台展示信息。
     */
    private SafetyRuleWorkbenchRespVO project(SafetyRuleWorkbenchRow row) {
        Map<String, Object> config = config(row);
        var result = BeanUtils.toBean(row, SafetyRuleWorkbenchRespVO.class);
        result.setRuleVersionId(row.getCurrentVerId());
        result.setRuleRevisionToken(SafetyRuleServiceImpl.revisionToken(row));
        result.setApplicationScene(((List<?>) config.get("applicationScene")).stream().map(String::valueOf).toList());
        result.setMaskType((String) config.get("maskType"));
        result.setReplaceRule((String) config.get("replaceRule"));
        result.setReplaceContent((String) config.get("maskChar"));
        result.setIntervalList(((List<?>) config.get("intervals")).stream().map(raw -> {
            Map<?, ?> range = (Map<?, ?>) raw;
            var item = new SafetyCategoryMaskReqVO.Interval();
            item.setStartNum(Integer.valueOf(String.valueOf(range.get("start"))));
            item.setEndNum(Integer.valueOf(String.valueOf(range.get("end"))));
            return item;
        }).toList());
        return result;
    }

    /**
     * 组装脱敏规则工作台配置。
     */
    private static Map<String, Object> config(SafetyRuleWorkbenchRow row) {
        if (row.getConfigJson() == null || !Objects.equals(row.getConfigChecksum(),
                DigestUtil.sha256Hex(row.getConfigJson()))) {
            throw exception(RULE_VERSION_UNAVAILABLE);
        }
        Map<String, Object> config = JsonUtils.parseObject(row.getConfigJson(), new TypeReference<>() {
        });
        SafetyRuleConfigValidator.validate("MASK", config, List.of());
        if (!(config.get("applicationScene") instanceof List<?>) || !"2".equals(config.get("maskType")) || !"1".equals(
                config.get("replaceRule"))) {
            throw exception(RULE_VERSION_UNAVAILABLE);
        }
        return config;
    }

    /**
     * 补充脱敏规则工作台的操作人。
     */
    private void enrichActors(List<SafetyRuleWorkbenchRow> rows, List<SafetyRuleWorkbenchRespVO> result) {
        Set<Long> ids = new HashSet<>();
        for (var row : rows) {
            for (String actor : Arrays.asList(row.getCreator(), row.getUpdater())) {
                try {
                    if (actor != null) {
                        ids.add(Long.valueOf(actor));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Map<Long, AdminUserRespDTO> people = ids.isEmpty() ? Map.of() : users.getUserMap(ids);
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var target = result.get(i);
            target.setCreatorName(
                    StrUtil.isNotBlank(row.getSourceCreatorName()) ? row.getSourceCreatorName() : actor(people,
                            row.getCreator()));
            target.setUpdaterName(
                    StrUtil.isNotBlank(row.getSourceUpdaterName()) ? row.getSourceUpdaterName() : actor(people,
                            row.getUpdater()));
            if (StrUtil.isNotBlank(row.getSourceCreatorName()) && row.getSourceCreateTime() != null) {
                target.setCreateTime(row.getSourceCreateTime());
            }
            if (StrUtil.isNotBlank(row.getSourceUpdaterName()) && row.getSourceUpdateTime() != null) {
                target.setUpdateTime(row.getSourceUpdateTime());
            }
        }
    }

    /**
     * 读取脱敏规则工作台当前操作人的身份信息。
     */
    private static String actor(Map<Long, AdminUserRespDTO> people, String id) {
        try {
            var person = id == null ? null : people.get(Long.valueOf(id));
            return person == null ? id : person.getNickname();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 组装单条脱敏规则工作台可选项。
     */
    private static SafetyRuleWorkbenchOptionsRespVO.Option option(String value, String label, boolean disabled) {
        var option = new SafetyRuleWorkbenchOptionsRespVO.Option();
        option.setValue(value);
        option.setLabel(label);
        option.setDisabled(disabled);
        return option;
    }
}

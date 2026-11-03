package cn.iocoder.yudao.module.governance.service.category;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyIdentifyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 平台共享分类定义；仅管理定义，不授予资产读取或脱敏执行权限。 */
@Service
@Validated
public class SafetyCategoryServiceImpl implements SafetyCategoryService {

    @Resource
    private SafetyCategoryMapper categoryMapper;

    @Resource
    private CategoryDataMapper directoryMapper;

    @Resource
    private SafetyLevelMapper levelMapper;

    @Resource
    private SafetyIdentifyRuleMapper identifyRuleMapper;

    @Resource
    private SafetyFieldTagMapper fieldTagMapper;

    /**
     * 创建数据安全--数据分类信息
     *
     *  @param request 创建信息
     *  @return 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCategory(SafetyCategorySaveReqVO request) {
        categoryMapper.lockDefinitions();
        validateDefinition(request, null);
        if (request.getRuleId() != null) {
            throw exception(SAFETY_CATEGORY_INVALID, "脱敏规则须通过专用配置入口保存");
        }
        SafetyCategoryDO row = BeanUtils.toBean(request, SafetyCategoryDO.class);
        row.setId(null);
        row.setName(request.getName().trim());
        row.setSort(request.getSort() == null ? 0 : request.getSort());
        row.setRevision(1L);
        categoryMapper.insert(row);
        return row.getId();
    }

    /**
     * 更新数据安全--数据分类信息
     *
     *  @param request 更新信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(SafetyCategorySaveReqVO request) {
        categoryMapper.lockDefinitions();
        SafetyCategoryDO current = lock(request.getId(), request.getRevision());
        validateDefinition(request, current.getId());
        if (request.getRuleId() != null && !Objects.equals(request.getRuleId(), current.getRuleId())) {
            throw exception(SAFETY_CATEGORY_INVALID, "不能替换其他分类的脱敏规则");
        }
        var update = changed(current)
                .set(SafetyCategoryDO::getCategoryId, request.getCategoryId())
                .set(SafetyCategoryDO::getName, request.getName().trim())
                .set(SafetyCategoryDO::getShortName, StrUtil.emptyToNull(request.getShortName()))
                .set(SafetyCategoryDO::getDataLevelId, request.getDataLevelId())
                .set(SafetyCategoryDO::getPriority, request.getPriority())
                .set(SafetyCategoryDO::getStatus, request.getStatus())
                .set(SafetyCategoryDO::getDescription, request.getDescription());
        if (request.getSort() != null) {
            update.set(SafetyCategoryDO::getSort, request.getSort());
        }
        if (request.getRemark() != null) {
            update.set(SafetyCategoryDO::getRemark, request.getRemark());
        }
        update(current, update);
    }

    /**
     * 修改分类启停状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(SafetyCategoryStatusReqVO request) {
        categoryMapper.lockDefinitions();
        SafetyCategoryDO current = lock(request.getId(), request.getRevision());
        update(current, changed(current).set(SafetyCategoryDO::getStatus, request.getStatus()));
    }

    /**
     * 批量指定数据分级。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignLevel(SafetyCategoryBatchLevelReqVO request) {
        categoryMapper.lockDefinitions();
        validateLevel(request.getDataLevelId());
        List<SafetyCategoryDO> rows = lockBatch(request.getItems());
        for (var row : rows) {
            update(row, changed(row).set(SafetyCategoryDO::getDataLevelId, request.getDataLevelId()));
        }
    }

    /**
     * 删除数据安全--数据分类信息
     *
     *  @param id 编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategory(Long id, Long revision) {
        categoryMapper.lockDefinitions();
        SafetyCategoryDO current = lock(id, revision);
        validateNoReferences(current);
        update(current, changed(current).setSql("deleted=1"));
    }

    /**
     * 批量删除数据安全--数据分类信息
     *
     * @param request 批量删除请求，包含分类编号与预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCategoryList(SafetyCategoryBatchReqVO request) {
        categoryMapper.lockDefinitions();
        List<SafetyCategoryDO> rows = lockBatch(request.getItems());
        rows.forEach(this::validateNoReferences);
        for (var row : rows) {
            update(row, changed(row).setSql("deleted=1"));
        }
    }

    /**
     * 校验安全分类的无引用，不满足约束时拒绝当前操作。
     */
    private void validateNoReferences(SafetyCategoryDO row) {
        // 同库引用可以在事务内验证；跨服务已保存的敏感清单保留其历史显示快照。
        if (row.getRuleId() != null
                || identifyRuleMapper.selectCount(SafetyIdentifyRuleDO::getCategoryId, row.getId()) > 0
                || fieldTagMapper.selectCount(SafetyFieldTagDO::getCategoryId, row.getId()) > 0) {
            throw exception(SAFETY_CATEGORY_REFERENCED);
        }
    }

    /**
     * 锁定安全分类的批次。
     */
    private List<SafetyCategoryDO> lockBatch(List<SafetyCategoryVersionReqVO> items) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw exception(SAFETY_CATEGORY_INVALID, "每批须包含 1 至 100 条记录");
        }
        Set<Long> unique = new HashSet<>();
        for (var item : items) {
            if (item == null || item.getId() == null || !unique.add(item.getId())) {
                throw exception(SAFETY_CATEGORY_INVALID, "不能包含空记录或重复编号");
            }
        }
        return items.stream().sorted(Comparator.comparing(SafetyCategoryVersionReqVO::getId))
                .map(item -> lock(item.getId(), item.getRevision())).toList();
    }

    /**
     * 校验安全分类的定义，不满足约束时拒绝当前操作。
     */
    private void validateDefinition(SafetyCategorySaveReqVO request, Long excludedId) {
        CategoryDataDO directory = request.getCategoryId() == null ? null : directoryMapper.selectById(
                request.getCategoryId());
        if (directory == null) {
            throw exception(CATEGORY_DATA_NOT_EXISTS);
        }
        validateLevel(request.getDataLevelId());
        var same = categoryMapper.selectOne(new LambdaQueryWrapper<SafetyCategoryDO>()
                .eq(SafetyCategoryDO::getCategoryId, request.getCategoryId())
                .eq(SafetyCategoryDO::getName, request.getName().trim())
                .ne(excludedId != null, SafetyCategoryDO::getId, excludedId).last("LIMIT 1"));
        if (same != null) {
            throw exception(SAFETY_CATEGORY_DUPLICATE);
        }
    }

    /**
     * 校验安全分类的等级，不满足约束时拒绝当前操作。
     */
    private void validateLevel(Integer id) {
        var level = id == null ? null : levelMapper.selectById(id.longValue());
        if (level == null || !Objects.equals(level.getStatus(), (short) 0)) {
            throw exception(SAFETY_CATEGORY_INVALID, "数据分级不存在或已停用");
        }
    }

    /**
     * 读取并锁定安全分类记录，供事务内更新使用。
     */
    private SafetyCategoryDO lock(Long id, Long revision) {
        SafetyCategoryDO row = id == null ? null : categoryMapper.selectForUpdate(id);
        if (row == null) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        checkRevision(revision, row.getRevision());
        return row;
    }

    /**
     * 检查安全分类当前修订号与请求预期一致。
     */
    public static void checkRevision(Long expected, Long actual) {
        if (expected == null || expected <= 0 || !Objects.equals(expected, actual)) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
    }

    /** 所有本地写入都清除来源修改者，读取时才能展示真实本地操作人。 */
    public static LambdaUpdateWrapper<SafetyCategoryDO> changed(SafetyCategoryDO row) {
        return new LambdaUpdateWrapper<SafetyCategoryDO>()
                .eq(SafetyCategoryDO::getId, row.getId()).eq(SafetyCategoryDO::getRevision, row.getRevision())
                .set(SafetyCategoryDO::getRevision, row.getRevision() + 1)
                .set(SafetyCategoryDO::getSourceUpdaterName, null).set(SafetyCategoryDO::getSourceUpdateTime, null)
                .set(SafetyCategoryDO::getUpdater, String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .set(SafetyCategoryDO::getUpdateTime, LocalDateTime.now());
    }

    /**
     * 校验目标和输入后更新安全分类记录。
     */
    private void update(SafetyCategoryDO row, LambdaUpdateWrapper<SafetyCategoryDO> update) {
        if (categoryMapper.update(null, update) != 1) {
            throw exception(SAFETY_CATEGORY_CONFLICT);
        }
    }

    /**
     * 获得数据安全--数据分类信息
     *
     *  @param id 编号
     *  @return 数据安全--数据分类信息
     */
    @Override
    public SafetyCategoryDO getCategory(Long id) {
        SafetyCategoryDO row = categoryMapper.selectById(id);
        if (row == null) {
            throw exception(CATEGORY_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 获得数据安全--数据分类信息分页
     *
     *  @param request 分页查询
     *  @return 数据安全--数据分类信息分页
     */
    @Override
    public PageResult<SafetyCategoryDO> getCategoryPage(SafetyCategoryPageReqVO request) {
        // 目录选择由服务端展开；旧调用方 categoryIds 仍只是普通筛选值，不代表权限范围。
        if (request.getCategoryId() != null) {
            Set<Long> descendants = new LinkedHashSet<>();
            descendants.add(request.getCategoryId());
            List<CategoryDataDO> directories = directoryMapper.selectList();
            boolean changed;
            do {
                changed = false;
                for (var directory : directories) {
                    if (descendants.contains(directory.getParentId())) {
                        changed |= descendants.add(directory.getId());
                    }
                }
            } while (changed);
            request.setCategoryIds(new ArrayList<>(descendants));
        }
        return categoryMapper.selectPage(request);
    }
}

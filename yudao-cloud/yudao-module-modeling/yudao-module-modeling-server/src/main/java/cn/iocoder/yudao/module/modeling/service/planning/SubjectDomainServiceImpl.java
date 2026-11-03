package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.HashSet;

import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 主题域 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class SubjectDomainServiceImpl implements SubjectDomainService {

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.ModelPlanningReferenceGuard referenceGuard;

    @Resource
    private SubjectDomainMapper subjectDomainMapper;

    @Resource
    private DataDomainMapper dataDomainMapper;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 创建主题域
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Long createSubjectDomain(SubjectDomainSaveReqVO createReqVO) {
        validateLeader(createReqVO);
        lockDataDomain(createReqVO.getDataDomainId(), true);
        validateParent(null, createReqVO.getParentId(), createReqVO.getDataDomainId());
        validateUnique(createReqVO);
        SubjectDomainDO subjectDomain = BeanUtils.toBean(createReqVO, SubjectDomainDO.class);
        if (subjectDomain.getStatus() == null) {
            subjectDomain.setStatus(CommonStatusEnum.DISABLE.getStatus());
        }
        try {
            subjectDomainMapper.insert(subjectDomain);
        } catch (DuplicateKeyException e) {
            throw exception(SUBJECT_DOMAIN_CODE_DUPLICATE);
        }
        return subjectDomain.getId();
    }

    /**
     * 更新主题域
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void updateSubjectDomain(SubjectDomainSaveReqVO updateReqVO) {
        validateLeader(updateReqVO);
        lockDataDomain(updateReqVO.getDataDomainId(), true);
        SubjectDomainDO current = validateSubjectDomainExists(updateReqVO.getId());
        if (!Objects.equals(current.getDataDomainId(), updateReqVO.getDataDomainId())) {
            throw exception(SUBJECT_DOMAIN_BOARD_IMMUTABLE);
        }
        requireDisabled(current);
        validateParent(updateReqVO.getId(), updateReqVO.getParentId(), updateReqVO.getDataDomainId());
        validateUnique(updateReqVO);
        try {
            subjectDomainMapper.updateById(BeanUtils.toBean(updateReqVO, SubjectDomainDO.class));
        } catch (DuplicateKeyException e) {
            throw exception(SUBJECT_DOMAIN_CODE_DUPLICATE);
        }
    }

    /**
     * 更新主题域状态
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSubjectDomainStatus(Long id, Integer status) {
        SubjectDomainDO current = validateSubjectDomainExists(id);
        lockDataDomain(current.getDataDomainId(), false);
        validateSubjectDomainExists(id);
        subjectDomainMapper.update(null, Wrappers.lambdaUpdate(SubjectDomainDO.class)
                .set(SubjectDomainDO::getStatus, status)
                .set(SubjectDomainDO::getUpdater, String.valueOf(
                        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()))
                .set(SubjectDomainDO::getUpdateTime, java.time.LocalDateTime.now())
                .eq(SubjectDomainDO::getId, id));
    }

    /**
     * 删除主题域（存在子级不可删除）
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSubjectDomain(Long id) {
        SubjectDomainDO current = validateSubjectDomainExists(id);
        lockDataDomain(current.getDataDomainId(), false);
        requireDisabled(validateSubjectDomainExists(id));
        referenceGuard.requireSubjectDomainUnused(id);
        if (subjectDomainMapper.selectCountByParentId(id) > 0) {
            throw exception(SUBJECT_DOMAIN_EXITS_CHILDREN);
        }
        subjectDomainMapper.deleteById(id);
    }

    /**
     * 获得主题域
     *
     * @param id 记录编号
     * @return 主题域
     */
    @Override
    public SubjectDomainDO getSubjectDomain(Long id) {
        return validateSubjectDomainExists(id);
    }

    /**
     * 获得主题域列表（树形数据平铺返回）
     *
     * @param listReqVO 列表请求参数
     * @return 主题域列表（树形数据平铺返回）结果集合
     */
    @Override
    public List<SubjectDomainDO> getSubjectDomainList(SubjectDomainListReqVO listReqVO) {
        return subjectDomainMapper.selectList(listReqVO);
    }

    /**
     * 校验主题域存在性。
     *
     * @param id 记录编号
     * @return 主题域
     */
    private SubjectDomainDO validateSubjectDomainExists(Long id) {
        SubjectDomainDO subjectDomain = subjectDomainMapper.selectById(id);
        if (subjectDomain == null) {
            throw exception(SUBJECT_DOMAIN_NOT_EXISTS);
        }
        return subjectDomain;
    }

    /**
     * 校验父级：存在性 + 不能选自己或自己的子孙
     */
    private void validateParent(Long id, Long parentId, Long dataDomainId) {
        int depth = 1;
        var visited = new HashSet<Long>();
        Long currentId = parentId;
        while (currentId != null && !Objects.equals(currentId, SubjectDomainDO.PARENT_ID_ROOT)) {
            if (Objects.equals(currentId, id) || !visited.add(currentId)) {
                throw exception(SUBJECT_DOMAIN_PARENT_ERROR);
            }
            SubjectDomainDO parent = subjectDomainMapper.selectById(currentId);
            if (parent == null) {
                throw exception(SUBJECT_DOMAIN_PARENT_NOT_EXITS);
            }
            if (!Objects.equals(dataDomainId, parent.getDataDomainId())) {
                throw exception(SUBJECT_DOMAIN_BOARD_MISMATCH);
            }
            currentId = parent.getParentId();
            depth++;
        }
        if (depth + subtreeDepth(id, dataDomainId) - 1 > 5) {
            throw exception(SUBJECT_DOMAIN_DEPTH_EXCEEDED);
        }
    }

    /**
     * 获取主题域的子树深度。
     *
     * @param id 记录编号
     * @param dataDomainId 数据域编号
     * @return 子树深度数值
     */
    private int subtreeDepth(Long id, Long dataDomainId) {
        if (id == null) {
            return 1;
        }
        var rows = subjectDomainMapper.selectList(new LambdaQueryWrapperX<SubjectDomainDO>()
                .eq(SubjectDomainDO::getDataDomainId, dataDomainId));
        var current = java.util.Set.of(id);
        var visited = new HashSet<Long>();
        int depth = 0;
        while (!current.isEmpty()) {
            if (++depth > 5 || !java.util.Collections.disjoint(visited, current)) {
                throw exception(SUBJECT_DOMAIN_DEPTH_EXCEEDED);
            }
            visited.addAll(current);
            var parents = current;
            current = rows.stream().filter(row -> parents.contains(row.getParentId()))
                    .map(SubjectDomainDO::getId).collect(java.util.stream.Collectors.toSet());
        }
        return depth;
    }

    /**
     * 校验禁用状态。
     *
     * @param row 当前行数据
     */
    private void requireDisabled(SubjectDomainDO row) {
        if (CommonStatusEnum.isEnable(row.getStatus())) {
            throw exception(SUBJECT_DOMAIN_ENABLED);
        }
    }

    /**
     * 锁定数据域。
     *
     * @param id 记录编号
     * @param requireEnabled 需要启用状态
     */
    private void lockDataDomain(Long id, boolean requireEnabled) {
        DataDomainDO board = dataDomainMapper.selectForUpdate(id);
        if (board == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
        if (requireEnabled && !Objects.equals(board.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
            throw exception(MODEL_DATA_DOMAIN_DISABLED);
        }
    }

    /**
     * 校验主节点。
     *
     * @param request 请求参数
     */
    private void validateLeader(SubjectDomainSaveReqVO request) {
        adminUserApi.validateUserList(List.of(request.getLeaderUserId())).checkError();
        var leader = adminUserApi.getUser(request.getLeaderUserId()).getCheckedData();
        request.setLeaderPhone(leader == null ? null : leader.getMobile());
    }

    /**
     * 校验唯一性。
     *
     * @param request 请求参数
     */
    private void validateUnique(SubjectDomainSaveReqVO request) {
        SubjectDomainDO exists = subjectDomainMapper.selectByDomainAndParentAndName(
                request.getDataDomainId(), request.getParentId(), request.getName());
        if (exists != null && !Objects.equals(exists.getId(), request.getId())) {
            throw exception(SUBJECT_DOMAIN_NAME_DUPLICATE);
        }
        var duplicates = subjectDomainMapper.selectList(new LambdaQueryWrapperX<SubjectDomainDO>()
                .eq(SubjectDomainDO::getDataDomainId, request.getDataDomainId())
                .and(q -> q.apply("lower(code) = lower({0})", request.getCode())
                        .or().apply("lower(abbreviation) = lower({0})", request.getAbbreviation())));
        if (duplicates.stream().anyMatch(row -> !Objects.equals(row.getId(), request.getId()))) {
            throw exception(SUBJECT_DOMAIN_CODE_DUPLICATE);
        }
    }
}

package cn.iocoder.yudao.module.modeling.service.businesscondition;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition.BusinessConditionMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelService;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/** 平台内按资源权限可见的业务限定；只维护定义，不执行自由表达式。 */
@Service
@Validated
public class BusinessConditionServiceImpl implements BusinessConditionService {

    @Resource
    private BusinessConditionMapper mapper;

    @Resource
    private LogicModelMapper modelMapper;

    @Resource
    private IndicatorModelService indicatorService;

    @Resource
    private ModelHierarchyResolver hierarchy;

    @Resource
    private AdminUserApi userApi;

    @Resource
    private SecurityFrameworkService security;

    @Resource
    private TransactionTemplate transactions;

    @Resource
    private BusinessEntityMapper entityMapper;

    /**
     * 创建业务限定定义。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long create(BusinessConditionSaveReqVO request) {
        validateOwner(request.getOwnerUserId());
        requireSourcePermission();
        return transactions.execute(transaction -> {
            mapper.lockReferences();
            BusinessConditionDO row = prepare(request);
            row.setId(null);
            row.setStatus(desiredStatus(request.getStatus()));
            row.setRevision(0L);
            validateDefinition(row);
            uniqueCode(row);
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException ignored) {
                throw exception(BUSINESS_CONDITION_CODE_DUPLICATE);
            }
            return row.getId();
        });
    }

    /**
     * 修改未启用的业务限定。
     *
     * @param request 请求参数
     */
    @Override
    public void update(BusinessConditionSaveReqVO request) {
        validateOwner(request.getOwnerUserId());
        requireSourcePermission();
        transactions.executeWithoutResult(transaction -> {
            mapper.lockReferences();
            BusinessConditionDO old = lock(request.getId(), request.getExpectedRevision());
            requireDraft(old);
            BusinessConditionDO row = prepare(request);
            row.setStatus(desiredStatus(request.getStatus()));
            row.setRevision(old.getRevision() + 1);
            validateDefinition(row);
            uniqueCode(row);
            try {
                mapper.updateById(row);
            } catch (DuplicateKeyException ignored) {
                throw exception(BUSINESS_CONDITION_CODE_DUPLICATE);
            }
        });
    }

    /**
     * 删除未启用的业务限定。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    public void delete(Long id, Long expectedRevision) {
        transactions.executeWithoutResult(transaction -> {
            mapper.lockReferences();
            BusinessConditionDO row = lock(id, expectedRevision);
            requireDraft(row);
            mapper.deleteById(id);
        });
    }

    /**
     * 启用或停用业务限定。
     *
     * @param request 请求参数
     */
    @Override
    public void updateStatus(BusinessConditionStatusReqVO request) {
        transactions.executeWithoutResult(transaction -> {
            mapper.lockReferences();
            BusinessConditionDO row = lock(request.getId(), request.getExpectedRevision());
            if (Objects.equals(row.getStatus(), request.getStatus())) {
                return;
            }
            if (BusinessConditionDO.ACTIVE.equals(request.getStatus())) {
                validateDefinition(row);
            }
            row.setStatus(request.getStatus());
            row.setRevision(row.getRevision() + 1);
            row.setUpdater(null);
            row.setUpdateTime(null);
            mapper.updateById(row);
        });
    }

    /**
     * 获得业务限定详情。
     *
     * @param id 记录编号
     * @return 业务限定响应数据
     */
    @Override
    public BusinessConditionRespVO get(Long id) {
        BusinessConditionDO row = id == null ? null : mapper.selectById(id);
        if (row == null) {
            throw exception(BUSINESS_CONDITION_NOT_EXISTS);
        }
        return responses(List.of(row)).getFirst();
    }

    /**
     * 分页查询业务限定。
     *
     * @param request 请求参数
     * @return 分页查询业务限定结果
     */
    @Override
    public PageResult<BusinessConditionRespVO> page(BusinessConditionPageReqVO request) {
        var page = mapper.selectPage(request);
        return new PageResult<>(responses(page.getList()), page.getTotal());
    }

    /**
     * 校验所有者。
     *
     * @param ownerId 所有者编号
     */
    private void validateOwner(Long ownerId) {
        // 用户 RPC 在持锁事务外完成，避免外部调用占用引用锁。
        if (ownerId != null) {
            userApi.validateUserList(List.of(ownerId)).checkError();
        }
    }

    /**
     * 校验来源权限。
     */
    private void requireSourcePermission() {
        if (!security.hasPermission("modeling:model:query")) {
            throw exception(INDICATOR_SOURCE_FORBIDDEN);
        }
    }

    /**
     * 准备业务限定所需的配置和状态。
     *
     * @param request 请求参数
     * @return 业务限定
     */
    private BusinessConditionDO prepare(BusinessConditionSaveReqVO request) {
        var row = BeanUtils.toBean(request, BusinessConditionDO.class);
        row.setCode(request.getCode().trim());
        row.setName(request.getName().trim());
        row.setExpression(request.getExpression().trim());
        return row;
    }

    /**
     * 获取业务限定的预期状态。
     *
     * @param status 目标状态
     * @return 业务限定的预期状态文本
     */
    private String desiredStatus(String status) {
        String value = status == null ? BusinessConditionDO.DRAFT : status;
        if (!Set.of(BusinessConditionDO.DRAFT, BusinessConditionDO.ACTIVE).contains(value)) {
            throw exception(BUSINESS_CONDITION_EXPRESSION_INVALID);
        }
        return value;
    }

    /**
     * 校验定义。
     *
     * @param row 当前行数据
     */
    private void validateDefinition(BusinessConditionDO row) {
        if (!"BASIC".equals(row.getConfigType()) || !"UNIFIED".equals(row.getLogicMode())) {
            throw exception(BUSINESS_CONDITION_UNSUPPORTED);
        }
        if (row.getExpression() == null || row.getExpression().isBlank() || row.getExpression().length() > 4000) {
            throw exception(BUSINESS_CONDITION_EXPRESSION_INVALID);
        }
        hierarchy.validateModelDataDomain(row.getDataDomainId());
        hierarchy.validateSubjectDomain(row.getSubjectDomainId(), null, row.getDataDomainId());
        // 复用指标定义对有效模型、已发布当前设计和稳定字段集合的校验。
        LogicModelDO source = modelMapper.selectById(row.getSourceModelId());
        if (source == null || !Objects.equals(source.getDataDomainId(), row.getDataDomainId())) {
            throw exception(BUSINESS_CONDITION_SOURCE_DOMAIN_INVALID);
        }
        row.setBusinessEntity(indicatorService.validateScopedSource(row.getSourceModelId(),
                row.getDataDomainId(), row.getSubjectDomainId(), row.getEntityId(), row.getDataFreshness()));
    }

    /**
     * 锁定业务限定当前记录。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 业务限定
     */
    private BusinessConditionDO lock(Long id, Long expectedRevision) {
        BusinessConditionDO row = id == null ? null : mapper.selectForUpdate(id);
        if (row == null) {
            throw exception(BUSINESS_CONDITION_NOT_EXISTS);
        }
        if (expectedRevision == null || !Objects.equals(row.getRevision(), expectedRevision)) {
            throw exception(BUSINESS_CONDITION_REVISION_CONFLICT);
        }
        return row;
    }

    /**
     * 校验草稿。
     *
     * @param row 当前行数据
     */
    private void requireDraft(BusinessConditionDO row) {
        if (!BusinessConditionDO.DRAFT.equals(row.getStatus())) {
            throw exception(BUSINESS_CONDITION_NOT_DRAFT);
        }
    }

    /**
     * 校验唯一性编码。
     *
     * @param row 当前行数据
     */
    private void uniqueCode(BusinessConditionDO row) {
        var old = mapper.selectByCodeIgnoreCase(row.getCode());
        if (old != null && !Objects.equals(old.getId(), row.getId())) {
            throw exception(BUSINESS_CONDITION_CODE_DUPLICATE);
        }
    }

    /**
     * 获取业务限定的响应。
     *
     * @param rows 待处理的行数据
     * @return 业务限定的响应结果集合
     */
    private List<BusinessConditionRespVO> responses(List<BusinessConditionDO> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var models = new HashMap<Long, LogicModelDO>();
        var entities = new HashMap<Long, cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO>();
        var entityIds = rows.stream().map(BusinessConditionDO::getEntityId).filter(Objects::nonNull).distinct()
                .toList();
        if (!entityIds.isEmpty()) {
            entityMapper.selectByIds(entityIds).forEach(entity -> entities.put(entity.getId(), entity));
        }
        modelMapper.selectBatchIds(rows.stream().map(BusinessConditionDO::getSourceModelId).distinct().toList())
                .forEach(row -> models.put(row.getId(), row));
        var userIds = new HashSet<Long>();
        rows.forEach(row -> {
            if (row.getOwnerUserId() != null) {
                userIds.add(row.getOwnerUserId());
            }
            Long creator = auditId(row.getCreator()), updater = auditId(row.getUpdater());
            if (creator != null) {
                userIds.add(creator);
            }
            if (updater != null) {
                userIds.add(updater);
            }
        });
        Map<Long, AdminUserRespDTO> users = userIds.isEmpty() ? Map.of() : userApi.getUserMap(userIds);
        return rows.stream().map(row -> {
            var result = BeanUtils.toBean(row, BusinessConditionRespVO.class);
            var entity = entities.get(row.getEntityId());
            if (entity != null) {
                result.setEntityName(entity.getName());
            }
            var model = models.get(row.getSourceModelId());
            if (model != null) {
                result.setSourceModelName(model.getName());
                result.setSourceModelCode(model.getCode());
            }
            var owner = row.getOwnerUserId() == null ? null : users.get(row.getOwnerUserId());
            if (owner != null) {
                result.setOwnerUserName(owner.getNickname());
                result.setOwnerUserPhone(owner.getMobile());
            }
            var creator = auditId(row.getCreator()) == null ? null : users.get(auditId(row.getCreator()));
            if (creator != null) {
                result.setCreatorName(creator.getNickname());
                result.setCreatorPhone(creator.getMobile());
            }
            var updater = auditId(row.getUpdater()) == null ? null : users.get(auditId(row.getUpdater()));
            if (updater != null) {
                result.setUpdaterName(updater.getNickname());
                result.setUpdaterPhone(updater.getMobile());
            }
            // 独立限定尚未接入派生定义，不能据文本相似性伪造引用。
            result.setDerivedIndicatorCount(0L);
            return result;
        }).toList();
    }

    /**
     * 获取业务限定的审计编号。
     *
     * @param value 待处理的值
     * @return 审计编号数值
     */
    private Long auditId(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

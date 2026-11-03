package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.*;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/** 业务实体按平台共享，接口以独立资源权限控制；写操作按板块串行并校验修订号。 */
@Service
@Validated
@Slf4j
public class BusinessEntityServiceImpl implements BusinessEntityService {

    @Resource
    private BusinessEntityMapper entities;

    @Resource
    private BusinessEntityRelationMapper relations;

    @Resource
    private DataDomainMapper boards;

    @Resource
    private SubjectDomainMapper subjects;

    @Resource
    private ModelHierarchyResolver hierarchy;

    @Resource
    private AdminUserApi users;

    @Resource
    private ILogRecordService logRecordService;

    /**
     * 新增业务实体。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(BusinessEntitySaveReqVO request) {
        String phone = validateLeader(request.getLeaderUserId());
        lockBoard(request.getDataDomainId());
        hierarchy.validateModelDataDomain(request.getDataDomainId());
        hierarchy.validateSubjectDomain(request.getSubjectDomainId(), null, request.getDataDomainId());
        validateCode(null, request);
        var edges = BusinessEntityRelationPolicy.validate(null, request,
                entities.selectByBoard(request.getDataDomainId()), relations.selectByBoard(request.getDataDomainId()));
        var row = BeanUtils.toBean(request, BusinessEntityDO.class);
        row.setLeaderPhone(phone);
        row.setStatus(CommonStatusEnum.DISABLE.getStatus());
        row.setRevision(0L);
        try {
            entities.insert(row);
        } catch (DuplicateKeyException e) {
            throw exception(BUSINESS_ENTITY_CODE_DUPLICATE);
        }
        edges.forEach(edge -> {
            edge.setSourceEntityId(row.getId());
            relations.insert(edge);
        });
        return row.getId();
    }

    /**
     * 修改业务实体及关系。
     *
     * @param request 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(BusinessEntityUpdateReqVO request) {
        String phone = validateLeader(request.getLeaderUserId());
        var current = lockEntity(request.getId());
        checkRevision(current, request.getExpectedRevision());
        requireEditable(current);
        if (!Objects.equals(current.getDataDomainId(), request.getDataDomainId())
                || !Objects.equals(current.getEntityType(), request.getEntityType())
                || !Objects.equals(current.getEntitySubtype(), request.getEntitySubtype())) {
            throw exception(BUSINESS_ENTITY_TYPE_IMMUTABLE);
        }
        hierarchy.validateSubjectDomain(request.getSubjectDomainId(), current.getSubjectDomainId(),
                request.getDataDomainId());
        validateCode(current.getId(), request);
        var edges = BusinessEntityRelationPolicy.validate(current.getId(), request,
                entities.selectByBoard(current.getDataDomainId()), relations.selectByBoard(current.getDataDomainId()));
        var row = BeanUtils.toBean(request, BusinessEntityDO.class);
        row.setLeaderPhone(phone);
        row.setRevision(current.getRevision() + 1);
        // 先撤销出边，使切换主题及继承关系在同一事务中保持一致；流程事件数量由提交时约束兜底。
        replaceRelations(current.getId(), List.of());
        try {
            entities.updateById(row);
        } catch (DuplicateKeyException e) {
            throw exception(BUSINESS_ENTITY_CODE_DUPLICATE);
        }
        edges.forEach(relations::insert);
    }

    /**
     * 上线或下线业务实体。
     *
     * @param request 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(BusinessEntityStatusReqVO request) {
        var row = lockEntity(request.getId());
        checkRevision(row, request.getExpectedRevision());
        transition(row, request.getStatus());
    }

    /**
     * 原子批量上线或下线业务实体。
     *
     * @param request 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusBatch(BusinessEntityBatchReqVO request) {
        var rows = lockTargets(request);
        rows.forEach(row -> transition(row, request.getStatus()));
    }

    /**
     * 删除业务实体。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long expectedRevision) {
        var row = lockEntity(id);
        checkRevision(row, expectedRevision);
        requireDeletable(row, Set.of(id));
        replaceRelations(id, List.of());
        entities.deleteById(id);
    }

    /**
     * 原子批量删除业务实体。
     *
     * @param request 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBatch(BusinessEntityBatchReqVO request) {
        var rows = lockTargets(request);
        var ids = rows.stream().map(BusinessEntityDO::getId).collect(Collectors.toSet());
        rows.forEach(row -> requireDeletable(row, ids));
        rows.forEach(row -> replaceRelations(row.getId(), List.of()));
        rows.forEach(row -> entities.deleteById(row.getId()));
    }

    /**
     * 获得业务实体及其关系。
     *
     * @param id 记录编号
     * @return 业务实体响应数据
     */
    @Override
    public BusinessEntityRespVO get(Long id) {
        var row = requireEntity(id);
        var response = present(List.of(row)).getFirst();
        fillRelations(response, relations.selectByBoard(row.getDataDomainId()),
                entities.selectByBoard(row.getDataDomainId()));
        return response;
    }

    /**
     * 获得业务实体分页。
     *
     * @param request 请求参数
     * @return 业务实体分页结果
     */
    @Override
    public PageResult<BusinessEntityRespVO> page(BusinessEntityPageReqVO request) {
        var page = entities.selectPage(request);
        return new PageResult<>(present(page.getList()), page.getTotal());
    }

    /**
     * 先按实体筛选，再组装流程包含关系和分页，避免流程事件跨页后脱离父流程。
     * 未匹配筛选条件的流程不作为上下文插入，其匹配事件保留为根节点。
     */
    @Override
    public PageResult<BusinessEntityRespVO> treePage(BusinessEntityPageReqVO request) {
        var matched = entities.selectList(request);
        if (matched.isEmpty()) {
            return new PageResult<>(List.of(), 0L);
        }
        var boardIds = matched.stream().map(BusinessEntityDO::getDataDomainId).distinct().toList();
        var boardEdges = boardIds.stream().flatMap(id -> relations.selectByBoard(id).stream()).toList();
        var boardEntities = boardIds.stream().flatMap(id -> entities.selectByBoard(id).stream()).toList();
        var rows = present(matched);
        var byId = rows.stream().collect(Collectors.toMap(BusinessEntityRespVO::getId, row -> row));
        var nested = new HashSet<Long>();
        for (var row : rows) {
            fillRelations(row, boardEdges, boardEntities);
            row.setChildren(List.of());
            if (!"PROCESS".equals(row.getEntitySubtype())) {
                continue;
            }
            var children = row.getProcessEventIds().stream().map(byId::get).filter(Objects::nonNull)
                    .filter(child -> "EVENT".equals(child.getEntitySubtype()))
                    .filter(child -> Objects.equals(child.getDataDomainId(), row.getDataDomainId()))
                    .filter(child -> nested.add(child.getId())).toList();
            row.setChildren(children);
        }
        var roots = rows.stream().filter(row -> !nested.contains(row.getId())).toList();
        long offset = (long) (request.getPageNo() - 1) * request.getPageSize();
        if (offset >= roots.size()) {
            return new PageResult<>(List.of(), (long) roots.size());
        }
        int end = (int) Math.min(offset + request.getPageSize(), roots.size());
        return new PageResult<>(roots.subList((int) offset, end), (long) roots.size());
    }

    /**
     * 获得板块实体及流程占用约束。
     *
     * @param dataDomainId 数据域编号
     * @param excludeId 排除编号
     * @return 业务实体选项响应数据
     */
    @Override
    public BusinessEntityOptionsRespVO options(Long dataDomainId, Long excludeId) {
        if (boards.selectById(dataDomainId) == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
        if (excludeId != null && !Objects.equals(requireEntity(excludeId).getDataDomainId(), dataDomainId)) {
            throw exception(BUSINESS_ENTITY_RELATION_INVALID, "实体不属于当前数据板块");
        }
        var result = new BusinessEntityOptionsRespVO();
        result.setEntities(entities.selectByBoard(dataDomainId).stream()
                .filter(row -> !Objects.equals(row.getId(), excludeId))
                .map(row -> BeanUtils.toBean(row, BusinessEntityRespVO.Summary.class)).toList());
        var edges = relations.selectByBoard(dataDomainId).stream()
                .filter(row -> !Objects.equals(row.getSourceEntityId(), excludeId)).toList();
        result.setUnavailableProcessEventIds(ids(edges, "PROCESS_EVENT"));
        result.setUnavailablePredecessorEntityIds(ids(edges, "PREDECESSOR"));
        return result;
    }

    /**
     * 校验业务实体目标状态，上线前复核领域层级和关系规则，再推进修订号并登记审计。
     *
     * @param row 当前行数据
     * @param status 目标状态
     */
    private void transition(BusinessEntityDO row, Integer status) {
        if (status == null || status < 0 || status > 1) {
            throw exception(BUSINESS_ENTITY_STATUS_INVALID);
        }
        if (status.equals(row.getStatus())) {
            return;
        }
        if (CommonStatusEnum.isEnable(status)) {
            hierarchy.validateModelDataDomain(row.getDataDomainId());
            hierarchy.validateSubjectDomain(row.getSubjectDomainId(), null, row.getDataDomainId());
            var request = BeanUtils.toBean(row, BusinessEntitySaveReqVO.class);
            var edges = relations.selectByBoard(row.getDataDomainId());
            var own = edges.stream().filter(r -> Objects.equals(r.getSourceEntityId(), row.getId())).toList();
            request.setInheritEntityId(ids(own, "INHERITANCE").stream().findFirst().orElse(null));
            request.setAssociatedEntityIds(ids(own, "ASSOCIATION"));
            request.setPredecessorEntityIds(ids(own, "PREDECESSOR"));
            request.setProcessEventIds(ids(own, "PROCESS_EVENT"));
            BusinessEntityRelationPolicy.validate(row.getId(), request, entities.selectByBoard(row.getDataDomainId()),
                    edges);
        }
        entities.update(null, Wrappers.lambdaUpdate(BusinessEntityDO.class)
                .set(BusinessEntityDO::getStatus, status)
                .set(BusinessEntityDO::getRevision, row.getRevision() + 1)
                .set(BusinessEntityDO::getUpdater, String.valueOf(SecurityFrameworkUtils.getLoginUserId()))
                .set(BusinessEntityDO::getUpdateTime, LocalDateTime.now())
                .eq(BusinessEntityDO::getId, row.getId()));
        auditStatus(row, status);
    }

    /**
     * 登记业务实体上下线的前后状态及修订，在事务提交后派发操作日志。
     *
     * @param before 变更前状态
     * @param afterStatus 变更后状态
     */
    private void auditStatus(BusinessEntityDO before, Integer afterStatus) {
        String action = CommonStatusEnum.isEnable(afterStatus) ? "上线" : "下线";
        var extra = new LinkedHashMap<String, Object>();
        extra.put("actorId", SecurityFrameworkUtils.getLoginUserId());
        extra.put("entityId", String.valueOf(before.getId()));
        extra.put("beforeStatus", before.getStatus());
        extra.put("afterStatus", afterStatus);
        extra.put("beforeRevision", before.getRevision());
        extra.put("afterRevision", before.getRevision() + 1);
        extra.put("reason", "用户请求" + action);
        extra.put("result", "SUCCESS");
        extra.put("correlation", TracerUtils.getTraceId());
        var record = LogRecord.builder().type("业务实体").subType(action).bizNo(String.valueOf(before.getId()))
                .action(action + "业务实体").extra(JsonUtils.toJsonString(extra)).build();
        // 复用操作日志组件，在本地事务提交后派发，不在持锁期间调用日志 RPC。
        Runnable write = () -> {
            try {
                logRecordService.record(record);
            } catch (RuntimeException failure) {
                log.warn("[auditStatus][实体({}) 操作日志写入失败，异常类型 {}]", before.getId(),
                        failure.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 在本地事务成功提交后触发对应的后续处理。
                 */
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    /**
     * 锁定实体。
     *
     * @param id 记录编号
     * @return 业务实体
     */
    private BusinessEntityDO lockEntity(Long id) {
        var before = requireEntity(id);
        lockBoard(before.getDataDomainId());
        return requireEntity(id);
    }

    /**
     * 锁定目标。
     *
     * @param request 请求参数
     * @return 锁定目标结果集合
     */
    private List<BusinessEntityDO> lockTargets(BusinessEntityBatchReqVO request) {
        if (request.getTargets() == null || request.getTargets().isEmpty() || request.getTargets().size() > 100
                || request.getTargets().stream().anyMatch(Objects::isNull)) {
            throw exception(BUSINESS_ENTITY_REVISION_CONFLICT);
        }
        var expected = new HashMap<Long, Long>();
        for (var target : request.getTargets()) {
            if (target.getId() == null || target.getExpectedRevision() == null
                    || expected.putIfAbsent(target.getId(), target.getExpectedRevision()) != null) {
                throw exception(BUSINESS_ENTITY_REVISION_CONFLICT);
            }
        }
        var before = expected.keySet().stream().map(this::requireEntity).toList();
        before.stream().map(BusinessEntityDO::getDataDomainId).distinct().sorted().forEach(this::lockBoard);
        var rows = expected.keySet().stream().sorted().map(this::requireEntity).toList();
        rows.forEach(row -> checkRevision(row, expected.get(row.getId())));
        return rows;
    }

    /**
     * 锁定板块。
     *
     * @param id 记录编号
     */
    private void lockBoard(Long id) {
        if (boards.selectForUpdate(id) == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
    }

    /**
     * 校验可删除。
     *
     * @param row 当前行数据
     * @param deletingIds 删除中编号集合
     */
    private void requireDeletable(BusinessEntityDO row, Set<Long> deletingIds) {
        requireEditable(row);
        if (relations.selectList(BusinessEntityRelationDO::getTargetEntityId, row.getId()).stream()
                .anyMatch(edge -> !deletingIds.contains(edge.getSourceEntityId()))) {
            throw exception(BUSINESS_ENTITY_REFERENCED);
        }
    }

    /**
     * 校验可编辑。
     *
     * @param row 当前行数据
     */
    private void requireEditable(BusinessEntityDO row) {
        if (CommonStatusEnum.isEnable(row.getStatus())) {
            throw exception(BUSINESS_ENTITY_ONLINE);
        }
    }

    /**
     * 检查修订号。
     *
     * @param row 当前行数据
     * @param revision 修订号
     */
    private void checkRevision(BusinessEntityDO row, Long revision) {
        if (!Objects.equals(row.getRevision(), revision)) {
            throw exception(BUSINESS_ENTITY_REVISION_CONFLICT);
        }
    }

    /**
     * 校验实体。
     *
     * @param id 记录编号
     * @return 业务实体
     */
    private BusinessEntityDO requireEntity(Long id) {
        var row = entities.selectById(id);
        if (row == null) {
            throw exception(BUSINESS_ENTITY_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 校验编码。
     *
     * @param id 记录编号
     * @param request 请求参数
     */
    private void validateCode(Long id, BusinessEntitySaveReqVO request) {
        var existing = entities.selectByCode(request.getDataDomainId(), request.getCode());
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(BUSINESS_ENTITY_CODE_DUPLICATE);
        }
    }

    /**
     * 校验主节点。
     *
     * @param id 记录编号
     * @return 校验主节点文本
     */
    private String validateLeader(Long id) {
        if (id == null) {
            return null;
        }
        users.validateUserList(List.of(id)).checkError();
        var leader = users.getUser(id).getCheckedData();
        return leader == null ? null : leader.getMobile();
    }

    /**
     * 替换关系。
     *
     * @param id 记录编号
     * @param edges 边
     */
    private void replaceRelations(Long id, List<BusinessEntityRelationDO> edges) {
        relations.delete(
                new LambdaQueryWrapperX<BusinessEntityRelationDO>().eq(BusinessEntityRelationDO::getSourceEntityId,
                        id));
        edges.forEach(relations::insert);
    }

    /**
     * 组装业务实体展示数据。
     *
     * @param rows 待处理的行数据
     * @return 组装业务实体展示数据结果集合
     */
    private List<BusinessEntityRespVO> present(List<BusinessEntityDO> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var userIds = new HashSet<Long>();
        rows.forEach(row -> {
            if (row.getLeaderUserId() != null) {
                userIds.add(row.getLeaderUserId());
            }
            var creatorId = userId(row.getCreator());
            if (creatorId != null) {
                userIds.add(creatorId);
            }
            var updaterId = userId(row.getUpdater());
            if (updaterId != null) {
                userIds.add(updaterId);
            }
        });
        var people = userIds.isEmpty() ? Map.<Long, AdminUserRespDTO>of() : users.getUserMap(userIds);
        var boardMap = boards.selectBatchIds(rows.stream().map(BusinessEntityDO::getDataDomainId).distinct().toList())
                .stream().collect(
                        Collectors.toMap(cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO::getId,
                                b -> b));
        var subjectMap = subjects.selectBatchIds(
                        rows.stream().map(BusinessEntityDO::getSubjectDomainId).distinct().toList())
                .stream().collect(Collectors.toMap(
                        cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO::getId, s -> s));
        return rows.stream().map(row -> {
            var response = BeanUtils.toBean(row, BusinessEntityRespVO.class);
            var leader = row.getLeaderUserId() == null ? null : people.get(row.getLeaderUserId());
            var creatorId = userId(row.getCreator());
            var creator = creatorId == null ? null : people.get(creatorId);
            response.setLeaderName(leader == null ? null : leader.getNickname());
            response.setCreatorName(creator == null ? null : creator.getNickname());
            response.setCreatorPhone(creator == null ? null : creator.getMobile());
            var updaterId = userId(row.getUpdater());
            var updater = updaterId == null ? null : people.get(updaterId);
            response.setUpdaterName(updater == null ? null : updater.getNickname());
            var board = boardMap.get(row.getDataDomainId());
            var subject = subjectMap.get(row.getSubjectDomainId());
            response.setDataDomainName(board == null ? null : board.getName());
            response.setSubjectDomainName(subject == null ? null : subject.getName());
            response.setSubjectDomainCode(subject == null ? null : subject.getCode());
            // 本项目尚未建立业务实体与逻辑模型的绑定，不导入源站外部模型计数。
            response.setLogicalModelCount(0L);
            return response;
        }).toList();
    }

    /**
     * 补充关系。
     *
     * @param result 处理结果
     * @param edges 边
     * @param targets 目标
     */
    private static void fillRelations(BusinessEntityRespVO result, List<BusinessEntityRelationDO> edges,
            List<BusinessEntityDO> targets) {
        var own = edges.stream().filter(r -> Objects.equals(r.getSourceEntityId(), result.getId())).toList();
        result.setInheritEntityId(ids(own, "INHERITANCE").stream().findFirst().orElse(null));
        result.setAssociatedEntityIds(ids(own, "ASSOCIATION"));
        result.setPredecessorEntityIds(ids(own, "PREDECESSOR"));
        result.setProcessEventIds(ids(own, "PROCESS_EVENT"));
        var summaries = targets.stream().collect(Collectors.toMap(BusinessEntityDO::getId,
                row -> BeanUtils.toBean(row, BusinessEntityRespVO.Summary.class)));
        result.setInheritedEntity(summaries.get(result.getInheritEntityId()));
        result.setAssociatedEntities(summaries(result.getAssociatedEntityIds(), summaries));
        result.setPredecessorEntities(summaries(result.getPredecessorEntityIds(), summaries));
        result.setProcessEvents(summaries(result.getProcessEventIds(), summaries));
    }

    /**
     * 获取业务实体的汇总。
     *
     * @param ids 记录编号集合
     * @param summaries 汇总
     * @return 业务实体的汇总结果集合
     */
    private static List<BusinessEntityRespVO.Summary> summaries(List<Long> ids,
            Map<Long, BusinessEntityRespVO.Summary> summaries) {
        return ids.stream().map(summaries::get).filter(Objects::nonNull).toList();
    }

    /**
     * 获取业务实体的编号集合。
     *
     * @param edges 边
     * @param type 类型标识
     * @return 业务实体的编号集合结果集合
     */
    private static List<Long> ids(List<BusinessEntityRelationDO> edges, String type) {
        return edges.stream().filter(r -> type.equals(r.getRelationType()))
                .sorted(Comparator.comparing(BusinessEntityRelationDO::getSortOrder))
                .map(BusinessEntityRelationDO::getTargetEntityId).toList();
    }

    /**
     * 获取业务实体的用户编号。
     *
     * @param value 待处理的值
     * @return 用户编号数值
     */
    private static Long userId(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

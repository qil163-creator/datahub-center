package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillReuseReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillReuseSourceRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDraftSnapshotRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDraftRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillOperationDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillDraftRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillOperationMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionRowMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillOperationTypeEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillWorkItemStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 在线填报-草稿服务实现类
 *
 * 并发模型：工作项行上的 serverVersion CAS 是唯一串行化点——第一个操作的
 * CAS 成功即持有行锁直至事务提交，并发批次在此排队或因版本不匹配被拒绝；
 * 草稿行 rowVersion CAS 为同行并发的最终防线（Redis 软锁失效时兜底）
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class FillDraftServiceImpl implements FillDraftService {

    /** 受控客户端生成的行标识格式 */
    private static final Pattern ROW_ID_PATTERN = Pattern.compile("^[A-Za-z0-9\\-]{4,64}$");

    private static final BigDecimal ORDER_GAP = BigDecimal.valueOf(1024);

    private static final int ORDER_SCALE = 10;

    private static final int MAX_PULL_LIMIT = 500;

    @Resource
    private FillWorkItemMapper workItemMapper;

    @Resource
    private FillDraftRowMapper draftRowMapper;

    @Resource
    private FillOperationMapper operationMapper;

    @Resource
    private FillSubmissionRowMapper submissionRowMapper;

    @Resource
    private FillControlService controlService;

    @Resource
    private FillPreviousPeriodService previousPeriodService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillSubmissionMapper submissionMapper;

    /** 可复用历史期次的查询上限 */
    private static final int REUSE_SOURCE_LIMIT = 24;

    /** 复用方式：替换全部草稿行 */
    static final int REUSE_MODE_REPLACE = 2;

    @Resource
    private FillTaskMapper taskMapper;

    @Resource
    private FillWorkItemService workItemService;

    @Resource
    private FillTableVersionService tableVersionService;

    @Resource
    private FillCollabService collabService;

    /**
     * 草稿全量快照：工作台进入/断线重连时拉取（含模板 schema、全部行与当前 serverVersion）
     *
     * @param workItemId 工作项编号
     * @return 填报草稿快照响应数据
     */
    @Override
    public FillDraftSnapshotRespVO getSnapshot(Long workItemId) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkViewPermission(item, userId);
        FillTableVersionDO version = tableVersionService.getTableVersion(item.getTableVersionId());
        FillTaskDO task = taskMapper.selectById(item.getTaskId());

        FillDraftSnapshotRespVO respVO = new FillDraftSnapshotRespVO();
        respVO.setWorkItemId(item.getId());
        respVO.setStatus(item.getStatus());
        respVO.setOverdue(item.getOverdue());
        respVO.setDeadlineTime(item.getDeadlineTime());
        respVO.setServerVersion(item.getServerVersion());
        respVO.setOrgName(item.getOrgName());
        if (task != null) {
            respVO.setTaskName(task.getName());
            respVO.setPeriodKey(task.getPeriodKey());
        }
        respVO.setFieldSchema(version.getFieldSchema());
        respVO.setFormulaSchema(version.getFormulaSchema());
        respVO.setLayoutSnapshot(version.getLayoutSnapshot());
        respVO.setValidationSchema(version.getValidationSchema());
        respVO.setEditable(isEditable(item, userId));
        List<FillDraftRowDO> draftRows = draftRowMapper.selectListByWorkItemId(workItemId);
        respVO.setRows(convertList(draftRows, row -> {
            FillDraftSnapshotRespVO.Row rowVO = new FillDraftSnapshotRespVO.Row();
            rowVO.setRowId(row.getRowId());
            rowVO.setRowOrder(row.getRowOrder());
            rowVO.setData(parseRowData(row.getData()));
            rowVO.setRowVersion(row.getRowVersion());
            rowVO.setCreateUserId(row.getCreateUserId());
            rowVO.setLastModifyUserId(row.getLastModifyUserId());
            rowVO.setSourceKind(row.getSourceKind());
            rowVO.setSourceOrgId(row.getSourceOrgId());
            return rowVO;
        }));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = version.getFieldSchema() == null
                ? List.of() : (List<Map<String, Object>>) (List<?>) JsonUtils.parseObject(version.getFieldSchema(), List.class);
        Map<String, List<FillControlService.Option>> refOptions = controlService.resolveOptions(item,
                fields == null ? List.of() : fields, convertList(draftRows, row -> parseRowData(row.getData())));
        Map<String, List<FillDraftSnapshotRespVO.RefOption>> refOptionVOs = new LinkedHashMap<>();
        refOptions.forEach((code, options) -> refOptionVOs.put(code, convertList(options, option -> {
            FillDraftSnapshotRespVO.RefOption vo = new FillDraftSnapshotRespVO.RefOption();
            vo.setValue(option.value());
            vo.setLabel(option.label());
            return vo;
        })));
        respVO.setRefOptions(refOptionVOs);
        return respVO;
    }

    /**
     * 增量拉取操作：afterVersion 之后的操作，按 serverVersion 升序（协同补齐与重放）
     *
     * @param workItemId 工作项编号
     * @param afterVersion 变更后版本
     * @param limit 读取或处理上限
     * @return 增量拉取操作：afterVersion 之后的操作，按 serverVersion 升序（协同补齐与重放）结果集合
     */
    @Override
    public List<FillOperationRespVO> getOperations(Long workItemId, Long afterVersion, Integer limit) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkViewPermission(item, userId);
        int pullLimit = limit == null ? 200 : Math.min(Math.max(limit, 1), MAX_PULL_LIMIT);
        List<FillOperationDO> operations = operationMapper.selectListAfterVersion(
                workItemId, afterVersion == null ? 0L : afterVersion, pullLimit);
        return convertList(operations, op -> {
            FillOperationRespVO respVO = new FillOperationRespVO();
            respVO.setServerVersion(op.getServerVersion());
            respVO.setClientOpId(op.getClientOpId());
            respVO.setClientSessionId(op.getClientSessionId());
            respVO.setOpType(op.getOpType());
            respVO.setRowId(op.getRowId());
            respVO.setFieldCode(op.getFieldCode());
            respVO.setNewValue(op.getNewValue() == null
                    ? null : JsonUtils.parseObject(op.getNewValue(), Object.class));
            respVO.setOperatorUserId(op.getOperatorUserId());
            respVO.setCreateTime(op.getCreateTime());
            return respVO;
        });
    }

    /**
     * 应用一批领域操作（SET_CELL/ADD_ROW/REMOVE_ROW）
     *
     *  单事务原子完成：状态与 expectedVersion 的工作项 CAS（同时串行化并发批次）、
     *  草稿行 rowVersion CAS、操作日志落盘；clientOpId 唯一约束保证网络重试不重复执行；
     *  事务提交后向房间广播"有新操作"提示
     *
     * @param workItemId 工作项编号
     * @param reqVO 请求参数
     * @return 填报操作应用响应数据
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FillOperationApplyRespVO applyOperations(Long workItemId, FillOperationApplyReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkEditPermission(item, userId);
        // 首次编辑：待填报 → 填报中（尽力迁移，失败说明已被并发迁移）
        if (FillWorkItemStatusEnum.PENDING.getStatus().equals(item.getStatus())) {
            workItemMapper.compareAndSetStatus(workItemId,
                    FillWorkItemStatusEnum.PENDING.getStatus(), FillWorkItemStatusEnum.FILLING.getStatus());
        }

        FieldSchemaIndex schema = parseFieldSchema(item.getTableVersionId());
        long currentVersion = reqVO.getExpectedVersion();
        int applied = 0;
        int duplicates = 0;
        boolean rowCountChanged = false;

        for (FillOperationApplyReqVO.OperationItem op : reqVO.getOperations()) {
            // clientOpId 幂等：已执行过的操作直接跳过（唯一约束兜底）
            if (operationMapper.selectByClientOpId(workItemId, op.getClientOpId()) != null) {
                duplicates++;
                continue;
            }
            // 版本 CAS：第一个操作校验客户端基准版本并锁定工作项行，后续操作顺序推进
            int advanced = workItemMapper.advanceServerVersion(workItemId, currentVersion, userId);
            if (advanced == 0) {
                throw exception(FILL_WORK_ITEM_VERSION_CONFLICT);
            }
            currentVersion++;

            String oldValueJson;
            String newValueJson;
            FillOperationTypeEnum opType = resolveOpType(op.getOpType());
            switch (opType) {
                case SET_CELL -> {
                    String[] values = applySetCell(workItemId, op, schema, userId);
                    oldValueJson = values[0];
                    newValueJson = values[1];
                }
                case ADD_ROW -> {
                    newValueJson = applyAddRow(workItemId, op, schema, userId);
                    oldValueJson = null;
                    rowCountChanged = true;
                }
                case REMOVE_ROW -> {
                    oldValueJson = applyRemoveRow(workItemId, op);
                    newValueJson = null;
                    rowCountChanged = true;
                }
                default -> throw exception(FILL_OPERATION_INVALID, "暂不支持的操作类型 " + op.getOpType());
            }

            try {
                operationMapper.insert(FillOperationDO.builder()
                        .workItemId(workItemId)
                        .serverVersion(currentVersion)
                        .clientOpId(op.getClientOpId())
                        .clientSessionId(reqVO.getClientSessionId())
                        .opType(opType.getType())
                        .rowId(op.getRowId())
                        .fieldCode(op.getFieldCode())
                        .oldValue(oldValueJson)
                        .newValue(newValueJson)
                        .operatorUserId(userId)
                        .build());
            } catch (DuplicateKeyException ex) {
                throw exception(FILL_OPERATION_DUPLICATE);
            }
            applied++;
        }

        int rowCount = Math.toIntExact(draftRowMapper.selectCount(
                FillDraftRowDO::getWorkItemId, workItemId));
        if (rowCountChanged) {
            FillWorkItemDO update = new FillWorkItemDO();
            update.setId(workItemId);
            update.setDraftRowCount(rowCount);
            workItemMapper.updateById(update);
        }

        // 事务提交后再广播，避免回滚事务发出脏提示
        long finalVersion = currentVersion;
        if (applied > 0 && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 在本地事务成功提交后触发对应的后续处理。
                 */
                @Override
                public void afterCommit() {
                    collabService.notifyOperationApplied(workItemId, finalVersion, userId);
                }
            });
        }

        FillOperationApplyRespVO respVO = new FillOperationApplyRespVO();
        respVO.setServerVersion(currentVersion);
        respVO.setAppliedCount(applied);
        respVO.setDuplicateCount(duplicates);
        respVO.setDraftRowCount(rowCount);
        return respVO;
    }

    /**
     * 系统带入草稿（上期数据带入）
     *
     *  先以一次版本 CAS 预留 N 个连续 serverVersion（同时锁定工作项行、确认草稿未被编辑），
     *  再逐行写草稿与 PREFILL 操作日志；新行标识重新生成，不复用来源快照的 rowId（防止来源追踪混淆）
     *
     * @param item 目标工作项
     * @param sourceSubmissionId 来源提交快照编号
     * @param operatorUserId 记入操作日志的操作人
     * @return 带入的行数，0 表示跳过
     */
    @Override
    public List<FillReuseSourceRespVO> getReuseSources(
            Long workItemId) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkViewPermission(item, userId);
        FillTaskDO task = taskMapper.selectById(item.getTaskId());
        List<FillReuseSourceRespVO> sources = new ArrayList<>();
        if (task == null || task.getPeriodStart() == null) {
            return sources;
        }
        for (FillTaskDO earlier : taskMapper.selectEarlierTasks(task.getPlanId(), task.getPeriodStart(), task.getId(),
                REUSE_SOURCE_LIMIT)) {
            var submission = previousPeriodService.findApprovedSubmission(earlier, item.getReportingOrgId());
            if (submission == null || submission.getRowCount() == null || submission.getRowCount() == 0) {
                continue;
            }
            var source = new FillReuseSourceRespVO();
            source.setSubmissionId(submission.getId());
            source.setTaskId(earlier.getId());
            source.setTaskName(earlier.getName());
            source.setPeriodKey(earlier.getPeriodKey());
            source.setRowCount(submission.getRowCount());
            source.setSubmitTime(submission.getSubmitTime());
            sources.add(source);
        }
        return sources;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FillOperationApplyRespVO reuse(Long workItemId,
            FillReuseReqVO reqVO) {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        FillWorkItemDO item = workItemService.getWorkItemValidated(workItemId);
        workItemService.checkEditPermission(item, userId);
        // 来源必须在可复用列表内：同计划、同组织、更早期次、审批通过、有数据
        boolean allowed = getReuseSources(workItemId).stream()
                .anyMatch(source -> Objects.equals(source.getSubmissionId(), reqVO.getSubmissionId()));
        if (!allowed) {
            throw exception(FILL_OPERATION_INVALID, "所选期次的数据不可复用");
        }
        FieldSchemaIndex schema = parseFieldSchema(item.getTableVersionId());
        List<FillOperationApplyReqVO.OperationItem> adds = new ArrayList<>();
        for (FillSubmissionRowDO sourceRow : submissionRowMapper.selectListBySubmissionId(reqVO.getSubmissionId())) {
            // 历史上由下级汇总生成的行不带入：本期应由下级重新上报
            if (sourceRow.getSourceKind() != null) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            parseRowData(sourceRow.getData()).forEach((fieldCode, value) -> {
                if (value != null && schema.prefillFields.contains(fieldCode)) {
                    data.put(fieldCode, value);
                }
            });
            if (data.isEmpty()) {
                continue;
            }
            FillOperationApplyReqVO.OperationItem add = new FillOperationApplyReqVO.OperationItem();
            add.setClientOpId("reuse-" + IdUtil.fastSimpleUUID());
            add.setOpType(FillOperationTypeEnum.ADD_ROW.getType());
            add.setRowId("ru" + IdUtil.fastSimpleUUID());
            add.setValue(data);
            adds.add(add);
        }
        // 没有可带入的值时不动现有草稿（替换模式也不清空）
        if (adds.isEmpty()) {
            throw exception(FILL_OPERATION_INVALID, "所选期次没有可带入的数据");
        }
        List<FillOperationApplyReqVO.OperationItem> operations = new ArrayList<>();
        if (Integer.valueOf(REUSE_MODE_REPLACE).equals(reqVO.getMode())) {
            for (FillDraftRowDO existing : draftRowMapper.selectListByWorkItemId(workItemId)) {
                if (existing.getSourceKind() != null) {
                    continue;
                }
                FillOperationApplyReqVO.OperationItem remove = new FillOperationApplyReqVO.OperationItem();
                remove.setClientOpId("reuse-" + IdUtil.fastSimpleUUID());
                remove.setOpType(FillOperationTypeEnum.REMOVE_ROW.getType());
                remove.setRowId(existing.getRowId());
                operations.add(remove);
            }
        }
        operations.addAll(adds);
        FillOperationApplyReqVO applyReqVO = new FillOperationApplyReqVO();
        applyReqVO.setExpectedVersion(reqVO.getExpectedVersion());
        applyReqVO.setClientSessionId("reuse");
        applyReqVO.setOperations(operations);
        return applyOperations(workItemId, applyReqVO);
    }

    @Override
    public int prefillFromSubmission(FillWorkItemDO item, Long sourceSubmissionId, Long operatorUserId) {
        if (item == null || sourceSubmissionId == null
                || !FillWorkItemStatusEnum.EDITABLE_STATUSES.contains(item.getStatus())) {
            return 0;
        }
        FieldSchemaIndex schema = parseFieldSchema(item.getTableVersionId());
        List<Map<String, Object>> rowsData = new ArrayList<>();
        for (FillSubmissionRowDO sourceRow : submissionRowMapper.selectListBySubmissionId(sourceSubmissionId)) {
            if (sourceRow.getSourceKind() != null) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            parseRowData(sourceRow.getData()).forEach((fieldCode, value) -> {
                if (value != null && schema.prefillFields.contains(fieldCode)) {
                    data.put(fieldCode, value);
                }
            });
            if (!data.isEmpty()) {
                rowsData.add(data);
            }
        }
        if (rowsData.isEmpty()) {
            return 0;
        }
        long baseVersion = item.getServerVersion() == null ? 0L : item.getServerVersion();
        if (workItemMapper.advanceServerVersionBy(item.getId(), baseVersion, (long) rowsData.size(),
                operatorUserId) == 0) {
            return 0;
        }
        FillDraftRowDO last = draftRowMapper.selectLastByWorkItemId(item.getId());
        BigDecimal order = last == null ? BigDecimal.ZERO : last.getRowOrder();
        for (int index = 0; index < rowsData.size(); index++) {
            String rowId = "pf" + IdUtil.fastSimpleUUID();
            String dataJson = JsonUtils.toJsonString(rowsData.get(index));
            order = order.add(ORDER_GAP);
            draftRowMapper.insert(FillDraftRowDO.builder()
                    .workItemId(item.getId())
                    .rowId(rowId)
                    .rowOrder(order)
                    .data(dataJson)
                    .rowVersion(0L)
                    .createUserId(operatorUserId)
                    .lastModifyUserId(operatorUserId)
                    .build());
            operationMapper.insert(FillOperationDO.builder()
                    .workItemId(item.getId())
                    .serverVersion(baseVersion + index + 1)
                    .clientOpId("pf-" + IdUtil.fastSimpleUUID())
                    .clientSessionId("prefill")
                    .opType(FillOperationTypeEnum.PREFILL.getType())
                    .rowId(rowId)
                    .newValue(dataJson)
                    .operatorUserId(operatorUserId)
                    .build());
        }
        FillWorkItemDO update = new FillWorkItemDO();
        update.setId(item.getId());
        update.setDraftRowCount(Math.toIntExact(draftRowMapper.selectCount(
                FillDraftRowDO::getWorkItemId, item.getId())));
        workItemMapper.updateById(update);
        item.setServerVersion(baseVersion + rowsData.size());
        item.setDraftRowCount(update.getDraftRowCount());
        return rowsData.size();
    }

    /**
     * 汇总写入：删除此前由汇总生成的行，再写入本次汇总的行（带来源）。
     *
     * <p>以一次版本 CAS 预留全部操作的 serverVersion（同时锁定工作项行、确认客户端看到的是最新草稿），
     * 每行记一条 REMOVE_ROW / ADD_ROW 操作日志，事务提交后提示协同房间重新加载。本单位录入的行不受影响。</p>
     *
     * @param item 汇总单位的工作项
     * @param rows 汇总生成的行
     * @param expectedVersion 客户端已同步到的服务端版本
     * @param operatorUserId 操作人
     * @return 汇总后的草稿行数
     */
    @Override
    public int replaceRollupRows(FillWorkItemDO item, List<RollupRow> rows, long expectedVersion,
                                 Long operatorUserId) {
        List<FillDraftRowDO> previous = draftRowMapper.selectListByWorkItemId(item.getId()).stream()
                .filter(row -> row.getSourceKind() != null).toList();
        long steps = (long) previous.size() + rows.size();
        if (steps == 0) {
            return Math.toIntExact(draftRowMapper.selectCount(FillDraftRowDO::getWorkItemId, item.getId()));
        }
        if (workItemMapper.advanceServerVersionBy(item.getId(), expectedVersion, steps, operatorUserId) == 0) {
            throw exception(FILL_WORK_ITEM_VERSION_CONFLICT);
        }
        if (FillWorkItemStatusEnum.PENDING.getStatus().equals(item.getStatus())) {
            workItemMapper.compareAndSetStatus(item.getId(),
                    FillWorkItemStatusEnum.PENDING.getStatus(), FillWorkItemStatusEnum.FILLING.getStatus());
        }
        long version = expectedVersion;
        for (FillDraftRowDO row : previous) {
            draftRowMapper.deleteById(row.getId());
            operationMapper.insert(FillOperationDO.builder()
                    .workItemId(item.getId())
                    .serverVersion(++version)
                    .clientOpId("ru-" + IdUtil.fastSimpleUUID())
                    .clientSessionId("rollup")
                    .opType(FillOperationTypeEnum.REMOVE_ROW.getType())
                    .rowId(row.getRowId())
                    .oldValue(row.getData())
                    .operatorUserId(operatorUserId)
                    .build());
        }
        FillDraftRowDO last = draftRowMapper.selectLastByWorkItemId(item.getId());
        BigDecimal order = last == null ? BigDecimal.ZERO : last.getRowOrder();
        for (RollupRow row : rows) {
            String dataJson = JsonUtils.toJsonString(row.data());
            order = order.add(ORDER_GAP);
            // 行标识含已删行全局唯一，每次汇总重新签发；数据身份由来源列（原始录入实例 + 行）表示
            String rowId = "ru" + IdUtil.fastSimpleUUID();
            draftRowMapper.insert(FillDraftRowDO.builder()
                    .workItemId(item.getId())
                    .rowId(rowId)
                    .rowOrder(order)
                    .data(dataJson)
                    .rowVersion(0L)
                    .createUserId(operatorUserId)
                    .lastModifyUserId(operatorUserId)
                    .sourceKind(row.sourceKind())
                    .sourceOrgId(row.sourceOrgId())
                    .sourceWorkItemId(row.sourceWorkItemId())
                    .sourceSubmissionId(row.sourceSubmissionId())
                    .sourceRowId(row.sourceRowId())
                    .build());
            operationMapper.insert(FillOperationDO.builder()
                    .workItemId(item.getId())
                    .serverVersion(++version)
                    .clientOpId("ru-" + IdUtil.fastSimpleUUID())
                    .clientSessionId("rollup")
                    .opType(FillOperationTypeEnum.ADD_ROW.getType())
                    .rowId(rowId)
                    .newValue(dataJson)
                    .operatorUserId(operatorUserId)
                    .build());
        }
        int rowCount = Math.toIntExact(draftRowMapper.selectCount(FillDraftRowDO::getWorkItemId, item.getId()));
        FillWorkItemDO update = new FillWorkItemDO();
        update.setId(item.getId());
        update.setDraftRowCount(rowCount);
        workItemMapper.updateById(update);
        long finalVersion = version;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    collabService.notifyOperationApplied(item.getId(), finalVersion, operatorUserId);
                }
            });
        }
        return rowCount;
    }

    // ---------------------------------------------------------------- 操作执行

    /** @return [oldValueJson, newValueJson] */
    private String[] applySetCell(Long workItemId, FillOperationApplyReqVO.OperationItem op,
            FieldSchemaIndex schema, Long userId) {
        if (StrUtil.isBlank(op.getRowId()) || StrUtil.isBlank(op.getFieldCode())) {
            throw exception(FILL_OPERATION_INVALID, "SET_CELL 缺少 rowId 或 fieldCode");
        }
        if (!schema.allFields.contains(op.getFieldCode())) {
            throw exception(FILL_TABLE_COLUMN_NOT_EXISTS);
        }
        if (!schema.editableFields.contains(op.getFieldCode())) {
            throw exception(FILL_FIELD_NOT_EDITABLE, op.getFieldCode());
        }
        FillDraftRowDO row = draftRowMapper.selectByWorkItemIdAndRowId(workItemId, op.getRowId());
        if (row == null) {
            throw exception(FILL_DRAFT_ROW_NOT_EXISTS);
        }
        if (row.getSourceKind() != null) {
            throw exception(FILL_ROLLUP_ROW_READONLY, row.getRowId());
        }
        Map<String, Object> data = parseRowData(row.getData());
        String oldValueJson = toJson(data.get(op.getFieldCode()));
        data.put(op.getFieldCode(), op.getValue());
        int updated = draftRowMapper.compareAndSetData(row.getId(), row.getRowVersion(),
                JsonUtils.toJsonString(data), userId);
        if (updated == 0) {
            // 工作项行锁内读取的行版本理论上稳定；到达此处说明存在绕过版本推进的写入，拒绝覆盖
            throw exception(FILL_DRAFT_ROW_VERSION_CONFLICT, row.getLastModifyUserId());
        }
        return new String[]{oldValueJson, toJson(op.getValue())};
    }

    /** @return newValueJson（新行初始数据） */
    private String applyAddRow(Long workItemId, FillOperationApplyReqVO.OperationItem op,
            FieldSchemaIndex schema, Long userId) {
        if (StrUtil.isBlank(op.getRowId()) || !ROW_ID_PATTERN.matcher(op.getRowId()).matches()) {
            throw exception(FILL_OPERATION_INVALID, "ADD_ROW 的 rowId 缺失或格式不合法");
        }
        // 初始数据只接收模板内可编辑字段（白名单过滤）
        Map<String, Object> data = new LinkedHashMap<>();
        if (op.getValue() instanceof Map<?, ?> initial) {
            for (Map.Entry<?, ?> entry : initial.entrySet()) {
                String fieldCode = String.valueOf(entry.getKey());
                if (schema.editableFields.contains(fieldCode)) {
                    data.put(fieldCode, entry.getValue());
                }
            }
        }
        try {
            draftRowMapper.insert(FillDraftRowDO.builder()
                    .workItemId(workItemId)
                    .rowId(op.getRowId())
                    .rowOrder(computeRowOrder(workItemId, op.getAfterRowId()))
                    .data(JsonUtils.toJsonString(data))
                    .rowVersion(0L)
                    .createUserId(userId)
                    .lastModifyUserId(userId)
                    .build());
        } catch (DuplicateKeyException ex) {
            // rowId 含已删行全局唯一（防复活破坏来源追踪）
            throw exception(FILL_OPERATION_INVALID, "行标识已存在：" + op.getRowId());
        }
        return JsonUtils.toJsonString(data);
    }

    /** @return oldValueJson（被删行的完整数据） */
    private String applyRemoveRow(Long workItemId, FillOperationApplyReqVO.OperationItem op) {
        if (StrUtil.isBlank(op.getRowId())) {
            throw exception(FILL_OPERATION_INVALID, "REMOVE_ROW 缺少 rowId");
        }
        FillDraftRowDO row = draftRowMapper.selectByWorkItemIdAndRowId(workItemId, op.getRowId());
        if (row == null) {
            throw exception(FILL_DRAFT_ROW_NOT_EXISTS);
        }
        if (row.getSourceKind() != null) {
            throw exception(FILL_ROLLUP_ROW_READONLY, row.getRowId());
        }
        draftRowMapper.deleteById(row.getId());
        return row.getData();
    }

    /** 小数间隙法：插行不重排既有行 */
    private BigDecimal computeRowOrder(Long workItemId, String afterRowId) {
        if (StrUtil.isBlank(afterRowId)) {
            FillDraftRowDO last = draftRowMapper.selectLastByWorkItemId(workItemId);
            return last == null ? ORDER_GAP : last.getRowOrder().add(ORDER_GAP);
        }
        FillDraftRowDO after = draftRowMapper.selectByWorkItemIdAndRowId(workItemId, afterRowId);
        if (after == null) {
            throw exception(FILL_OPERATION_INVALID, "afterRowId 对应的行不存在：" + afterRowId);
        }
        FillDraftRowDO next = draftRowMapper.selectFirstAfterOrder(workItemId, after.getRowOrder());
        if (next == null) {
            return after.getRowOrder().add(ORDER_GAP);
        }
        return after.getRowOrder().add(next.getRowOrder())
                .divide(BigDecimal.valueOf(2), ORDER_SCALE, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 解析操作类型。
     *
     * @param opType 操作类型
     * @return 填报操作类型
     */
    private FillOperationTypeEnum resolveOpType(String opType) {
        for (FillOperationTypeEnum value : FillOperationTypeEnum.values()) {
            if (value.getType().equals(opType)) {
                return value;
            }
        }
        throw exception(FILL_OPERATION_INVALID, "未知操作类型 " + opType);
    }

    /**
     * 判断是否满足可编辑条件。
     *
     * @param item 当前条目
     * @param userId 用户编号
     * @return 判断结果
     */
    private boolean isEditable(FillWorkItemDO item, Long userId) {
        try {
            workItemService.checkEditPermission(item, userId);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 解析行数据。
     *
     * @param dataJson 数据JSON
     * @return 处理后的键值映射
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRowData(String dataJson) {
        if (StrUtil.isBlank(dataJson)) {
            return new HashMap<>();
        }
        Map<String, Object> data = JsonUtils.parseObject(dataJson, Map.class);
        return data != null ? data : new HashMap<>();
    }

    /**
     * 转换为JSON。
     *
     * @param value 待处理的值
     * @return 转换为JSON文本
     */
    private String toJson(Object value) {
        return value == null ? null : JsonUtils.toJsonString(value);
    }

    /** 模板字段索引：全部字段码 + 可编辑字段码（公式/系统列只读） */
    private FieldSchemaIndex parseFieldSchema(Long tableVersionId) {
        FillTableVersionDO version = tableVersionService.getTableVersion(tableVersionId);
        FieldSchemaIndex index = new FieldSchemaIndex();
        List<?> fields = JsonUtils.parseObject(version.getFieldSchema(), List.class);
        if (fields != null) {
            for (Object fieldObj : fields) {
                if (!(fieldObj instanceof Map<?, ?> field)) {
                    continue;
                }
                String fieldCode = String.valueOf(field.get("fieldCode"));
                index.allFields.add(fieldCode);
                if (!Boolean.FALSE.equals(field.get("editable"))) {
                    index.editableFields.add(fieldCode);
                    // 主键沿用上期会与上期正式数据冲突，隐藏字段不在录入界面，二者都不带入
                    if (!Boolean.TRUE.equals(field.get("hidden")) && !Boolean.TRUE.equals(field.get("pk"))) {
                        index.prefillFields.add(fieldCode);
                    }
                }
            }
        }
        return index;
    }

    private static class FieldSchemaIndex {

        private final Set<String> allFields = new HashSet<>();

        private final Set<String> editableFields = new HashSet<>();

        /** 上期数据带入的字段：可编辑、未隐藏、非主键 */
        private final Set<String> prefillFields = new HashSet<>();
    }

}

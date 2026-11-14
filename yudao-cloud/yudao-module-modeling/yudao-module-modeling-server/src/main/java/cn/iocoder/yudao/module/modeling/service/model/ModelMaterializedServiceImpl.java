package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo.LogicTableCatNodeRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized.*;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerListReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelDeploymentAttemptDO;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.PostgresSchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaChanges;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedLogDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedLogMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelDeploymentAttemptMapper;
import cn.iocoder.yudao.module.modeling.service.logictable.LogicTableService;
import cn.iocoder.yudao.module.modeling.service.planning.DwLayerService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 模型物化发布：门禁沿用 ModelHierarchyResolver；DDL 复用 ModelDdlExecutor + dataforge 数据源连接。
 */
@Slf4j
@Service
@Validated
public class ModelMaterializedServiceImpl implements ModelMaterializedService {

    /** 仅固定领域子查询参与排序，客户端的字段名和方向不会进入 SQL。 */
    private static final String LATEST_PUBLISH_TIME_SQL = "(SELECT a.finished_time FROM dh_modeling_model_materialized m "
            + "LEFT JOIN dh_modeling_deployment_attempt a ON a.id = m.attempt_id AND a.deleted = 0 "
            + "WHERE m.model_id = dh_modeling_logic_model.id AND m.deleted = 0 "
            + "ORDER BY m.update_time DESC, m.id DESC LIMIT 1)";

    @Resource
    private ModelMaterializedMapper materializedMapper;

    @Resource
    private ModelMaterializedLogMapper logMapper;

    @Resource
    private LogicModelMapper logicModelMapper;

    @Resource
    private LogicModelColumnService columnService;

    @Resource
    private ModelDeploymentAttemptMapper deploymentAttemptMapper;

    @Resource
    private LogicModelService logicModelService;

    @Resource
    private LogicTableService logicTableService;

    @Resource
    private DwLayerService dwLayerService;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private ModelDeploymentAttemptStore attemptStore;

    @Resource
    private ModelTypeConfigValidator typeConfigValidator;

    @Resource
    private ModelHierarchyResolver hierarchyResolver;

    @Resource
    private ModelTableNameResolver tableNameResolver;

    @Resource
    private ModelDdlExecutor ddlExecutor;

    @Resource
    private ModelingDatabaseResolver databaseResolver;

    /**
     * 物化发布分页。
     *
     * @param reqVO 请求参数
     * @return 物化发布分页结果
     */
    @Override
    public PageResult<ModelMaterializedRespVO> getPage(ModelMaterializedPageReqVO reqVO) {
        String publishStatus = resolvePublishStatusFilter(reqVO);
        PublishStatusScope statusScope = resolveStatusScope(publishStatus, reqVO);
        if (statusScope.empty()) {
            return PageResult.empty();
        }

        LambdaQueryWrapperX<LogicModelDO> wrapper = new LambdaQueryWrapperX<LogicModelDO>();
        wrapper.eqIfPresent(LogicModelDO::getId, reqVO.getModelId())
                .likeIfPresent(LogicModelDO::getName, reqVO.getName())
                .likeIfPresent(LogicModelDO::getCode, reqVO.getCode())
                .eqIfPresent(LogicModelDO::getLayerId, reqVO.getLayerId())
                .inIfPresent(LogicModelDO::getLayerId, reqVO.getLayerIds())
                .eqIfPresent(LogicModelDO::getTableType, reqVO.getTableType())
                .eqIfPresent(LogicModelDO::getSourceSystemId, reqVO.getSourceSystemId())
                .eqIfPresent(LogicModelDO::getBizCategoryId, reqVO.getBizCategoryId())
                .eqIfPresent(LogicModelDO::getDataDomainId, reqVO.getDataDomainId())
                .eqIfPresent(LogicModelDO::getSubjectDomainId, reqVO.getSubjectDomainId());
        // 只发布逻辑模型里已启用的记录；0 禁用、空都不进入列表
        wrapper.eq(LogicModelDO::getEnabled, 1);
        if ("publishTime".equals(reqVO.getSortField())) {
            wrapper.last("ORDER BY " + LATEST_PUBLISH_TIME_SQL
                    + ("asc".equals(reqVO.getSortOrder()) ? " ASC" : " DESC") + " NULLS LAST, id DESC");
        } else {
            wrapper.orderBy(true, "asc".equals(reqVO.getSortOrder()), LogicModelDO::getId);
        }
        if (statusScope.unpublished()) {
            if (CollUtil.isNotEmpty(statusScope.excludeModelIds())) {
                wrapper.notIn(LogicModelDO::getId, statusScope.excludeModelIds());
            }
        } else if (statusScope.includeModelIds() != null) {
            wrapper.in(LogicModelDO::getId, statusScope.includeModelIds());
        }

        PageResult<LogicModelDO> page = logicModelMapper.selectPage(reqVO, wrapper);
        if (CollUtil.isEmpty(page.getList())) {
            return new PageResult<>(Collections.emptyList(), page.getTotal());
        }

        List<Long> modelIds = page.getList().stream().map(LogicModelDO::getId).toList();
        Map<Long, List<ModelMaterializedDO>> matByModel = groupMaterializedByModel(
                materializedMapper.selectListByModelIds(modelIds));

        List<ModelMaterializedRespVO> list = new ArrayList<>(page.getList().size());
        for (LogicModelDO model : page.getList()) {
            list.add(toListItem(model, matByModel.getOrDefault(model.getId(), List.of())));
        }
        fillModelHierarchy(list);
        fillPresentation(list);
        return new PageResult<>(list, page.getTotal());
    }

    /**
     * 读取模型物化响应数据。
     *
     * @param id 记录编号
     * @return 模型物化响应数据
     */
    @Override
    public ModelMaterializedRespVO get(Long id) {
        ModelMaterializedDO row = requireMaterialized(id);
        // 与统一模型目录一致，当前采用平台内全可见；存在性与读取入口由模型服务统一处理。
        LogicModelDO model = logicModelService.getModel(row.getModelId());
        ModelMaterializedRespVO resp = toListItem(model, List.of(row));
        fillModelHierarchy(List.of(resp));
        fillPresentation(List.of(resp));
        fillDetail(resp);
        return resp;
    }

    /**
     * 按模型编号读取模型物化。
     *
     * @param modelId 模型编号
     * @return 模型物化响应数据
     */
    @Override
    public ModelMaterializedRespVO getByModelId(Long modelId) {
        LogicModelDO model = logicModelService.getModel(modelId);
        ModelMaterializedRespVO resp = toListItem(model, materializedMapper.selectListByModelIds(List.of(modelId)));
        fillModelHierarchy(List.of(resp));
        fillPresentation(List.of(resp));
        fillDetail(resp);
        return resp;
    }

    /**
     * 获得模型发布数据源选项（不含凭据）。
     *
     * @return 数据源列表
     */
    @Override
    public List<ModelMaterializedDatabaseRespVO> getDatabaseList() {
        // 仅映射所属服务返回的可见连接展示字段，RPC 的凭据字段不会进入浏览器协议。
        return databaseResolver.getDatabaseList().stream().map(source -> {
            ModelMaterializedDatabaseRespVO result = BeanUtils.toBean(source, ModelMaterializedDatabaseRespVO.class);
            result.setDatabaseAddress(source.getHost());
            return result;
        }).toList();
    }

    /**
     * 获得发布模型分类目录。
     *
     * @return 分类树
     */
    @Override
    public List<ModelMaterializedCatNodeRespVO> getCatTree() {
        List<LogicTableCatNodeRespVO> domains = logicTableService.getCatTree("DETAIL");
        List<LogicTableCatNodeRespVO> sources = logicTableService.getCatTree("SOURCE");
        List<DwLayerDO> layers = dwLayerService.getDwLayerList(new DwLayerListReqVO());
        Map<Long, ModelMaterializedCatNodeRespVO> layerNodes = new LinkedHashMap<>();
        for (DwLayerDO layer : layers) {
            var node = catalogNode("layer-" + layer.getId(), layer.getLayerName(),
                    DwLayerDO.NODE_TYPE_LAYER.equals(layer.getNodeType()) ? "LAYER" : "LAYER_CATEGORY");
            if (DwLayerDO.NODE_TYPE_LAYER.equals(layer.getNodeType())) {
                node.setLayerId(layer.getId());
                node.setTableType(layer.getDefaultTableType());
                node.setChildren(copyCatalog("SOURCE".equals(layer.getDefaultTableType()) ? sources : domains,
                        node.getKey() + "-", layer.getId()));
                node.getChildren().forEach(child -> fillCatalogTableType(child, layer.getDefaultTableType()));
            }
            layerNodes.put(layer.getId(), node);
        }
        List<ModelMaterializedCatNodeRespVO> layerRoots = new ArrayList<>();
        for (DwLayerDO layer : layers) {
            var node = layerNodes.get(layer.getId());
            var parent = layerNodes.get(layer.getParentId());
            if (parent == null) {
                layerRoots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        layerRoots.forEach(this::fillCatalogLayerIds);
        var byDomain = catalogNode("by-domain", "按数据板块", "GROUP");
        byDomain.setChildren(copyCatalog(domains, "board-", null));
        var byLayer = catalogNode("by-layer", "按数仓分层", "GROUP");
        byLayer.setChildren(layerRoots);
        var bySource = catalogNode("by-source", "按来源系统", "GROUP");
        bySource.setChildren(copyCatalog(sources, "origin-", null));
        return List.of(byDomain, byLayer, bySource);
    }

    /**
     * 发布日志分页。
     *
     * @param reqVO 请求参数
     * @return 发布日志分页结果
     */
    @Override
    public PageResult<ModelMaterializedLogRespVO> getLogPage(ModelMaterializedLogPageReqVO reqVO) {
        PageResult<ModelMaterializedLogDO> page = logMapper.selectPage(reqVO);
        if (page.getList().isEmpty()) {
            return new PageResult<>(List.of(), page.getTotal());
        }
        Map<Long, AdminUserRespDTO> users = userMap(page.getList().stream()
                .flatMap(row -> java.util.stream.Stream.of(row.getOperator(), row.getCreator())).toList());
        var result = page.getList().stream().map(row -> {
            var item = BeanUtils.toBean(row, ModelMaterializedLogRespVO.class);
            var operator = auditUser(users, row.getOperator());
            item.setOperatorName(operator == null ? null : operator.getNickname());
            var creator = auditUser(users, row.getCreator());
            item.setCreatorName(creator == null ? null : creator.getNickname());
            item.setPublishDuration(duration(row.getStartTime(), row.getEndTime()));
            return item;
        }).toList();
        return new PageResult<>(result, page.getTotal());
    }

    /**
     * 首次发布模型。
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long publish(ModelMaterializedPublishReqVO reqVO) {
        String mode = normalizeMode(reqVO.getPublishMode());
        return deploy(reqVO.getModelId(), reqVO.getDatasourceId(), mode, reqVO.getDescription(), reqVO.getRemark());
    }

    /**
     * 重新发布模型。
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Long republish(ModelMaterializedRepublishReqVO reqVO) {
        String mode = normalizeMode(reqVO.getPublishMode());
        ModelMaterializedDO row = requireMaterialized(reqVO.getId());
        if (ModelMaterializedDO.STATUS_UNKNOWN.equals(row.getStatus()) || ModelMaterializedDO.STATUS_RUNNING.equals(
                row.getStatus())) {
            throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
        }
        return deploy(row.getModelId(), row.getDatasourceId(), mode, reqVO.getDescription(), reqVO.getRemark());
    }

    /**
     * 根据已发布模型快照生成目标 DDL，先保存部署意图再执行外部变更及结果核验。
     *
     * @param modelId 模型编号
     * @param datasourceId 数据源编号
     * @param mode 处理模式
     * @param description 描述
     * @param remark 备注
     * @return 记录编号
     */
    private Long deploy(Long modelId, Long datasourceId, String mode, String description, String remark) {
        LogicModelDO current = requireLogicModel(modelId);
        if (!Integer.valueOf(1).equals(current.getEnabled()) || !LogicModelDO.STATUS_PUBLISHED.equals(
                current.getStatus())) {
            throw exception(MODEL_MATERIALIZED_MODEL_UNPUBLISHED);
        }
        // 只冻结本次部署所需的任务输入，不建立模型历史版本。
        LogicModelDO frozen = current;
        hierarchyResolver.validateHierarchyOnPublish(frozen.getLayerId(), frozen.getSubjectDomainId(),
                frozen.getDataDomainId(),
                frozen.getTableType(), frozen.getBizCategoryId());
        List<ModelFieldItem> fields = columnService.listByModelId(modelId);
        if (CollUtil.isEmpty(fields)) {
            throw exception(LOGIC_MODEL_FIELDS_EMPTY);
        }
        typeConfigValidator.validateOnPublish(frozen.getTableType(), typeConfigValidator.parse(current.getTypeConfig()),
                fields);
        var conn = databaseResolver.resolve(datasourceId);
        requireSupported(conn);
        if (StrUtil.isBlank(conn.getConfigurationRevision())) {
            throw exception(MODEL_PUBLISH_CONTENT_CHANGED);
        }
        String tableName = tableNameResolver.buildFullTableName(hierarchyResolver.findLayer(frozen.getLayerId()),
                frozen.getCode());
        var before = ddlExecutor.observeTable(conn, datasourceId, tableName);
        String comment = StrUtil.blankToDefault(frozen.getName(), tableName);
        List<String> ddl;
        if (ModelMaterializedDO.MODE_REBUILD.equals(mode)) {
            if (conn.getProductType() == ProductTypeEnum.DORIS && before.tableExists()) {
                throw exception(MODEL_MATERIALIZED_MODE_INVALID,
                        "Doris 已有表不支持直接删除重建，请使用增量发布；键、分区或分桶调整须另行迁移");
            }
            var create = ddlExecutor.buildCreateTableDdl(tableName, comment, fields, conn, null);
            ddl = conn.getProductType() == ProductTypeEnum.POSTGRESQL
                    ? PostgresSchemaChanges.rebuildPlan(conn.getSchema(), tableName, create, before.tableExists()).steps()
                    .stream().map(SqlScriptPlan.Step::sql).toList() : create;
        } else if (before.tableExists()) {
            ddl = ddlExecutor.buildSynchronizeTableDdl(tableName, comment, conn, fields);
        } else {
            ddl = ddlExecutor.buildCreateTableDdl(tableName, comment, fields, conn, null);
            if (CollUtil.isEmpty(ddl)) {
                throw exception(LOGIC_MODEL_FIELDS_EMPTY);
            }
        }
        if (!ddl.isEmpty()) {
            ddlExecutor.validateDdl(conn, tableName, ddl);
        }
        var attempt = new ModelDeploymentAttemptDO();
        attempt.setModelId(modelId);
        attempt.setDesignRevision(current.getRevision());
        attempt.setDatasourceId(datasourceId);
        attempt.setTargetKey(ddlExecutor.physicalTargetKey(conn, tableName));
        attempt.setSchemaName(conn.getSchema());
        attempt.setTableName(tableName);
        attempt.setConfigurationRevision(conn.getConfigurationRevision());
        attempt.setPublishMode(mode);
        attempt.setBeforeObservationJson(JsonUtils.toJsonString(before));
        attempt.setFieldsJson(JsonUtils.toJsonString(fields));
        attempt.setModelSnapshotJson(ModelDeploymentAttemptStore.taskModelInput(current));
        attempt.setDdlSql(String.join(";\n", ddl));
        var desired = ModelMaterializedDO.builder().modelId(modelId).datasourceId(datasourceId)
                .datasourceType(conn.getProductType().name()).datasourceName(conn.getName())
                .schemaName(conn.getSchema()).tableName(tableName)
                .modelName(frozen.getCode()).modelAlias(frozen.getName()).publishMode(mode).ddlSql(attempt.getDdlSql())
                .designRevision(current.getRevision()).fieldsJson(attempt.getFieldsJson())
                .description(description).remark(remark).build();
        // 先以短平台事务保存部署意图并占用目标，再执行外部 DDL，使进程中断后仍有可对账的依据。
        attempt = attemptStore.reserve(desired, attempt);
        executeAndFinish(attempt, frozen, fields, conn, ddl, before);
        return desired.getId();
    }

    /**
     * 执行冻结的外部部署计划并核验目标结构，保存确定结果或待对账状态。
     *
     * @param attempt 当前部署尝试
     * @param snapshot 快照
     * @param fields 字段定义
     * @param conn 已解析的数据源连接信息
     * @param ddl DDL
     * @param before 变更前状态
     */
    private void executeAndFinish(ModelDeploymentAttemptDO attempt,
            LogicModelDO snapshot, List<ModelFieldItem> fields,
            ModelingDatabaseResolver.ConnectionInfo conn, List<String> ddl,
            ModelDdlExecutor.TableObservation before) {
        boolean entered = false;
        try {
            verifyRoute(attempt, databaseResolver.resolve(attempt.getDatasourceId()));
            if (!ddl.isEmpty()) {
                attemptStore.executing(attempt);
                entered = true;
                ddlExecutor.executeDdl(conn, ddl, before, ignored -> attemptStore.heartbeat(attempt));
            }
            var after = ddlExecutor.observeTable(conn, attempt.getDatasourceId(), attempt.getTableName());
            attemptStore.heartbeat(attempt);
            withExpectedObservation(attempt, conn, snapshot.getName(), fields, before,
                    (locked, matches) -> {
                        if (!matches || !after.sameDatabaseState(locked) || (ddl.isEmpty() && !before.sameDatabaseState(
                                locked))) {
                            throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
                        }
                        attemptStore.finish(attempt, ModelMaterializedDO.STATUS_SUCCESS, "已核验任务输入部署成功");
                    });
        } catch (ModelDdlExecutor.DdlExecutionException failure) {
            // 只有确认回滚或尚未开始才能认定失败；连接中断等不确定结果继续占用目标，防止重建覆盖。
            boolean certain = failure.outcome() == ModelDdlExecutor.DdlOutcome.ROLLED_BACK || failure.outcome() == ModelDdlExecutor.DdlOutcome.NOT_STARTED;
            attemptStore.finish(attempt, certain ? ModelMaterializedDO.STATUS_FAIL : ModelMaterializedDO.STATUS_UNKNOWN,
                    certain ? "外部DDL已确认回滚或尚未开始" : "外部DDL结果待对账，已阻止重建和覆盖");
        } catch (RuntimeException failure) {
            attemptStore.finish(attempt, entered ? ModelMaterializedDO.STATUS_UNKNOWN : ModelMaterializedDO.STATUS_FAIL,
                    entered ? "部署或执行后核验未完成，保留目标占用等待对账" : "部署前目标核验失败，未执行外部DDL");
        }
    }

    /**
     * 接管失联部署并核对冻结前态与目标结构，两次观测一致后记录确定结果。
     *
     * @param id 记录编号
     * @return 接管失联部署并核对冻结前态与目标结构，两次观测一致后记录确定结果文本
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String reconcile(Long id) {
        ModelMaterializedDO row = requireMaterialized(id);
        if (!ModelMaterializedDO.STATUS_UNKNOWN.equals(row.getStatus()) && !ModelMaterializedDO.STATUS_RUNNING.equals(
                row.getStatus())) {
            return row.getStatus();
        }
        if (row.getAttemptId() == null) {
            throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
        }
        var attempt = attemptStore.claimRecovery(row.getAttemptId());
        try {
            var conn = databaseResolver.resolve(attempt.getDatasourceId());
            verifyRoute(attempt, conn);
            var before = JsonUtils.parseObject(attempt.getBeforeObservationJson(),
                    ModelDdlExecutor.TableObservation.class);
            var fields = JsonUtils.parseArray(attempt.getFieldsJson(), ModelFieldItem.class);
            var snapshot = JsonUtils.parseObject(attempt.getModelSnapshotJson(), LogicModelDO.class);
            if (snapshot == null || !Objects.equals(snapshot.getId(), attempt.getModelId())
                    || StrUtil.isBlank(snapshot.getName()) || CollUtil.isEmpty(fields)) {
                throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
            }
            var observed = new ModelDdlExecutor.TableObservation[1];
            var decision = new String[]{ModelMaterializedDO.STATUS_UNKNOWN};
            withExpectedObservation(attempt, conn, snapshot.getName(), fields, before,
                    (locked, matches) -> {
                        attemptStore.heartbeat(attempt);
                        observed[0] = locked;
                        if ("PREPARED".equals(attempt.getPhase()) || before.sameDatabaseState(locked)) {
                            decision[0] = ModelMaterializedDO.STATUS_FAIL;
                        } else if (matches) {
                            decision[0] = ModelMaterializedDO.STATUS_SUCCESS;
                        }
                    });
            attemptStore.heartbeat(attempt);
            // 任务输入复核发生在两次目标观测之间；结束对账前再次受控比对，拒绝把中途变化的结构认定为成功。
            withExpectedObservation(attempt, conn, snapshot.getName(), fields, before,
                    (locked, matches) -> {
                        if (!observed[0].sameDatabaseState(locked)) {
                            throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
                        }
                        if (ModelMaterializedDO.STATUS_SUCCESS.equals(decision[0]) && !matches) {
                            throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
                        }
                        attemptStore.finish(attempt, decision[0], ModelMaterializedDO.STATUS_SUCCESS.equals(decision[0])
                                ? "对账确认目标结构已达到冻结任务输入" : ModelMaterializedDO.STATUS_FAIL.equals(
                                decision[0])
                                ? "对账确认未进入DDL或目标仍保持原冻结状态，可重新发起部署" : "目标与原状态及任务输入均不一致，继续保留未知占用");
                    });
            return decision[0];
        } catch (RuntimeException failure) {
            attemptStore.finish(attempt, ModelMaterializedDO.STATUS_UNKNOWN,
                    "目标路由或结构对账未完成，继续禁止重建和覆盖");
            return ModelMaterializedDO.STATUS_UNKNOWN;
        }
    }

    /**
     * 核对数据源配置修订及物理目标身份是否仍与冻结部署意图一致。
     *
     * @param attempt 当前部署尝试
     * @param conn 已解析的数据源连接信息
     */
    private void verifyRoute(ModelDeploymentAttemptDO attempt, ModelingDatabaseResolver.ConnectionInfo conn) {
        requireSupported(conn);
        if (!Objects.equals(attempt.getConfigurationRevision(), conn.getConfigurationRevision())
                || !Objects.equals(attempt.getSchemaName(), conn.getSchema())
                || !Objects.equals(attempt.getTargetKey(),
                ddlExecutor.physicalTargetKey(conn, attempt.getTableName()))) {
            throw exception(MODEL_PUBLISH_CONTENT_CHANGED);
        }
    }

    /** Doris 首次创建以冻结 CREATE 为布局依据，后续对账不重新采用当前配置生成期望值。 */
    private void withExpectedObservation(ModelDeploymentAttemptDO attempt,
            ModelingDatabaseResolver.ConnectionInfo conn, String comment, List<ModelFieldItem> fields,
            ModelDdlExecutor.TableObservation before,
            java.util.function.BiConsumer<ModelDdlExecutor.TableObservation, Boolean> callback) {
        if (conn.getProductType() == ProductTypeEnum.DORIS && !before.tableExists()) {
            if (StrUtil.isBlank(attempt.getDdlSql())) throw exception(MODEL_MATERIALIZED_OUTCOME_UNKNOWN);
            ddlExecutor.withLockedExpectedObservation(conn, attempt.getTableName(), comment, fields,
                    attempt.getDdlSql(), callback);
        } else {
            ddlExecutor.withLockedExpectedObservation(conn, attempt.getTableName(), comment, fields, callback);
        }
    }

    /**
     * 校验目标数据库产品是否属于当前功能支持范围。
     *
     * @param conn 已解析的数据源连接信息
     */
    private void requireSupported(ModelingDatabaseResolver.ConnectionInfo conn) {
        if (!SchemaChanges.supports(conn.getProductType())) {
            throw exception(MODEL_MATERIALIZED_PRODUCT_UNSUPPORTED);
        }
    }

    /**
     * 规范化操作模式，并拒绝未支持的模式值。
     *
     * @param mode 处理模式
     * @return 规范化操作模式，并拒绝未支持的模式值文本
     */
    private String normalizeMode(String mode) {
        String m = StrUtil.trim(mode);
        if (ModelMaterializedDO.MODE_REBUILD.equalsIgnoreCase(m)) {
            return ModelMaterializedDO.MODE_REBUILD;
        }
        if (ModelMaterializedDO.MODE_INCREMENT.equalsIgnoreCase(m)) {
            return ModelMaterializedDO.MODE_INCREMENT;
        }
        throw exception(MODEL_MATERIALIZED_MODE_INVALID, mode);
    }

    /**
     * 校验逻辑模型。
     *
     * @param modelId 模型编号
     * @return 逻辑模型
     */
    private LogicModelDO requireLogicModel(Long modelId) {
        LogicModelDO model = logicModelMapper.selectById(modelId);
        if (model == null) {
            throw exception(LOGIC_MODEL_NOT_EXISTS);
        }
        return model;
    }

    /**
     * 校验物化。
     *
     * @param id 记录编号
     * @return 模型物化
     */
    private ModelMaterializedDO requireMaterialized(Long id) {
        ModelMaterializedDO row = materializedMapper.selectById(id);
        if (row == null) {
            throw exception(MODEL_MATERIALIZED_NOT_EXISTS);
        }
        return row;
    }

    /**
     * 解析发布状态筛选。
     *
     * @param reqVO 请求参数
     * @return 解析发布状态筛选文本
     */
    private String resolvePublishStatusFilter(ModelMaterializedPageReqVO reqVO) {
        if (StrUtil.isNotBlank(reqVO.getPublishStatus())) {
            return StrUtil.trim(reqVO.getPublishStatus());
        }
        return StrUtil.trim(reqVO.getStatus());
    }

    /**
     * 按发布态收敛逻辑模型 ID：未发布=排除已有物化；其它态=仅保留「最新物化状态」匹配的模型。
     */
    private PublishStatusScope resolveStatusScope(String publishStatus, ModelMaterializedPageReqVO reqVO) {
        boolean targetFilter = reqVO.getDatasourceId() != null || StrUtil.isNotBlank(reqVO.getTableName())
                || StrUtil.isNotBlank(reqVO.getPublishMode());
        if (StrUtil.isBlank(publishStatus) && !targetFilter) {
            return PublishStatusScope.none();
        }
        String status = StrUtil.trim(publishStatus);
        List<ModelMaterializedDO> all = materializedMapper.selectAllForLatestStatus();
        Map<Long, ModelMaterializedDO> latestByModel = new LinkedHashMap<>();
        for (ModelMaterializedDO row : all) {
            if (row.getModelId() == null) {
                continue;
            }
            latestByModel.putIfAbsent(row.getModelId(), row);
        }
        if (ModelMaterializedDO.STATUS_UNPUBLISHED.equalsIgnoreCase(status)) {
            if (targetFilter) {
                return PublishStatusScope.include(Set.of());
            }
            return PublishStatusScope.unpublished(latestByModel.keySet());
        }
        Set<Long> matched = latestByModel.entrySet().stream()
                .filter(e -> StrUtil.isBlank(status) || status.equalsIgnoreCase(e.getValue().getStatus()))
                .filter(e -> reqVO.getDatasourceId() == null || Objects.equals(reqVO.getDatasourceId(),
                        e.getValue().getDatasourceId()))
                .filter(e -> StrUtil.isBlank(reqVO.getTableName()) || StrUtil.containsIgnoreCase(
                        e.getValue().getTableName(), reqVO.getTableName()))
                .filter(e -> StrUtil.isBlank(reqVO.getPublishMode()) || reqVO.getPublishMode()
                        .equalsIgnoreCase(e.getValue().getPublishMode()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return PublishStatusScope.include(matched);
    }

    /**
     * 获取模型物化的分组物化按模型。
     *
     * @param rows 待处理的行数据
     * @return 模型物化的分组物化按模型结果集合
     */
    private Map<Long, List<ModelMaterializedDO>> groupMaterializedByModel(List<ModelMaterializedDO> rows) {
        if (CollUtil.isEmpty(rows)) {
            return Map.of();
        }
        Map<Long, List<ModelMaterializedDO>> map = new LinkedHashMap<>();
        for (ModelMaterializedDO row : rows) {
            if (row.getModelId() == null) {
                continue;
            }
            map.computeIfAbsent(row.getModelId(), k -> new ArrayList<>()).add(row);
        }
        return map;
    }

    /**
     * 转换为列表项。
     *
     * @param model 模型
     * @param mats 物化记录
     * @return 模型物化响应数据
     */
    private ModelMaterializedRespVO toListItem(LogicModelDO model, List<ModelMaterializedDO> mats) {
        ModelMaterializedRespVO item = new ModelMaterializedRespVO();
        item.setModelId(model.getId());
        item.setModelAlias(model.getName());
        item.setModelName(model.getCode());
        item.setLayerId(model.getLayerId());
        item.setSubjectDomainId(model.getSubjectDomainId());
        item.setDataDomainId(model.getDataDomainId());
        item.setBizCategoryId(model.getBizCategoryId());
        item.setTableType(model.getTableType());
        item.setDescription(model.getDescription());
        item.setRemark(model.getRemark());
        item.setCreator(model.getCreator());
        item.setUpdater(model.getUpdater());
        item.setCreateTime(model.getCreateTime());
        item.setUpdateTime(model.getUpdateTime());
        item.setTableCase(model.getTableCase());
        item.setTableNameNotation(model.getTableNameNotation());
        item.setDocumentType(model.getDocumentType());
        item.setDocumentId(model.getDocumentId());
        item.setContactNumber(model.getContactNumber());
        if (CollUtil.isEmpty(mats)) {
            item.setPublishStatus(ModelMaterializedDO.STATUS_UNPUBLISHED);
            item.setStatus(ModelMaterializedDO.STATUS_UNPUBLISHED);
            return item;
        }
        ModelMaterializedDO latest = mats.get(0);
        item.setId(latest.getId());
        item.setMaterializedId(latest.getId());
        item.setPublishStatus(latest.getStatus());
        item.setStatus(latest.getStatus());
        item.setDatasourceId(latest.getDatasourceId());
        item.setDesignRevision(latest.getDesignRevision());
        item.setAttemptId(latest.getAttemptId());
        item.setDatasourceType(latest.getDatasourceType());
        item.setDatasourceName(latest.getDatasourceName());
        item.setSchemaName(latest.getSchemaName());
        item.setTableName(latest.getTableName());
        item.setPublishMode(latest.getPublishMode());
        item.setDdlSql(latest.getDdlSql());
        item.setMessage(latest.getMessage());
        return item;
    }

    /** 产物详情使用其实际部署输入；缺失时不借用当前设计冒充产物。 */
    private void fillDetail(ModelMaterializedRespVO item) {
        if (item.getAttemptId() != null) {
            ModelDeploymentAttemptDO attempt = deploymentAttemptMapper.selectById(item.getAttemptId());
            item.setFields(attempt != null && Objects.equals(attempt.getModelId(), item.getModelId())
                    ? parseFields(attempt.getFieldsJson()) : List.of());
        } else if (item.getMaterializedId() != null) {
            ModelMaterializedDO artifact = materializedMapper.selectById(item.getMaterializedId());
            item.setFields(artifact != null && Objects.equals(artifact.getModelId(), item.getModelId())
                    ? parseFields(artifact.getFieldsJson()) : List.of());
        } else {
            LogicModelDO current = requireLogicModel(item.getModelId());
            item.setFields(LogicModelDO.STATUS_PUBLISHED.equals(current.getStatus())
                    ? columnService.listByModelId(current.getId()) : List.of());
        }
    }

    /**
     * 解析模型物化的字段。
     *
     * @param fields 字段定义
     * @return 解析模型物化的字段结果集合
     */
    private List<ModelFieldItem> parseFields(String fields) {
        return StrUtil.isBlank(fields) ? List.of() : JsonUtils.parseArray(fields, ModelFieldItem.class);
    }

    /**
     * 补充展示。
     *
     * @param items 项
     */
    private void fillPresentation(List<ModelMaterializedRespVO> items) {
        Set<Long> attemptIds = items.stream().map(ModelMaterializedRespVO::getAttemptId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ModelDeploymentAttemptDO> attempts = attemptIds.isEmpty() ? Map.of()
                : deploymentAttemptMapper.selectBatchIds(attemptIds).stream()
                .collect(Collectors.toMap(ModelDeploymentAttemptDO::getId, row -> row));
        List<String> auditIds = new ArrayList<>();
        for (var item : items) {
            auditIds.add(item.getCreator());
            auditIds.add(item.getUpdater());
            var attempt = item.getAttemptId() == null ? null : attempts.get(item.getAttemptId());
            if (attempt != null) {
                item.setPublisher(attempt.getCreator());
                item.setPublishStartTime(attempt.getCreateTime());
                item.setPublishTime(attempt.getFinishedTime());
                item.setPublishDuration(duration(attempt.getCreateTime(), attempt.getFinishedTime()));
                auditIds.add(attempt.getCreator());
            }
        }
        var users = userMap(auditIds);
        for (var item : items) {
            var creator = auditUser(users, item.getCreator());
            var updater = auditUser(users, item.getUpdater());
            var publisher = auditUser(users, item.getPublisher());
            if (creator != null) {
                item.setCreatorName(creator.getNickname());
                item.setCreatorPhone(creator.getMobile());
            }
            if (updater != null) {
                item.setUpdaterName(updater.getNickname());
                item.setUpdaterPhone(updater.getMobile());
            }
            if (publisher != null) {
                item.setPublisherName(publisher.getNickname());
                item.setPublisherPhone(publisher.getMobile());
            }
        }
    }

    /**
     * 获取模型物化的用户映射。
     *
     * @param values 字段值集合
     * @return 处理后的键值映射
     */
    private Map<Long, AdminUserRespDTO> userMap(List<String> values) {
        Set<Long> ids = values.stream().map(this::userId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : adminUserApi.getUserMap(ids);
    }

    /**
     * 获取模型物化的用户编号。
     *
     * @param value 待处理的值
     * @return 用户编号数值
     */
    private Long userId(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 获取模型物化的审计用户。
     *
     * @param users 用户
     * @param value 待处理的值
     * @return 管理端用户响应数据
     */
    private AdminUserRespDTO auditUser(Map<Long, AdminUserRespDTO> users, String value) {
        Long id = userId(value);
        return id == null ? null : users.get(id);
    }

    /**
     * 根据起止时间计算处理耗时。
     *
     * @param start 开始位置或时间
     * @param end 结束位置或时间
     * @return 耗时数值
     */
    private Long duration(LocalDateTime start, LocalDateTime end) {
        return start == null || end == null || end.isBefore(start) ? null : Duration.between(start, end).toMillis();
    }

    /**
     * 获取模型物化的目录节点。
     *
     * @param key 标识键
     * @param name 名称
     * @param type 类型标识
     * @return 模型物化分类节点响应数据
     */
    private ModelMaterializedCatNodeRespVO catalogNode(String key, String name, String type) {
        var result = new ModelMaterializedCatNodeRespVO();
        result.setKey(key);
        result.setName(name);
        result.setType(type);
        result.setChildren(new ArrayList<>());
        return result;
    }

    /**
     * 复制模型物化的目录。
     *
     * @param nodes 节点
     * @param prefix 前缀
     * @param layerId 分层编号
     * @return 复制模型物化的目录结果集合
     */
    private List<ModelMaterializedCatNodeRespVO> copyCatalog(List<LogicTableCatNodeRespVO> nodes, String prefix,
            Long layerId) {
        return nodes.stream().map(node -> {
            var result = catalogNode(prefix + node.getKey(), node.getName(), node.getType());
            result.setLayerId(layerId);
            result.setSourceSystemId(node.getSourceSystemId());
            result.setDataDomainId(node.getDataDomainId());
            result.setSubjectDomainId(node.getSubjectDomainId());
            result.setTableType(node.getTableType());
            result.setChildren(
                    copyCatalog(node.getChildren() == null ? List.of() : node.getChildren(), prefix, layerId));
            return result;
        }).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 补充目录分层编号集合。
     *
     * @param node 节点
     * @return 补充目录分层编号集合结果集合
     */
    private List<Long> fillCatalogLayerIds(ModelMaterializedCatNodeRespVO node) {
        if (node.getLayerId() != null) {
            return List.of(node.getLayerId());
        }
        List<Long> layerIds = node.getChildren().stream().flatMap(child -> fillCatalogLayerIds(child).stream())
                .distinct().toList();
        node.setLayerIds(layerIds);
        return layerIds;
    }

    /**
     * 补充目录表类型。
     *
     * @param node 节点
     * @param tableType 表类型
     */
    private void fillCatalogTableType(ModelMaterializedCatNodeRespVO node, String tableType) {
        node.setTableType(tableType);
        node.getChildren().forEach(child -> fillCatalogTableType(child, tableType));
    }

    /**
     * 补充模型层级。
     *
     * @param list 待处理列表
     */
    private void fillModelHierarchy(List<ModelMaterializedRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        Set<Long> modelIds = list.stream().map(ModelMaterializedRespVO::getModelId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, LogicModelDO> modelMap = CollUtil.isEmpty(modelIds) ? Map.of()
                : logicModelMapper.selectBatchIds(modelIds).stream()
                .collect(Collectors.toMap(LogicModelDO::getId, m -> m, (a, b) -> a));
        for (ModelMaterializedRespVO item : list) {
            LogicModelDO model = modelMap.get(item.getModelId());
            if (model == null) {
                continue;
            }
            item.setLayerId(model.getLayerId());
            item.setSubjectDomainId(model.getSubjectDomainId());
            item.setDataDomainId(model.getDataDomainId());
            item.setBizCategoryId(model.getBizCategoryId());
            if (StrUtil.isBlank(item.getTableType())) {
                item.setTableType(model.getTableType());
            }
            if (StrUtil.isBlank(item.getModelAlias())) {
                item.setModelAlias(model.getName());
            }
            if (StrUtil.isBlank(item.getModelName())) {
                item.setModelName(model.getCode());
            }
        }
        hierarchyResolver.fillHierarchy(list);
    }

    private record PublishStatusScope(boolean unpublished, Set<Long> excludeModelIds,
                                      Set<Long> includeModelIds, boolean empty) {

        /**
         * 获取发布状态范围的空范围。
         *
         * @return 发布状态范围
         */
        static PublishStatusScope none() {
            return new PublishStatusScope(false, null, null, false);
        }

        /**
         * 获取发布状态范围的未发布。
         *
         * @param excludeModelIds 排除模型编号集合
         * @return 发布状态范围
         */
        static PublishStatusScope unpublished(Set<Long> excludeModelIds) {
            return new PublishStatusScope(true, excludeModelIds, null, false);
        }

        /**
         * 获取发布状态范围的包含。
         *
         * @param includeModelIds 包含模型编号集合
         * @return 发布状态范围
         */
        static PublishStatusScope include(Set<Long> includeModelIds) {
            if (CollUtil.isEmpty(includeModelIds)) {
                return new PublishStatusScope(false, null, Set.of(), true);
            }
            return new PublishStatusScope(false, null, includeModelIds, false);
        }
    }

}

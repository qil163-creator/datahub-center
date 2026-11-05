package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedTableMutationExecutor;
import cn.iocoder.yudao.framework.dbswitch.core.service.QueryFilterOperator;
import cn.iocoder.yudao.framework.dbswitch.core.service.QueryFilterSql;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelStatisticsRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelDataCountRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDomainDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCodeRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDataChangeMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterCodeRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaTableRegisterReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.service.metadata.MetaImportService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;

import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 主数据模型 Service 实现类
 *
 * 模型归属主数据域；结构修改保存为草稿，再次发布时同步目标表。
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class MasterModelServiceImpl implements MasterModelService {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    /**
     * 表名/字段名格式：小写字母开头，小写字母数字下划线
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    @Resource
    private MasterModelMapper modelMapper;

    @Resource
    private MasterColumnMapper columnMapper;

    @Resource
    private MasterDomainMapper domainMapper;

    @Resource
    private MasterCodeRuleMapper codeRuleMapper;

    @Resource
    private MasterDataChangeMapper dataChangeMapper;

    @Resource
    private MasterDistributeMapper distributeMapper;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private MasterModelSchemaPublisher schemaPublisher;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private MetaImportService metaImportService;

    @Resource
    private MetaTableMapper metaTableMapper;

    @Resource
    private MetaColumnMapper metaColumnMapper;

    @Resource
    private ILogRecordService logRecordService;

    // ==================== 模型 CRUD ====================

    /**
     * 创建主数据模型草稿，仅保存平台定义，字段与主键可在发布前完善。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createModel(MasterModelSaveReqVO createReqVO) {
        validateSaveReq(createReqVO);
        MasterModelDO model = BeanUtils.toBean(createReqVO, MasterModelDO.class);
        model.setId(null);
        model.setCatalogId(null);
        model.setStatus(MasterModelDO.STATUS_UNRELEASED);
        model.setVersion(1);
        model.setApprovalEnabled(Boolean.TRUE.equals(createReqVO.getApprovalEnabled()));
        model.setApprovalProcessKey(StrUtil.blankToDefault(createReqVO.getApprovalProcessKey(), null));
        model.setPublishState(MasterModelDO.PUBLISH_READY);
        try {
            modelMapper.insert(model);
        } catch (DuplicateKeyException ex) {
            throw exception(MASTER_MODEL_TABLE_NAME_DUPLICATE);
        }
        replaceColumns(model.getId(), createReqVO.getColumns());
        return model.getId();
    }

    /**
     * 保存模型修改并保持原发布状态；曾发布模型只能修改业务信息，结构仍冻结。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateModel(MasterModelSaveReqVO updateReqVO) {
        // 先锁域再锁模型，与域删除使用相同的父级锁边界。
        validateSaveReq(updateReqVO);
        MasterModelDO old = modelMapper.selectForUpdate(updateReqVO.getId());
        if (old == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        validatePublishReady(old);
        if (old.getStatus() == null || !Set.of(MasterModelDO.STATUS_UNRELEASED, MasterModelDO.STATUS_RELEASED,
                MasterModelDO.STATUS_OFFLINE).contains(old.getStatus())) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        boolean publishedBefore = wasPublished(old);
        if (publishedBefore && (!Objects.equals(old.getDatabaseId(), updateReqVO.getDatabaseId())
                || !Objects.equals(old.getTableName(), updateReqVO.getTableName()))) {
            throw exception(MASTER_MODEL_STRUCTURE_FROZEN);
        }
        int version = old.getVersion() == null ? 1 : old.getVersion();
        if ((publishedBefore || updateReqVO.getExpectedVersion() != null)
                && !Objects.equals(version, updateReqVO.getExpectedVersion())) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        List<MasterColumnDO> oldColumns = publishedBefore ? columnMapper.selectListByModelId(old.getId()) : List.of();
        boolean structureChanged = publishedBefore && !sameColumns(oldColumns, updateReqVO.getColumns());
        if (structureChanged) {
            // 先停用平台维护入口，物理表仍保留旧结构；外部 DDL 不在此事务执行。
            validateNoDistribute(old.getId());
            if (!dataChangeMapper.selectActive(old.getId()).isEmpty()) {
                throw exception(MASTER_DATA_ROW_PENDING);
            }
            if (codeRuleMapper.selectCount(MasterCodeRuleDO::getModelId, old.getId()) > 0) {
                throw exception(MASTER_MODEL_HAS_CODE_RULE);
            }
            // 缺失发布记录的历史对象须先重新发布，避免草稿丢失物理表绑定依据。
            if (old.getReleaseTime() == null || version == Integer.MAX_VALUE) {
                throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
            }
        }
        MasterModelDO update = BeanUtils.toBean(updateReqVO, MasterModelDO.class);
        update.setCatalogId(old.getCatalogId());
        update.setStatus(structureChanged ? MasterModelDO.STATUS_UNRELEASED : old.getStatus());
        update.setVersion(structureChanged ? version + 1 : old.getVersion());
        update.setReleaseTime(old.getReleaseTime());
        update.setReleaseUserId(old.getReleaseUserId());
        update.setApprovalEnabled(Boolean.TRUE.equals(updateReqVO.getApprovalEnabled()));
        update.setApprovalProcessKey(StrUtil.blankToDefault(updateReqVO.getApprovalProcessKey(), null));
        try {
            modelMapper.updateById(update);
        } catch (DuplicateKeyException ex) {
            throw exception(MASTER_MODEL_TABLE_NAME_DUPLICATE);
        }
        // 显式清空可选流程标识，避免 updateById 的非空字段策略保留旧值。
        modelMapper.update(null, Wrappers.lambdaUpdate(MasterModelDO.class)
                .set(MasterModelDO::getApprovalProcessKey, update.getApprovalProcessKey())
                .eq(MasterModelDO::getId, old.getId()));
        if (!publishedBefore || structureChanged) {
            replaceColumns(old.getId(), updateReqVO.getColumns());
        }
        if (structureChanged) {
            auditModel(old, "保存结构草稿", update.getStatus(), update.getVersion(), "APPLIED",
                    DigestUtil.sha256Hex(JsonUtils.toJsonString(oldColumns)),
                    DigestUtil.sha256Hex(JsonUtils.toJsonString(updateReqVO.getColumns())));
        }
    }

    /**
     * 保存前锁定启用域，并检查目标及已填写字段；草稿允许空字段和暂未设置主键。
     */
    private void validateSaveReq(MasterModelSaveReqVO reqVO) {
        if (reqVO.getDomainId() == null) {
            throw exception(MASTER_MODEL_DOMAIN_REQUIRED);
        }
        MasterDomainDO domain = domainMapper.selectForUpdate(reqVO.getDomainId());
        if (domain == null) {
            throw exception(MASTER_DOMAIN_NOT_EXISTS);
        }
        if (!Integer.valueOf(0).equals(domain.getStatus())) {
            throw exception(MASTER_DOMAIN_DISABLED);
        }
        if (StrUtil.isNotBlank(reqVO.getApprovalProcessKey())
                && !reqVO.getApprovalProcessKey().matches("[a-zA-Z_][a-zA-Z0-9_:.\\-]{0,99}")) {
            throw exception(MASTER_MODEL_APPROVAL_CONFIG_INVALID);
        }
        if (!NAME_PATTERN.matcher(reqVO.getTableName()).matches()) {
            throw exception(MASTER_MODEL_TABLE_NAME_INVALID);
        }
        MasterModelDO exists = modelMapper.selectByDatabaseAndTableName(reqVO.getDatabaseId(), reqVO.getTableName());
        if (exists != null && !exists.getId().equals(reqVO.getId())) {
            throw exception(MASTER_MODEL_TABLE_NAME_DUPLICATE);
        }
        Set<String> names = new HashSet<>();
        for (MasterColumnVO column : reqVO.getColumns()) {
            if (!NAME_PATTERN.matcher(column.getName()).matches()) {
                throw exception(MASTER_MODEL_COLUMN_NAME_INVALID, column.getName());
            }
            if (!names.add(column.getName())) {
                throw exception(MASTER_MODEL_COLUMN_NAME_DUPLICATE, column.getName());
            }
        }
    }

    /**
     * 字段定义保存为草稿；已发布模型的目标结构在再次发布时更新。
     */
    private void replaceColumns(Long modelId, List<MasterColumnVO> columns) {
        columnMapper.deleteByModelId(modelId);
        int sort = 0;
        for (MasterColumnVO columnVO : columns) {
            MasterColumnDO column = BeanUtils.toBean(columnVO, MasterColumnDO.class);
            column.setId(null);
            column.setMasterModelId(modelId);
            column.setSort(sort++);
            if (column.getNullable() == null) {
                column.setNullable(1);
            }
            if (column.getPk() == null) {
                column.setPk(0);
            }
            // 主键不可为空
            if (column.getPk() == 1) {
                column.setNullable(0);
            }
            columnMapper.insert(column);
        }
    }

    /**
     * 删除主数据模型（已发布或存在派发任务时拒绝）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(Long id) {
        deleteLocked(id);
    }

    /**
     * 批量删除主数据草稿或已下线模型（保留物理表）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteModelList(List<Long> ids) {
        ids.stream().distinct().sorted().forEach(this::deleteLocked);
    }

    /**
     * 删除已锁定。
     */
    private void deleteLocked(Long id) {
        MasterModelDO model = modelMapper.selectForUpdate(id);
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        validatePublishReady(model);
        if (!Integer.valueOf(MasterModelDO.STATUS_UNRELEASED).equals(model.getStatus())
                && !Integer.valueOf(MasterModelDO.STATUS_OFFLINE).equals(model.getStatus())) {
            throw exception(MASTER_MODEL_STRUCTURE_FROZEN);
        }
        validateNoDistribute(id);
        if (codeRuleMapper.selectCount(MasterCodeRuleDO::getModelId, id) > 0) {
            throw exception(MASTER_MODEL_HAS_CODE_RULE);
        }
        if (!dataChangeMapper.selectActive(id).isEmpty()) {
            throw exception(MASTER_DATA_ROW_PENDING);
        }
        // 草稿和已下线模型仅逻辑删除平台定义，保留目标物理表与历史变更审计。
        modelMapper.deleteById(id);
        columnMapper.deleteByModelId(id);
        auditModel(model, "删除模型定义（保留物理表）", model.getStatus(), model.getVersion(), "APPLIED", null, null);
    }

    /**
     * 获得主数据模型（含字段）
     */
    @Override
    public MasterModelRespVO getModel(Long id) {
        return convertToResp(validateModelExists(id));
    }

    /**
     * 获得目录节点下的主数据模型（含字段），未建模时返回 null
     */
    @Override
    public MasterModelRespVO getModelByCatalog(Long catalogId) {
        MasterModelDO model = modelMapper.selectByCatalogId(catalogId);
        return model == null ? null : convertToResp(model);
    }

    /**
     * 获得已发布模型精简列表（派发任务选择用）
     */
    @Override
    public List<MasterModelRespVO> getReleasedModelList() {
        List<MasterModelDO> list = modelMapper.selectListByStatus(MasterModelDO.STATUS_RELEASED);
        List<MasterModelRespVO> result = list.stream().filter(MasterModelServiceImpl::isPublishReady)
                .map(this::toListResp).toList();
        enrichSources(result);
        return result;
    }

    /**
     * 分页查询主数据模型。
     */
    @Override
    public PageResult<MasterModelRespVO> getModelPage(MasterModelPageReqVO req) {
        PageResult<MasterModelDO> page = modelMapper.selectPage(req);
        List<MasterModelRespVO> result = page.getList().stream().map(this::toListResp).toList();
        enrichSources(result);
        enrichListMetadata(result);
        return new PageResult<>(result, page.getTotal());
    }

    /**
     * 维护页面在平台内共享，仅允许读取已发布且结构结果明确的模型。
     */
    @Override
    public PageResult<MasterModelRespVO> getMaintenanceModelPage(MasterModelPageReqVO req) {
        PageResult<MasterModelDO> page = modelMapper.selectMaintenancePage(req);
        List<MasterModelRespVO> result = page.getList().stream().map(this::toListResp).toList();
        enrichSources(result);
        enrichListMetadata(result);
        enrichMaintenanceTimes(result);
        return new PageResult<>(result, page.getTotal());
    }

    /**
     * 读取维护模型。
     */
    @Override
    public MasterModelRespVO getMaintenanceModel(Long id) {
        MasterModelRespVO result = convertToResp(validateModelReleased(id));
        result.setFieldCount((long) result.getColumns().size());
        enrichMaintenanceTimes(List.of(result));
        return result;
    }

    /**
     * 读取维护数据数量。
     */
    @Override
    public MasterModelDataCountRespVO getMaintenanceDataCount(Long id) {
        Long actorId = requireCountActor();
        return countModelData(validateModelReleased(id), actorId);
    }

    /**
     * 补充主数据模型的维护时间。
     */
    private void enrichMaintenanceTimes(List<MasterModelRespVO> models) {
        if (models.isEmpty()) {
            return;
        }
        Map<Long, java.time.LocalDateTime> latest = new HashMap<>();
        dataChangeMapper.selectLatestAppliedByModels(models.stream().map(MasterModelRespVO::getId).toList())
                .forEach(change -> latest.put(change.getModelId(), change.getAppliedAt()));
        models.forEach(model -> model.setLastMaintenanceTime(latest.get(model.getId())));
    }

    /**
     * 与主数据查询一样在平台内共享；仅按需读取已绑定物理表，不接受客户端表名。
     */
    @Override
    public MasterModelDataCountRespVO getDataCount(Long id) {
        Long actorId = requireCountActor();
        return countModelData(validateModelExists(id), actorId);
    }

    /**
     * 校验主数据模型的数量操作人，不满足约束时拒绝当前操作。
     */
    private static Long requireCountActor() {
        Long actorId = SecurityFrameworkUtils.getLoginUserId();
        if (actorId == null || actorId <= 0) {
            throw new IllegalStateException("缺少有效的用户上下文");
        }
        return actorId;
    }

    /**
     * 统计模型数据。
     */
    private MasterModelDataCountRespVO countModelData(MasterModelDO model, Long actorId) {
        // 主数据域和模型在平台内共享，沿用主数据查询权限及服务端绑定表范围。
        Long id = model.getId();
        MasterModelDataCountRespVO response = new MasterModelDataCountRespVO();
        if (!isPublishReady(model)) {
            response.setMessage("模型发布结果尚未确认，请先核查发布结果");
            return response;
        }
        if (!wasPublished(model)) {
            response.setDataCount(0L);
            response.setAvailable(true);
            return response;
        }
        cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.Operation operation = null;
        try {
            var connection = databaseResolver.resolve(model.getDatabaseId(), MASTER_MODEL_DATABASE_NOT_EXISTS);
            if (connection.getProductType() != ProductTypeEnum.POSTGRESQL) {
                response.setMessage("该数据源暂不支持已验证的限时数据量查询");
                return response;
            }
            String scope = "master-model-count:" + actorId + ":" + id;
            String quotaScope = "master-model-count:" + actorId;
            var budget = new cn.iocoder.yudao.framework.dbswitch.data.query.OperationBudget(
                    java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(1),
                    1, 1024, 1024, 1);
            operation = dbSwitchRuntime.queries().register(scope, quotaScope, java.util.UUID.randomUUID().toString(),
                    java.util.UUID.randomUUID(), budget);
            operation.bindWorker();
            var spec = databaseResolver.queryConnectionSpec(connection);
            try (var session = operation.openSession(spec,
                    cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.WORKLOAD)) {
                String select = "SELECT 1 FROM " + connection.getProductType()
                        .quoteSchemaTableName(connection.getSchema(), model.getTableName());
                // 计数包装、方言和执行生命周期均复用 DB Switch，不读取或返回业务行。
                String countSql = session.dialect().getPageModel(select).getPageCountSql();
                try (var cursor = session.query(scope, countSql, List.of())) {
                    var rows = cursor.fetch(scope, cursor.id(), 1).rows();
                    if (rows.size() != 1 || rows.getFirst().size() != 1 || rows.getFirst().getFirst() == null) {
                        throw new IllegalStateException("数据量结果不可用");
                    }
                    long count = new java.math.BigDecimal(rows.getFirst().getFirst().toString()).longValueExact();
                    if (count < 0) {
                        throw new IllegalStateException("数据量结果不可用");
                    }
                    response.setDataCount(count);
                    response.setAvailable(true);
                }
            }
        } catch (java.sql.SQLException | RuntimeException failure) {
            response.setDataCount(null);
            response.setAvailable(false);
            response.setMessage("数据量暂时无法查询，请检查数据源或稍后重试");
            log.warn("[getDataCount][主数据模型({}) 数据量查询失败，异常类型 {}]", id,
                    failure.getClass().getSimpleName());
        } finally {
            if (operation != null) {
                operation.workerExited();
                operation.awaitTermination();
            }
        }
        return response;
    }

    /** 字段统计使用一条聚合查询，创建人使用一次批量通讯录 RPC。 */
    private void enrichListMetadata(List<MasterModelRespVO> models) {
        if (models.isEmpty()) {
            return;
        }
        List<Long> modelIds = models.stream().map(MasterModelRespVO::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        columnMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MasterColumnDO>()
                        .select("master_model_id", "COUNT(*) AS field_count")
                        .in("master_model_id", modelIds).groupBy("master_model_id"))
                .forEach(row -> counts.put(((Number) row.get("master_model_id")).longValue(),
                        ((Number) row.get("field_count")).longValue()));
        models.forEach(model -> model.setFieldCount(counts.getOrDefault(model.getId(), 0L)));
        Set<Long> creatorIds = new HashSet<>();
        for (MasterModelRespVO model : models) {
            Long creatorId = creatorId(model.getCreator());
            if (creatorId != null) {
                creatorIds.add(creatorId);
            }
        }
        if (creatorIds.isEmpty()) {
            return;
        }
        try {
            Map<Long, AdminUserRespDTO> users = adminUserApi.getUserMap(creatorIds);
            for (MasterModelRespVO model : models) {
                Long creatorId = creatorId(model.getCreator());
                AdminUserRespDTO user = creatorId == null ? null : users.get(creatorId);
                model.setCreatorName(user == null ? null : user.getNickname());
            }
        } catch (RuntimeException failure) {
            log.warn("[enrichListMetadata][主数据模型创建人查询失败，异常类型 {}]", failure.getClass().getSimpleName());
        }
    }

    /**
     * 解析主数据模型记录中的创建人编号。
     */
    private static Long creatorId(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * 查询主数据模型精简列表。
     */
    @Override
    public List<MasterModelRespVO> getSimpleList() {
        List<MasterModelRespVO> result = modelMapper.selectList().stream().map(this::toListResp).toList();
        enrichSources(result);
        return result;
    }

    /**
     * 统计主数据模型状态。
     */
    @Override
    public MasterModelStatisticsRespVO getStatistics(Long domainId) {
        MasterModelStatisticsRespVO resp = new MasterModelStatisticsRespVO();
        resp.setTotal(modelMapper.selectStatisticsCount(domainId, null));
        resp.setReleased(modelMapper.selectStatisticsCount(domainId, MasterModelDO.STATUS_RELEASED));
        resp.setDraft(modelMapper.selectStatisticsCount(domainId, MasterModelDO.STATUS_UNRELEASED));
        resp.setOffline(modelMapper.selectStatisticsCount(domainId, MasterModelDO.STATUS_OFFLINE));
        return resp;
    }

    // ==================== 发布 / 取消发布 ====================

    /**
     * 发布模型：在绑定数据源中创建物理表
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void releaseModel(Long id) {
        MasterModelDO model = validateModelExists(id);
        validatePublishReady(model);
        validateNotReleased(model);
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(id);
        if (CollUtil.isEmpty(columns)) {
            throw exception(MASTER_MODEL_NO_COLUMN);
        }
        List<ColumnDescription> columnDescriptions = new ArrayList<>();
        List<String> pkNames = new ArrayList<>();
        for (MasterColumnDO column : columns) {
            ColumnDescription description = column.toColumnDescription();
            columnDescriptions.add(description);
            if (description.isPk()) {
                pkNames.add(description.getFieldName());
            }
        }
        if (pkNames.isEmpty()) {
            throw exception(MASTER_MODEL_NO_PK);
        }
        MasterDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(model.getDatabaseId(), MASTER_MODEL_DATABASE_NOT_EXISTS);
        MasterDomainDO domain = domainMapper.selectById(model.getDomainId());
        if (domain == null) {
            throw exception(MASTER_DOMAIN_NOT_EXISTS);
        }
        if (!Integer.valueOf(0).equals(domain.getStatus())) {
            throw exception(MASTER_DOMAIN_DISABLED);
        }
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        if (wasPublished(model)) {
            if (Integer.valueOf(MasterModelDO.STATUS_UNRELEASED).equals(model.getStatus())) {
                publishStructureDraft(model, columns, conn);
                return;
            }
            // 未改结构的下线模型只确认既有表，不重建或覆盖数据。
            verifyPhysicalColumns(model, columns, conn, metaDataService);
            if (modelMapper.claimPublish(model) != 1) {
                throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
            }
            if (modelMapper.completePublish(id, false) != 1) {
                throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
            }
            auditModel(model, "重新发布", MasterModelDO.STATUS_RELEASED, model.getVersion(), "APPLIED", null, null);
            ingestMasterMetadata(model, columns, conn);
            return;
        }
        List<String> ddlList = GenerateSqlUtils.getDDLCreateTableSQL(
                dbSwitchRuntime.products().metadata(conn.getProductType()), columnDescriptions, pkNames,
                conn.getSchema(), model.getTableName(), model.getTableCn(),
                false, null);
        if (modelMapper.claimPublish(model) != 1) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        try {
            for (String sql : ddlList) {
                metaDataService.executeSql(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), sql);
            }
        } catch (Exception ex) {
            // 超时或后续语句失败时建表可能成功，保留表和意图以供核查。
            modelMapper.markPublishUnknown(id);
            log.warn("[releaseModel][模型({}) 外部发布结果待核查，异常类型 {}]", id, ex.getClass().getSimpleName());
            throw exception(MASTER_MODEL_PUBLISH_UNCERTAIN);
        }
        if (modelMapper.completePublish(id, false) != 1) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        auditModel(model, "首次发布", MasterModelDO.STATUS_RELEASED, model.getVersion(), "APPLIED", null, null);
        ingestMasterMetadata(model, columns, conn);
    }

    /**
     * 发布结构草稿。
     */
    private void publishStructureDraft(MasterModelDO model, List<MasterColumnDO> columns,
            MasterDatabaseResolver.ConnectionInfo conn) {
        if (modelMapper.claimPublish(model) != 1) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        MasterModelSchemaPublisher.Result result = schemaPublisher.synchronize(conn, model.getTableName(),
                model.getTableCn(), columns);
        if (result.status() == MasterModelSchemaPublisher.Status.UNKNOWN) {
            modelMapper.markPublishUnknown(model.getId());
            auditModel(model, "发布结构草稿", model.getStatus(), model.getVersion(), "UNKNOWN", null, null);
            throw exception(MASTER_MODEL_PUBLISH_UNCERTAIN);
        }
        if (result.status() == MasterModelSchemaPublisher.Status.NOT_APPLIED) {
            if (modelMapper.resetPublishAfterRollback(model.getId()) != 1) {
                throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
            }
            auditModel(model, "发布结构草稿", model.getStatus(), model.getVersion(), "NOT_APPLIED", null, null);
            throw exception(MASTER_MODEL_RELEASE_FAILED,
                    "目标结构未更新，请检查字段、现有数据和表依赖；目前仅支持 PostgreSQL 增量发布");
        }
        if (modelMapper.completePublish(model.getId(), false) != 1) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        auditModel(model, "发布结构草稿", MasterModelDO.STATUS_RELEASED, model.getVersion(), "APPLIED", null, null);
        ingestMasterMetadata(model, columns, conn);
    }

    /**
     * 核查不确定发布结果并确认物理表。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void verifyRelease(Long id) {
        MasterModelDO model = validateModelExists(id);
        if ((!MasterModelDO.PUBLISH_APPLYING.equals(model.getPublishState())
                && !MasterModelDO.PUBLISH_UNKNOWN.equals(model.getPublishState())) || model.getStatus() == null
                || !Set.of(MasterModelDO.STATUS_UNRELEASED, MasterModelDO.STATUS_RELEASED,
                MasterModelDO.STATUS_OFFLINE).contains(model.getStatus())) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(id);
        MasterDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(model.getDatabaseId(),
                MASTER_MODEL_DATABASE_NOT_EXISTS);
        IMetaDataByJdbcService metadata = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        if (wasPublished(model) && Integer.valueOf(MasterModelDO.STATUS_UNRELEASED).equals(model.getStatus())) {
            var result = schemaPublisher.verify(conn, model.getTableName(), model.getTableCn(), columns);
            if (result.status() != MasterModelSchemaPublisher.Status.APPLIED) {
                throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
            }
        } else {
            verifyPhysicalColumns(model, columns, conn, metadata);
        }
        if (modelMapper.completePublish(id, true) != 1) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
        auditModel(model, "核查发布结果", MasterModelDO.STATUS_RELEASED, model.getVersion(), "APPLIED", null, null);
        ingestMasterMetadata(model, columns, conn);
    }

    /** 核查列和主键，通过 DB Switch 元数据接口读取物理结构。 */
    private void verifyPhysicalColumns(MasterModelDO model, List<MasterColumnDO> expected,
            MasterDatabaseResolver.ConnectionInfo conn, IMetaDataByJdbcService metadata) {
        try {
            List<ColumnDescription> actual = metadata.queryTableColumnMeta(conn.getJdbcUrl(), conn.getUsername(),
                    conn.getPassword(), conn.getSchema(), model.getTableName());
            List<String> keys = metadata.queryTablePrimaryKeys(conn.getJdbcUrl(), conn.getUsername(),
                    conn.getPassword(), conn.getSchema(), model.getTableName());
            Map<String, ColumnDescription> actualByName = convertMap(actual, ColumnDescription::getFieldName);
            Set<String> expectedKeys = new HashSet<>();
            if (actualByName.size() != expected.size()) {
                throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
            }
            for (MasterColumnDO column : expected) {
                ColumnDescription match = actualByName.get(column.getName());
                if (match == null || match.getMetaData() == null
                        || !Objects.equals(column.getFieldType(), match.getMetaData().getType())) {
                    throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
                }
                if (Integer.valueOf(1).equals(column.getPk())) {
                    expectedKeys.add(column.getName());
                }
                if (Integer.valueOf(0).equals(column.getNullable()) && match.isNullable()) {
                    throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
                }
                ColumnMetaData actualType = match.getMetaData();
                if ((Integer.valueOf(ColumnMetaData.TYPE_STRING).equals(column.getFieldType())
                        || Integer.valueOf(ColumnMetaData.TYPE_BIGNUMBER).equals(column.getFieldType()))
                        && column.getFieldLength() != null && column.getFieldLength() > 0
                        && actualType.getLength() > 0 && actualType.getLength() < column.getFieldLength()) {
                    throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
                }
                if (Integer.valueOf(ColumnMetaData.TYPE_BIGNUMBER).equals(column.getFieldType())
                        && column.getFieldScale() != null && column.getFieldScale() >= 0
                        && actualType.getPrecision() != column.getFieldScale()) {
                    throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
                }
            }
            if (keys == null || !expectedKeys.equals(new HashSet<>(keys))) {
                throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
            }
        } catch (Exception ex) {
            throw exception(MASTER_MODEL_PUBLISH_VERIFY_FAILED);
        }
    }

    /**
     * 发布成功后回写元数据，并打主数据标记、回填字段绑定的数据元。
     */
    private void ingestMasterMetadata(MasterModelDO model, List<MasterColumnDO> columns,
            MasterDatabaseResolver.ConnectionInfo conn) {
        try {
            MetaTableRegisterReqVO registerReq = new MetaTableRegisterReqVO();
            registerReq.setDatasourceId(model.getDatabaseId());
            registerReq.setDbName(StrUtil.blankToDefault(conn.getSchema(), "default"));
            registerReq.setTableName(model.getTableName());
            registerReq.setTableComment(model.getTableCn());
            registerReq.setSourceSystemName("主数据管理");
            registerReq.setColumns(columns.stream().map(column -> {
                MetaTableRegisterReqVO.RegisterColumn item = new MetaTableRegisterReqVO.RegisterColumn();
                item.setColumnName(column.getName());
                item.setColumnComment(column.getCommentStr());
                item.setDataType(masterFieldTypeName(column.getFieldType()));
                item.setDataLength(column.getFieldLength() == null ? null : column.getFieldLength().longValue());
                item.setDataScale(column.getFieldScale());
                item.setNullableFlag(column.getNullable());
                item.setPkFlag(column.getPk());
                return item;
            }).toList());
            Long tableId = metaImportService.registerTable(registerReq);
            metaTableMapper.updateById(MetaTableDO.builder().id(tableId).masterFlag(1).build());
            for (MasterColumnDO column : columns) {
                if (column.getStandardElementId() == null) {
                    continue;
                }
                MetaColumnDO metaColumn = metaColumnMapper.selectByTableIdAndName(tableId, column.getName());
                if (metaColumn == null) {
                    continue;
                }
                metaColumnMapper.updateById(MetaColumnDO.builder()
                        .id(metaColumn.getId())
                        .standardElementId(column.getStandardElementId())
                        .build());
            }
        } catch (Exception ex) {
            log.warn("[ingestMasterMetadata][主数据模型({}) 发布后元数据回写失败，异常类型 {}]",
                    model.getId(), ex.getClass().getSimpleName());
        }
    }

    /**
     * 将主数据字段类型转换为展示名称。
     */
    private String masterFieldTypeName(Integer fieldType) {
        if (fieldType == null) {
            return "varchar";
        }
        return switch (fieldType) {
            case ColumnMetaData.TYPE_BIGNUMBER -> "decimal";
            case ColumnMetaData.TYPE_DATE -> "date";
            case ColumnMetaData.TYPE_BOOLEAN -> "boolean";
            case ColumnMetaData.TYPE_INTEGER -> "int";
            case ColumnMetaData.TYPE_TIMESTAMP -> "timestamp";
            case ColumnMetaData.TYPE_TIME -> "time";
            default -> "varchar";
        };
    }

    /**
     * 下线模型并保留物理表（存在派发任务时拒绝）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelReleaseModel(Long id) {
        MasterModelDO model = modelMapper.selectForUpdate(id);
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        validatePublishReady(model);
        if (model.getStatus() == null || model.getStatus() != MasterModelDO.STATUS_RELEASED) {
            throw exception(MASTER_MODEL_NOT_RELEASED);
        }
        // 存在派发任务（无论是否发布）都不允许取消发布，与 srt 语义一致
        validateNoDistribute(id);
        if (!dataChangeMapper.selectActive(id).isEmpty()) {
            throw exception(MASTER_DATA_ROW_PENDING);
        }
        // 下线保留结构、数据与发布时间，取消发布不执行 DROP TABLE。
        modelMapper.update(null, Wrappers.lambdaUpdate(MasterModelDO.class)
                .set(MasterModelDO::getStatus, MasterModelDO.STATUS_OFFLINE)
                .eq(MasterModelDO::getId, id));
    }

    // ==================== 字段拾取 ====================

    /**
     * 从数据源已有表提取字段定义（建模时拾取字段用）
     */
    @Override
    public List<MasterColumnVO> getTableColumns(Long databaseId, String tableName) {
        MasterDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(databaseId, MASTER_MODEL_DATABASE_NOT_EXISTS);
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
        List<ColumnDescription> descriptions = metaDataService.queryTableColumnMeta(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema(), tableName);
        List<String> pks = metaDataService.queryTablePrimaryKeys(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(), conn.getSchema(), tableName);
        List<MasterColumnVO> result = new ArrayList<>();
        for (ColumnDescription description : descriptions) {
            ColumnMetaData meta = description.getMetaData();
            MasterColumnVO vo = new MasterColumnVO();
            vo.setName(description.getFieldName());
            vo.setCommentStr(description.getRemarks());
            vo.setFieldType(meta.getType());
            vo.setFieldLength(meta.getLength());
            vo.setFieldScale(meta.getPrecision());
            boolean pk = pks != null && pks.contains(description.getFieldName());
            vo.setPk(pk ? 1 : 0);
            vo.setNullable(pk || !description.isNullable() ? 0 : 1);
            result.add(vo);
        }
        return result;
    }

    // ==================== 内部方法 ====================

    /**
     * 校验模型存在且已发布（数据维护、派发执行用）
     */
    @Override
    public MasterModelDO validateModelReleased(Long id) {
        MasterModelDO model = validateModelExists(id);
        validatePublishReady(model);
        if (model.getStatus() == null || model.getStatus() != MasterModelDO.STATUS_RELEASED) {
            throw exception(MASTER_MODEL_NOT_RELEASED);
        }
        return model;
    }

    /**
     * 校验模型记录存在，不存在时抛出业务异常。
     */
    private MasterModelDO validateModelExists(Long id) {
        MasterModelDO model = modelMapper.selectById(id);
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        return model;
    }

    /**
     * 校验主数据模型的非已发布，不满足约束时拒绝当前操作。
     */
    private void validateNotReleased(MasterModelDO model) {
        if (model.getStatus() != null && model.getStatus() == MasterModelDO.STATUS_RELEASED) {
            throw exception(MASTER_MODEL_RELEASED_CANNOT_MODIFY);
        }
        if (!Integer.valueOf(MasterModelDO.STATUS_UNRELEASED).equals(model.getStatus())
                && !Integer.valueOf(MasterModelDO.STATUS_OFFLINE).equals(model.getStatus())) {
            throw exception(MASTER_MODEL_PUBLISH_CONFLICT);
        }
    }

    /**
     * 校验主数据模型的无分发，不满足约束时拒绝当前操作。
     */
    private void validateNoDistribute(Long modelId) {
        if (distributeMapper.selectCountByModelId(modelId) > 0) {
            throw exception(MASTER_MODEL_HAS_DISTRIBUTE);
        }
    }

    /**
     * 转换目标响应。
     */
    private MasterModelRespVO convertToResp(MasterModelDO model) {
        MasterModelRespVO vo = toListResp(model);
        vo.setColumns(convertList(columnMapper.selectListByModelId(model.getId()),
                column -> BeanUtils.toBean(column, MasterColumnVO.class)));
        enrichSources(List.of(vo));
        if (model.getReleaseUserId() != null) {
            AdminUserRespDTO user = adminUserApi.getUser(model.getReleaseUserId()).getCheckedData();
            if (user != null) {
                vo.setReleaseUserName(user.getNickname());
            }
        }
        return vo;
    }

    /**
     * 转换列表响应。
     */
    private MasterModelRespVO toListResp(MasterModelDO model) {
        MasterModelRespVO vo = BeanUtils.toBean(model, MasterModelRespVO.class);
        MasterDomainDO domain = model.getDomainId() == null ? null : domainMapper.selectById(model.getDomainId());
        vo.setDomainName(domain == null ? null : domain.getName());
        vo.setVersion(model.getVersion() == null ? 1 : model.getVersion());
        vo.setApprovalEnabled(Boolean.TRUE.equals(model.getApprovalEnabled()));
        vo.setPublishState(model.getPublishState() == null ? MasterModelDO.PUBLISH_READY : model.getPublishState());
        MasterCodeRuleDO rule = codeRuleMapper.selectEnabled(model.getId());
        vo.setEnabledCodeField(rule == null ? null : rule.getFieldName());
        vo.setMaintenanceSupported(false);
        vo.setQueryFilterOperators(List.of());
        vo.setMaintenanceUnsupportedReason("暂时无法确认数据源维护能力，请刷新后重试");
        return vo;
    }

    /** 每个请求只获取一次无密码列表，响应只返回名称、能力和安全提示。 */
    private void enrichSources(List<MasterModelRespVO> models) {
        if (models.isEmpty()) {
            return;
        }
        try {
            List<DatabaseConnectionRespDTO> sources = databaseApi.getDatabaseList(null).getCheckedData();
            if (sources == null) {
                return;
            }
            Map<Long, DatabaseConnectionRespDTO> databases = convertMap(sources, DatabaseConnectionRespDTO::getId);
            for (MasterModelRespVO model : models) {
                DatabaseConnectionRespDTO source = databases.get(model.getDatabaseId());
                if (source == null) {
                    model.setMaintenanceUnsupportedReason("模型绑定的数据源不存在或不可见，请检查数据源配置");
                    continue;
                }
                model.setDatabaseName(source.getName());
                model.setDatabaseType(source.getProductType());
                model.setDatabaseHost(source.getHost());
                if (StrUtil.isBlank(source.getProductType())) {
                    model.setMaintenanceUnsupportedReason("数据源产品类型尚未识别，请检查数据源类型配置");
                    continue;
                }
                try {
                    ProductTypeEnum product = ProductTypeEnum.valueOf(source.getProductType());
                    model.setQueryFilterOperators(java.util.Arrays.stream(QueryFilterOperator.values())
                            .filter(operator -> QueryFilterSql.supports(product, operator))
                            .map(Enum::name).toList());
                    boolean supported = GuardedTableMutationExecutor.supports(product);
                    model.setMaintenanceSupported(supported);
                    model.setMaintenanceUnsupportedReason(supported ? null
                            : "该数据源产品（" + product.name() + "）暂不支持原子维护，目前仅支持 PostgreSQL 数据源");
                } catch (IllegalArgumentException invalidProduct) {
                    model.setMaintenanceUnsupportedReason(
                            "数据源产品类型编码无法识别，请检查数据源配置并确认服务版本一致");
                }
            }
        } catch (RuntimeException failure) {
            log.warn("[enrichSources][获取数据源列表失败，异常类型 {}]", failure.getClass().getSimpleName());
        }
    }

    /**
     * 判断主数据模型是否发布就绪。
     */
    private static boolean isPublishReady(MasterModelDO model) {
        return model.getPublishState() == null || MasterModelDO.PUBLISH_READY.equals(model.getPublishState());
    }

    /** 复用操作日志组件；事务成功后记录主体、版本与摘要，不记录数据、DDL 或连接信息。 */
    private void auditModel(MasterModelDO before, String action, Integer afterStatus, Integer afterVersion,
            String result, String beforeHash, String afterHash) {
        Map<String, Object> extra = new java.util.LinkedHashMap<>();

        extra.put("actorId", SecurityFrameworkUtils.getLoginUserId());
        extra.put("modelId", String.valueOf(before.getId()));
        extra.put("beforeStatus", before.getStatus());
        extra.put("afterStatus", afterStatus);
        extra.put("beforeVersion", before.getVersion());
        extra.put("afterVersion", afterVersion);
        extra.put("beforeHash", beforeHash);
        extra.put("afterHash", afterHash);
        extra.put("result", result);
        extra.put("reason", action);
        extra.put("correlation", TracerUtils.getTraceId());
        String resultText = switch (result) {
            case "APPLIED" -> "已生效";
            case "NOT_APPLIED" -> "未生效";
            case "UNKNOWN" -> "待核查";
            default -> result;
        };
        LogRecord record = LogRecord.builder().type("主数据模型").subType(action).bizNo(String.valueOf(before.getId()))
                .action(action + "，主数据模型【" + StrUtil.blankToDefault(before.getTableCn(), before.getTableName())
                        + "】；版本【" + Objects.toString(before.getVersion(), "未发布") + "】→【"
                        + Objects.toString(afterVersion, "未发布") + "】；结果：" + resultText)
                .extra(JsonUtils.toJsonString(extra)).build();
        Runnable write = () -> {
            try {
                logRecordService.record(record);
            } catch (RuntimeException failure) {
                log.warn("[auditModel][模型({}) 操作日志写入失败，异常类型 {}]", before.getId(),
                        failure.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 在当前事务成功提交后触发主数据模型后续处理。
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
     * 校验主数据模型的发布就绪，不满足约束时拒绝当前操作。
     */
    private void validatePublishReady(MasterModelDO model) {
        if (!isPublishReady(model)) {
            throw exception(MASTER_MODEL_PUBLISH_UNCERTAIN);
        }
    }

    /**
     * 判断是否曾经已发布。
     */
    private static boolean wasPublished(MasterModelDO model) {
        return model.getReleaseTime() != null || Integer.valueOf(MasterModelDO.STATUS_RELEASED)
                .equals(model.getStatus())
                || Integer.valueOf(MasterModelDO.STATUS_OFFLINE).equals(model.getStatus());
    }

    /**
     * 比对两组字段结构是否一致。
     */
    static boolean sameColumns(List<MasterColumnDO> current, List<MasterColumnVO> requested) {
        if (current.size() != requested.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            MasterColumnDO old = current.get(i);
            MasterColumnVO next = requested.get(i);
            if (!Objects.equals(old.getName(), next.getName()) || !Objects.equals(old.getFieldType(),
                    next.getFieldType())
                    || !Objects.equals(old.getFieldLength(), next.getFieldLength()) || !Objects.equals(
                    old.getFieldScale(), next.getFieldScale())
                    || !Objects.equals(StrUtil.nullToEmpty(old.getCommentStr()),
                    StrUtil.nullToEmpty(next.getCommentStr()))
                    || !Objects.equals(old.getPk() == null ? 0 : old.getPk(), next.getPk() == null ? 0 : next.getPk())
                    || !Objects.equals(
                    Integer.valueOf(1).equals(old.getPk()) ? 0 : old.getNullable() == null ? 1 : old.getNullable(),
                    Integer.valueOf(1).equals(next.getPk()) ? 0 : next.getNullable() == null ? 1 : next.getNullable())
                    || !Objects.equals(old.getStandardElementId(), next.getStandardElementId())) {
                return false;
            }
        }
        return true;
    }

}

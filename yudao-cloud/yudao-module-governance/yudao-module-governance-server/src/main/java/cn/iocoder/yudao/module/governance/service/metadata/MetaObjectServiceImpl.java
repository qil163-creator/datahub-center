package cn.iocoder.yudao.module.governance.service.metadata;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import cn.iocoder.yudao.module.governance.service.standardmapping.StandardMappingCommandService;
import cn.iocoder.yudao.module.governance.service.standardmapping.StandardMappingTargetSnapshot;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.dataforge.api.metadata.MetadataCollectionApi;
import cn.iocoder.yudao.module.dataforge.api.metadata.dto.MetadataSourceOptionsDTO.ConnectionOption;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaSnapshotViews;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.dept.dto.DeptRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagRespVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-元数据对象浏览 Service 实现
 * 管理端元数据在平台内可见，沿控制器细粒度权限与 Mapper 数据范围门禁，不输出连接凭据。
 *
 * 业务/管理属性维护为全量覆盖语义（表单回显后提交），逐属性写 change_source=手工 的变更明细；
 * 手工变更不推进结构语义版本、不产生快照（结构版本只属于采集/导入/回滚链路）
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class MetaObjectServiceImpl implements MetaObjectService {

    /** 手工维护属性的变更属性名 */
    private static final String PROP_CUSTOM_COMMENT = "CUSTOM_COMMENT";

    private static final String PROP_BUSINESS_NAME = "BUSINESS_NAME";

    private static final String PROP_BUSINESS_DEFINITION = "BUSINESS_DEFINITION";

    private static final String PROP_MEASURING_UNIT = "MEASURING_UNIT";

    private static final String PROP_LAYER = "LAYER";

    private static final String PROP_MASTER_FLAG = "MASTER_FLAG";

    private static final String PROP_TEMPORARY_FLAG = "TEMPORARY_FLAG";

    private static final String PROP_STORAGE_TYPE = "STORAGE_TYPE";

    private static final String PROP_SENSITIVE_FLAG = "SENSITIVE_FLAG";

    private static final String PROP_SUBJECT_DOMAIN = "SUBJECT_DOMAIN";

    private static final String PROP_TECH_LEADER = "TECH_LEADER";

    private static final String PROP_BUSINESS_LEADER = "BUSINESS_LEADER";

    private static final String PROP_OWNER_DEPT = "OWNER_DEPT";

    private static final String PROP_STANDARD_MAP = "STANDARD_MAP";

    private static final String PROP_SAFETY_LEVEL = "SAFETY_LEVEL";

    private static final String PROP_OBJECT_STATUS = "OBJECT_STATUS";

    private static final String PROP_PUBLISH_STATUS = "PUBLISH_STATUS";

    private static final String PROP_REMARK = "REMARK";

    @Resource
    private MetaDbMapper dbMapper;

    @Resource
    private MetaTableMapper tableMapper;

    @Resource
    private MetaColumnMapper columnMapper;

    @Resource
    private MetaTableSnapshotMapper snapshotMapper;

    @Resource
    private MetaChangeMapper changeMapper;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private MetadataCollectionApi metadataCollectionApi;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private DeptApi deptApi;

    @Resource
    private MetaTagMapper tagMapper;

    @Resource
    private MetaTagRelMapper tagRelMapper;

    @Resource
    private StandardMappingMapper mappingMapper;

    @Resource
    private StandardElementMapper standardElementMapper;

    @Resource
    private StandardMappingCommandService mappingCommandService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private MetaCollectInstanceMapper instanceMapper;

    @Resource
    private MetaCollectTaskMapper collectTaskMapper;

    // ==================== 树与浏览 ====================

    /**
     * 元数据树：来源系统 → 数据源 → 库（含待确认删除对象，前端打警示标）
     */
    @Override
    public List<MetaObjectTreeNodeVO> getObjectTree() {
        List<MetaDbDO> dbs = dbMapper.selectListForTree();
        Map<Long, String> datasourceNames = loadDatasourceNames();
        // 来源系统 → 数据源 → 库 三级分组（保持库名有序）
        Map<Long, MetaObjectTreeNodeVO> systemNodes = new LinkedHashMap<>();
        Map<String, MetaObjectTreeNodeVO> datasourceNodes = new LinkedHashMap<>();
        List<MetaObjectTreeNodeVO> roots = new ArrayList<>();
        for (MetaDbDO db : dbs) {
            Long systemId = db.getSourceSystemId() == null
                    ? MetaTablePageReqVO.UNASSIGNED_SOURCE_SYSTEM_ID : db.getSourceSystemId();
            MetaObjectTreeNodeVO systemNode = systemNodes.computeIfAbsent(systemId, key -> {
                MetaObjectTreeNodeVO node = new MetaObjectTreeNodeVO();
                node.setKey(MetaObjectTreeNodeVO.TYPE_SYSTEM + ":" + key);
                node.setType(MetaObjectTreeNodeVO.TYPE_SYSTEM);
                node.setId(key);
                node.setName(StrUtil.blankToDefault(db.getSourceSystemName(), "未指定来源系统"));
                roots.add(node);
                return node;
            });
            String datasourceKey = systemId + ":" + db.getDatasourceId();
            MetaObjectTreeNodeVO datasourceNode = datasourceNodes.computeIfAbsent(datasourceKey, key -> {
                MetaObjectTreeNodeVO node = new MetaObjectTreeNodeVO();
                node.setKey(MetaObjectTreeNodeVO.TYPE_DATASOURCE + ":" + db.getDatasourceId());
                node.setType(MetaObjectTreeNodeVO.TYPE_DATASOURCE);
                node.setId(db.getDatasourceId());
                String name = MetaImportedDisplay.read(db.getDisplaySnapshot())
                        .fallback(datasourceNames.get(db.getDatasourceId()), "datasourceName");
                node.setName(StrUtil.blankToDefault(name, "数据源#" + db.getDatasourceId()));
                node.setDbType(db.getDbType());
                systemNode.getChildren().add(node);
                return node;
            });
            MetaObjectTreeNodeVO dbNode = new MetaObjectTreeNodeVO();
            dbNode.setKey(MetaObjectTreeNodeVO.TYPE_DB + ":" + db.getId());
            dbNode.setType(MetaObjectTreeNodeVO.TYPE_DB);
            dbNode.setId(db.getId());
            dbNode.setName(db.getDbName());
            dbNode.setDbType(db.getDbType());
            dbNode.setTableCount(db.getTableCount());
            dbNode.setObjectStatus(db.getObjectStatus());
            datasourceNode.getChildren().add(dbNode);
        }
        // 采集任务拥有尚未采出库表的来源及连接，仍作为可筛选的空节点展示。
        for (var task : collectTaskMapper.selectList(new LambdaQueryWrapperX<MetaCollectTaskDO>()
                .orderByAsc(MetaCollectTaskDO::getSourceSystemId).orderByAsc(MetaCollectTaskDO::getDatasourceId))) {
            Long systemId = task.getSourceSystemId() == null
                    ? MetaTablePageReqVO.UNASSIGNED_SOURCE_SYSTEM_ID : task.getSourceSystemId();
            var systemNode = systemNodes.computeIfAbsent(systemId, key -> {
                var node = new MetaObjectTreeNodeVO();
                node.setKey("system:" + key);
                node.setType(MetaObjectTreeNodeVO.TYPE_SYSTEM);
                node.setId(key);
                node.setName(StrUtil.blankToDefault(task.getSourceSystemName(), "未指定来源系统"));
                roots.add(node);
                return node;
            });
            String key = systemId + ":" + task.getDatasourceId();
            datasourceNodes.computeIfAbsent(key, ignored -> {
                var node = new MetaObjectTreeNodeVO();
                node.setKey("datasource:" + task.getDatasourceId());
                node.setType(MetaObjectTreeNodeVO.TYPE_DATASOURCE);
                node.setId(task.getDatasourceId());
                node.setName(StrUtil.blankToDefault(task.getDatasourceName(), "数据源#" + task.getDatasourceId()));
                node.setDbType(task.getDbType());
                systemNode.getChildren().add(node);
                return node;
            });
        }
        roots.sort(Comparator.comparing(MetaObjectTreeNodeVO::getId));
        return roots;
    }

    /**
     * 数据源名称映射（dataforge 精简列表，不含密码）；RPC 失败时降级为编号展示
     */
    private Map<Long, String> loadDatasourceNames() {
        try {
            List<DatabaseConnectionRespDTO> list = databaseApi.getDatabaseList(null).getCheckedData();
            return convertMap(list, DatabaseConnectionRespDTO::getId, DatabaseConnectionRespDTO::getName);
        } catch (Exception ex) {
            log.warn("[loadDatasourceNames][获取数据源展示名称失败，使用编号展示]");
            return Map.of();
        }
    }

    /**
     * 表元数据分页（填充库名/类型/来源系统冗余展示信息）
     */
    @Override
    public PageResult<MetaTableRespVO> getTablePage(MetaTablePageReqVO pageReqVO) {
        PageResult<MetaTableDO> pageResult = tableMapper.selectPage(pageReqVO);
        PageResult<MetaTableRespVO> result = BeanUtils.toBean(pageResult, MetaTableRespVO.class);
        fillDbInfo(result.getList());
        Map<Long, MetaTableDO> states = convertMap(pageResult.getList(), MetaTableDO::getId);
        result.getList().forEach(view -> fillState(view, states.get(view.getId())));
        enrichDisplay(result.getList());
        return result;
    }

    /**
     * 表元数据详情
     */
    @Override
    public MetaTableRespVO getTableDetail(Long id) {
        MetaTableDO table = validateTableExists(id);
        MetaTableRespVO respVO = BeanUtils.toBean(table, MetaTableRespVO.class);
        fillDbInfo(List.of(respVO));
        fillState(respVO, table);
        enrichDisplay(List.of(respVO));
        return respVO;
    }

    /**
     * 填充库名/数据库类型/来源系统名称冗余展示信息
     */
    private void fillDbInfo(List<MetaTableRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        Set<Long> dbIds = new HashSet<>(convertList(list, MetaTableRespVO::getDbId));
        Map<Long, MetaDbDO> dbMap = convertMap(dbMapper.selectBatchIds(dbIds), MetaDbDO::getId);
        for (MetaTableRespVO respVO : list) {
            MetaDbDO db = dbMap.get(respVO.getDbId());
            if (db != null) {
                respVO.setDbName(db.getDbName());
                respVO.setSchemaName(db.getSchemaName());
                respVO.setDbType(db.getDbType());
                respVO.setSourceSystemName(db.getSourceSystemName());
            }
        }
    }

    // ==================== 业务属性维护（手工变更留痕） ====================

    /**
     * 维护表的业务/管理属性（全量覆盖业务属性，逐属性记录手工变更明细；采集属性不可改）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTableBiz(MetaTableUpdateBizReqVO reqVO) {
        MetaTableDO table = lockTable(reqVO.getId());
        validateEditable(table.getObjectStatus());
        validateUnpublished(table);
        if (!Objects.equals(table.getBizRevision(), reqVO.getBizRevision())) {
            throw exception(META_BIZ_CONCURRENT_CHANGED);
        }
        // NOT NULL 标志位缺省沿用旧值，其余字段全量覆盖（null 即置空）
        Integer masterFlag = ObjectUtil.defaultIfNull(reqVO.getMasterFlag(), table.getMasterFlag());
        Integer temporaryFlag = ObjectUtil.defaultIfNull(reqVO.getTemporaryFlag(), table.getTemporaryFlag());
        Integer sensitiveFlag = ObjectUtil.defaultIfNull(reqVO.getSensitiveFlag(), table.getSensitiveFlag());

        List<MetaChangeDO> changes = new ArrayList<>();
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_CUSTOM_COMMENT, table.getCustomComment(), reqVO.getCustomComment());
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_BUSINESS_NAME, table.getBusinessName(), reqVO.getBusinessName());
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_BUSINESS_DEFINITION, table.getBusinessDefinition(), reqVO.getBusinessDefinition());
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_LAYER, table.getLayer(), reqVO.getLayer());
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_MASTER_FLAG, toStr(table.getMasterFlag()), toStr(masterFlag));
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_TEMPORARY_FLAG, toStr(table.getTemporaryFlag()), toStr(temporaryFlag));
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_STORAGE_TYPE, table.getStorageType(), reqVO.getStorageType());
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_SENSITIVE_FLAG, toStr(table.getSensitiveFlag()), toStr(sensitiveFlag));
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_SUBJECT_DOMAIN, toStr(table.getSubjectDomainId()), toStr(reqVO.getSubjectDomainId()));
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_TECH_LEADER, toStr(table.getTechLeaderUserId()), toStr(reqVO.getTechLeaderUserId()));
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_BUSINESS_LEADER, toStr(table.getBusinessLeaderUserId()), toStr(reqVO.getBusinessLeaderUserId()));
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_OWNER_DEPT, toStr(table.getOwnerDeptId()), toStr(reqVO.getOwnerDeptId()));
        trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                PROP_REMARK, table.getRemark(), reqVO.getRemark());

        // 显式 set 全量覆盖（updateById 会跳过 null，无法支持置空解除主题域/负责人等场景）
        int updated = tableMapper.update(null, Wrappers.lambdaUpdate(MetaTableDO.class)
                .set(MetaTableDO::getCustomComment, reqVO.getCustomComment())
                .set(MetaTableDO::getBusinessName, reqVO.getBusinessName())
                .set(MetaTableDO::getBusinessDefinition, reqVO.getBusinessDefinition())
                .set(MetaTableDO::getLayer, reqVO.getLayer())
                .set(MetaTableDO::getMasterFlag, masterFlag)
                .set(MetaTableDO::getTemporaryFlag, temporaryFlag)
                .set(MetaTableDO::getStorageType, reqVO.getStorageType())
                .set(MetaTableDO::getSensitiveFlag, sensitiveFlag)
                .set(MetaTableDO::getSubjectDomainId, reqVO.getSubjectDomainId())
                .set(MetaTableDO::getSubjectDomainName, reqVO.getSubjectDomainName())
                .set(MetaTableDO::getTechLeaderUserId, reqVO.getTechLeaderUserId())
                .set(MetaTableDO::getTechLeaderPhone, reqVO.getTechLeaderPhone())
                .set(MetaTableDO::getBusinessLeaderUserId, reqVO.getBusinessLeaderUserId())
                .set(MetaTableDO::getBusinessLeaderPhone, reqVO.getBusinessLeaderPhone())
                .set(MetaTableDO::getOwnerDeptId, reqVO.getOwnerDeptId())
                .set(MetaTableDO::getRemark, reqVO.getRemark())
                .set(MetaTableDO::getBizRevision, table.getBizRevision() + 1)
                .set(MetaTableDO::getUpdater, Objects.toString(SecurityFrameworkUtils.getLoginUserId(), "metadata"))
                .set(MetaTableDO::getUpdateTime, LocalDateTime.now())
                .eq(MetaTableDO::getId, table.getId())
                .eq(MetaTableDO::getBizRevision, reqVO.getBizRevision()));
        if (updated != 1) {
            throw exception(META_BIZ_CONCURRENT_CHANGED);
        }
        if (!changes.isEmpty()) {
            changeMapper.insertBatch(changes);
        }
    }

    /**
     * 维护字段的业务/管理属性（全量覆盖业务属性，逐属性记录手工变更明细）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateColumnBiz(MetaColumnUpdateBizReqVO reqVO) {
        // 先读取不可变归属，再按库、表、字段顺序加锁，与采集、发布和定版保持一致。
        MetaColumnDO observed = columnMapper.selectById(reqVO.getId());
        validateEditable(observed == null ? null : observed.getObjectStatus());
        MetaTableDO table = lockTable(observed.getTableId());
        validateEditable(table.getObjectStatus());
        validateUnpublished(table);
        MetaColumnDO column = columnMapper.selectByIdForUpdate(reqVO.getId());
        validateEditable(column == null ? null : column.getObjectStatus());
        if (!Objects.equals(column.getRevision(), reqVO.getRevision())) {
            throw exception(META_BIZ_CONCURRENT_CHANGED);
        }
        List<MetaChangeDO> changes = new ArrayList<>();
        Consumer<MetaChangeDO> owner = change -> {
            change.setTableId(column.getTableId());
            change.setDbId(column.getDbId());
        };
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_CUSTOM_COMMENT, column.getCustomComment(), reqVO.getCustomComment());
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_BUSINESS_NAME, column.getBusinessName(), reqVO.getBusinessName());
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_BUSINESS_DEFINITION, column.getBusinessDefinition(), reqVO.getBusinessDefinition());
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_MEASURING_UNIT, column.getMeasuringUnit(), reqVO.getMeasuringUnit());
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_STANDARD_MAP, toStr(column.getStandardElementId()), toStr(reqVO.getStandardElementId()));
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_SAFETY_LEVEL, toStr(column.getSafetyLevelId()), toStr(reqVO.getSafetyLevelId()));
        Integer sensitiveFlag = ObjectUtil.defaultIfNull(reqVO.getSensitiveFlag(), column.getSensitiveFlag());
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_SENSITIVE_FLAG, toStr(column.getSensitiveFlag()), toStr(sensitiveFlag));
        trackChange(changes, owner, MetaChangeDO.OBJECT_TYPE_COLUMN, column.getId(),
                PROP_REMARK, column.getRemark(), reqVO.getRemark());

        // 显式 set 全量覆盖（支持置空解除标准映射/安全分级）
        int updated = columnMapper.update(null, Wrappers.lambdaUpdate(MetaColumnDO.class)
                .set(MetaColumnDO::getCustomComment, reqVO.getCustomComment())
                .set(MetaColumnDO::getBusinessName, reqVO.getBusinessName())
                .set(MetaColumnDO::getBusinessDefinition, reqVO.getBusinessDefinition())
                .set(MetaColumnDO::getMeasuringUnit, reqVO.getMeasuringUnit())
                .set(MetaColumnDO::getSafetyLevelId, reqVO.getSafetyLevelId())
                .set(MetaColumnDO::getSensitiveFlag, sensitiveFlag)
                .set(MetaColumnDO::getRemark, reqVO.getRemark())
                .set(MetaColumnDO::getRevision, column.getRevision() + 1)
                .set(MetaColumnDO::getUpdater, Objects.toString(SecurityFrameworkUtils.getLoginUserId(), "metadata"))
                .set(MetaColumnDO::getUpdateTime, LocalDateTime.now())
                .eq(MetaColumnDO::getId, column.getId())
                .eq(MetaColumnDO::getRevision, reqVO.getRevision()));
        if (updated != 1) {
            throw exception(META_BIZ_CONCURRENT_CHANGED);
        }
        if (!changes.isEmpty()) {
            changeMapper.insertBatch(changes);
        }
        syncColumnMapping(column, reqVO.getStandardElementId());
    }

    /**
     * 同步字段映射。
     */
    private void syncColumnMapping(MetaColumnDO column, Integer standardElementId) {
        StandardMappingDO current = mappingMapper.selectConfirmedByTargetForUpdate(
                StandardMappingDO.TARGET_TYPE_METADATA_COLUMN,
                StandardMappingTargetSnapshot.targetUrn(column));
        if (current != null && Objects.equals(current.getElementId(), standardElementId)) {
            return;
        }
        if (standardElementId == null) {
            if (current != null) {
                mappingCommandService.revoke(current.getId(), "字段手工解除落标");
            }
            columnMapper.update(null, Wrappers.lambdaUpdate(MetaColumnDO.class)
                    .eq(MetaColumnDO::getId, column.getId()).set(MetaColumnDO::getStandardElementId, null));
            return;
        }
        mappingCommandService.confirm(StandardMappingDO.TARGET_TYPE_METADATA_COLUMN,
                StandardMappingTargetSnapshot.targetUrn(column),
                standardElementId, column.getId(),
                StandardMappingDO.SOURCE_MANUAL, "字段手工落标", column.getColumnName());
    }

    /**
     * 值有变化时记录一条手工变更明细（不推进结构版本）
     */
    private void trackChange(List<MetaChangeDO> changes, MetaTableDO table, int objectType, Long objectId,
            String property, String before, String after) {
        trackChange(changes, change -> {
            change.setTableId(table.getId());
            change.setDbId(table.getDbId());
        }, objectType, objectId, property, before, after);
    }

    /**
     * 值有变化时记录一条手工变更明细（不推进结构版本）
     */
    private void trackChange(List<MetaChangeDO> changes, Consumer<MetaChangeDO> ownerFiller,
            int objectType, Long objectId, String property, String before, String after) {
        if (Objects.equals(StrUtil.nullToEmpty(before), StrUtil.nullToEmpty(after))) {
            return;
        }
        MetaChangeDO change = MetaChangeDO.builder()
                .changeSource(MetaChangeDO.SOURCE_MANUAL)
                .objectType(objectType)
                .objectId(objectId)
                .changeType(MetaChangeDO.TYPE_MODIFY)
                .property(property)
                .beforeValue(StrUtil.maxLength(before, 2000))
                .afterValue(StrUtil.maxLength(after, 2000))
                .operator(toStr(SecurityFrameworkUtils.getLoginUserId()))
                .build();
        ownerFiller.accept(change);
        changes.add(change);
    }

    // ==================== 发布与定版 ====================

    /**
     * 发布表：校验必填治理属性（注释、业务负责人），独立更新发布状态并留痕；
     *  目录候选/资产/共享一律引用定版版本，采集流程不感知定版
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishTable(Long id, Integer stateRevision) {
        changePublishState(id, stateRevision, true);
    }

    /**
     * 取消发布：保留定版指针并留痕；已编目资产不自动删除
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unpublishTable(Long id, Integer stateRevision) {
        changePublishState(id, stateRevision, false);
    }

    /**
     * 变更发布状态。
     */
    private void changePublishState(Long id, Integer revision, boolean published) {
        MetaTableDO table = lockTable(id);
        validateStateRevision(table, revision);
        if (published) {
            if (!Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)) {
                throw exception(META_TABLE_PENDING_CANNOT_PUBLISH);
            }
            String reason = validatePublishable(table);
            if (reason != null) {
                throw exception(META_TABLE_PUBLISH_VALIDATE_FAIL, reason);
            }
        } else if (!Objects.equals(table.getPublishStatus(), MetaTableDO.PUBLISH_STATUS_YES)) {
            throw exception(META_TABLE_NOT_PUBLISHED);
        }
        // 按库、表、字段顺序锁定，与业务维护及采集串行，发布校验和状态提交处于同一事务。
        columnMapper.selectListByTableIdForUpdate(id);
        int next = published ? MetaTableDO.PUBLISH_STATUS_YES : MetaTableDO.PUBLISH_STATUS_NO;
        int count = tableMapper.update(null, stateUpdate(table)
                .set(MetaTableDO::getPublishStatus, next)
                .set(MetaTableDO::getPublishTime, published ? LocalDateTime.now() : null)
                .set(MetaTableDO::getPublisher, published ? toStr(SecurityFrameworkUtils.getLoginUserId()) : null));
        requireStateUpdate(count);
        auditState(table, PROP_PUBLISH_STATUS, toStr(table.getPublishStatus()), toStr(next), table.getStructVersion());
    }

    /**
     * 指定结构版本定版，确认时业务属性另存只读镜像
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finalizeTable(MetaTableFinalizeReqVO request) {
        // 人员及安全连接选项在加锁前读取；冻结过程中不执行远程调用。
        MetaTableDO observed = validateTableExists(request.getId());
        DisplayReferences display = loadDisplayReferences(List.of(BeanUtils.toBean(observed, MetaTableRespVO.class)),
                BeanUtils.toBean(columnMapper.selectListByTableId(observed.getId()), MetaColumnRespVO.class));
        MetaTableDO table = lockTable(request.getId());
        validateStateRevision(table, request.getStateRevision());
        validateEditable(table.getObjectStatus());
        MetaTableSnapshotDO snapshot = requireSnapshot(table.getId(), request.getSnapshotId());
        // 与采集、标准关联和业务字段维护复用同一组对象锁；同名重建字段绝不继承旧身份。
        columnMapper.selectListByTableIdForUpdate(table.getId());
        LocalDateTime now = LocalDateTime.now();
        String actor = toStr(SecurityFrameworkUtils.getLoginUserId());
        MetaFinalizedDetailRespVO frozen = freeze(table, snapshot, display);
        frozen.getTable().setFinalized(true);
        frozen.getTable().setFinalizedVersion(snapshot.getStructVersion());
        frozen.getTable().setFinalizedSnapshotId(snapshot.getId());
        frozen.getTable().setPublishedVersion(snapshot.getStructVersion());
        frozen.getTable().setPublishedSnapshotId(snapshot.getId());
        frozen.getTable().setFinalizedTime(now);
        frozen.getTable().setFinalizedBy(actor);
        frozen.getSnapshot().setFinalized(true);
        int count = tableMapper.update(null, stateUpdate(table)
                .set(MetaTableDO::getPublishedVersion, snapshot.getStructVersion())
                .set(MetaTableDO::getPublishedSnapshotId, snapshot.getId())
                .set(MetaTableDO::getFinalizedTime, now)
                .set(MetaTableDO::getFinalizedBy, actor)
                .setSql("finalized_snapshot = CAST({0} AS jsonb)", JsonUtils.toJsonString(frozen)));
        requireStateUpdate(count);
        auditState(table, "FINALIZED_VERSION", toStr(table.getPublishedSnapshotId()), toStr(snapshot.getId()),
                snapshot.getStructVersion());
    }

    /**
     * 取消定版表。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelFinalizeTable(MetaTableStateReqVO request) {
        MetaTableDO table = lockTable(request.getId());
        validateStateRevision(table, request.getStateRevision());
        if (table.getPublishedSnapshotId() == null) {
            throw exception(META_TABLE_NOT_FINALIZED);
        }
        requireStateUpdate(tableMapper.update(null, stateUpdate(table)
                .set(MetaTableDO::getPublishedVersion, null)
                .set(MetaTableDO::getPublishedSnapshotId, null)
                .set(MetaTableDO::getFinalizedTime, null)
                .set(MetaTableDO::getFinalizedBy, null)
                .set(MetaTableDO::getFinalizedSnapshot, null)));
        auditState(table, "FINALIZED_VERSION", toStr(table.getPublishedSnapshotId()), null, null);
    }

    /**
     * 锁定表。
     */
    private MetaTableDO lockTable(Long id) {
        MetaTableDO observed = validateTableExists(id);
        MetaDbDO db = dbMapper.selectByIdForUpdate(observed.getDbId());
        if (db == null || Objects.equals(db.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_DB_NOT_EXISTS);
        }
        MetaTableDO locked = tableMapper.selectByIdForUpdate(id);
        if (locked == null || Objects.equals(locked.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_TABLE_NOT_EXISTS);
        }
        return locked;
    }

    /**
     * 校验元数据对象的状态修订号，不满足约束时拒绝当前操作。
     */
    private void validateStateRevision(MetaTableDO table, Integer revision) {
        if (revision == null || !Objects.equals(table.getStateRevision(), revision)) {
            throw exception(META_STATE_CONCURRENT_CHANGED);
        }
    }

    /**
     * 构造带预期版本条件的元数据状态更新。
     */
    private LambdaUpdateWrapper<MetaTableDO> stateUpdate(MetaTableDO table) {
        return Wrappers.lambdaUpdate(MetaTableDO.class)
                .set(MetaTableDO::getStateRevision, table.getStateRevision() + 1)
                .eq(MetaTableDO::getId, table.getId())
                .eq(MetaTableDO::getStateRevision, table.getStateRevision());
    }

    /**
     * 校验元数据对象的状态更新，不满足约束时拒绝当前操作。
     */
    private void requireStateUpdate(int count) {
        if (count != 1) {
            throw exception(META_STATE_CONCURRENT_CHANGED);
        }
    }

    /**
     * 记录元数据发布或定版状态变更审计。
     */
    private void auditState(MetaTableDO table, String property, String before, String after, String targetVersion) {
        changeMapper.insert(MetaChangeDO.builder()
                .changeSource(MetaChangeDO.SOURCE_MANUAL).objectType(MetaChangeDO.OBJECT_TYPE_TABLE)
                .objectId(table.getId()).tableId(table.getId()).dbId(table.getDbId())
                .changeType(MetaChangeDO.TYPE_MODIFY).property(property)
                .beforeValue(before).afterValue(after).versionFrom(table.getPublishedVersion()).versionTo(targetVersion)
                .operator(toStr(SecurityFrameworkUtils.getLoginUserId()))
                .remark("元数据状态操作成功；修订号 " + table.getStateRevision() + " → " + (table.getStateRevision() + 1))
                .build());
    }

    /**
     * 校验元数据对象的快照，不满足约束时拒绝当前操作。
     */
    private MetaTableSnapshotDO requireSnapshot(Long tableId, Long snapshotId) {
        MetaTableSnapshotDO snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw exception(META_SNAPSHOT_NOT_EXISTS);
        }
        if (!Objects.equals(snapshot.getTableId(), tableId)) {
            throw exception(META_SNAPSHOT_TABLE_MISMATCH);
        }
        return snapshot;
    }

    /**
     * 生成元数据表的冻结快照，保存定版时的结构与业务属性。
     */
    private MetaFinalizedDetailRespVO freeze(MetaTableDO table, MetaTableSnapshotDO snapshot,
            DisplayReferences display) {
        Map<String, Object> body = MetaSnapshotViews.body(snapshot);
        MetaTableRespVO view = BeanUtils.toBean(table, MetaTableRespVO.class);
        fillDbInfo(List.of(view));
        applyDisplay(List.of(view), display);
        MetaSnapshotViews.applyTableStructure(view, MetaSnapshotViews.table(body));
        view.setStructVersion(snapshot.getStructVersion());
        view.setReadOnly(true);
        view.setBizRevision(null);
        view.setTags(loadTags(MetaChangeDO.OBJECT_TYPE_TABLE, List.of(table.getId())).getOrDefault(table.getId(),
                List.of()));
        List<MetaColumnRespVO> currentColumns = columnViews(table);
        applyColumnAudit(currentColumns, display.users());
        Map<Long, MetaColumnRespVO> current = convertMap(currentColumns, MetaColumnRespVO::getId);
        List<MetaColumnRespVO> columns = new ArrayList<>();
        for (Map<String, Object> node : MetaSnapshotViews.columns(body)) {
            Long id = MetaSnapshotViews.number(node.get("id"));
            MetaColumnRespVO matched = id == null ? null : current.get(id);
            MetaColumnRespVO column = matched == null ? new MetaColumnRespVO()
                    : BeanUtils.toBean(matched, MetaColumnRespVO.class);
            MetaSnapshotViews.applyColumnStructure(column, node);
            column.setTableId(table.getId());
            columns.add(column);
        }
        view.setColumnCount(columns.size());
        MetaFinalizedDetailRespVO result = new MetaFinalizedDetailRespVO();
        result.setTable(view);
        result.setColumns(columns);
        result.setSnapshot(BeanUtils.toBean(snapshot, MetaSnapshotRespVO.class));
        return result;
    }

    /**
     * 读取已定版表分页。
     */
    @Override
    public PageResult<MetaTableRespVO> getFinalizedTablePage(MetaTablePageReqVO request) {
        request.setFinalized(true);
        PageResult<MetaTableDO> page = tableMapper.selectPage(request);
        List<MetaTableRespVO> rows = page.getList().stream().map(table -> readFrozen(table).getTable()).toList();
        return new PageResult<>(rows, page.getTotal());
    }

    /**
     * 读取已定版表详情。
     */
    @Override
    public MetaFinalizedDetailRespVO getFinalizedTableDetail(Long tableId) {
        return readFrozen(validateTableExists(tableId));
    }

    /**
     * 读取已定版元数据的冻结快照，避免混入后续工作副本内容。
     */
    private MetaFinalizedDetailRespVO readFrozen(MetaTableDO table) {
        if (table.getPublishedSnapshotId() == null || StrUtil.isBlank(table.getFinalizedSnapshot())) {
            throw exception(META_TABLE_NOT_FINALIZED);
        }
        MetaFinalizedDetailRespVO frozen;
        try {
            frozen = JsonUtils.parseObject(table.getFinalizedSnapshot(), MetaFinalizedDetailRespVO.class);
        } catch (RuntimeException invalid) {
            throw exception(META_SNAPSHOT_INVALID);
        }
        if (frozen == null || frozen.getTable() == null || frozen.getColumns() == null
                || !Objects.equals(frozen.getTable().getId(), table.getId())) {
            throw exception(META_SNAPSHOT_INVALID);
        }
        // 状态元信息取最新值，以便撤销/CAS；冻结业务和技术内容绝不从当前态补值。
        fillState(frozen.getTable(), table);
        frozen.getTable().setReadOnly(true);
        return frozen;
    }

    /**
     * 比对元数据对象的快照。
     */
    @Override
    public MetaSnapshotCompareRespVO compareSnapshots(Long tableId, Long fromSnapshotId, Long toSnapshotId) {
        validateTableExists(tableId);
        return MetaSnapshotViews.compare(requireSnapshot(tableId, fromSnapshotId),
                requireSnapshot(tableId, toSnapshotId));
    }

    /**
     * 库级批量发布：发布本库全部「正常状态且校验通过且未发布」的表；
     *  已发布表跳过，所有定版指针保持不变
     */
    @Override
    public MetaPublishBatchRespVO publishDb(Long dbId) {
        MetaDbDO db = dbMapper.selectById(dbId);
        if (db == null) {
            throw exception(META_DB_NOT_EXISTS);
        }
        MetaPublishBatchRespVO result = new MetaPublishBatchRespVO();
        List<MetaTableDO> tables = tableMapper.selectListByDbId(dbId);
        int total = 0;
        int published = 0;
        int alreadyPublished = 0;
        int skipped = 0;
        for (MetaTableDO table : tables) {
            if (table.getObjectStatus() == null
                    || table.getObjectStatus() != MetaDbDO.OBJECT_STATUS_ACTIVE) {
                continue;
            }
            total++;
            if (table.getPublishStatus() != null
                    && table.getPublishStatus() == MetaTableDO.PUBLISH_STATUS_YES) {
                // 已发布只计数，不改发布信息，也不触碰独立定版指针。
                alreadyPublished++;
                continue;
            }
            String reason = validatePublishable(table);
            if (reason != null) {
                skipped++;
                if (result.getSkippedReasons().size() < 20) {
                    result.getSkippedReasons().add(table.getTableName() + "：" + reason);
                }
                continue;
            }
            transactionTemplate.executeWithoutResult(status ->
                    changePublishState(table.getId(), table.getStateRevision(), true));
            published++;
        }
        result.setTotal(total);
        result.setPublished(published);
        result.setAlreadyPublished(alreadyPublished);
        result.setSkipped(skipped);
        return result;
    }

    /**
     * 发布校验（P1b 固定规则：注释与业务负责人必填；后续挂治理配置）
     *
     * @return 不通过原因，通过返回 null
     */
    private String validatePublishable(MetaTableDO table) {
        if (StrUtil.isBlank(StrUtil.blankToDefault(table.getCustomComment(), table.getSourceComment()))) {
            return "表注释为空";
        }
        if (table.getBusinessLeaderUserId() == null) {
            return "业务负责人未认责";
        }
        return null;
    }

    // ==================== 删除确认 ====================

    /**
     * 确认删除表（仅待确认删除状态可确认；记录与快照永久保留）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmDeleteTable(Long id) {
        MetaTableDO observed = validateTableExists(id);
        if (dbMapper.selectByIdForUpdate(observed.getDbId()) == null) {
            throw exception(META_DB_NOT_EXISTS);
        }
        MetaTableDO table = tableMapper.selectByIdForUpdate(id);
        if (table == null) {
            throw exception(META_TABLE_NOT_EXISTS);
        }
        if (table.getObjectStatus() == null
                || table.getObjectStatus() != MetaDbDO.OBJECT_STATUS_PENDING_DELETE) {
            throw exception(META_TABLE_NOT_PENDING_DELETE);
        }
        markTableDeleted(table);
    }

    /**
     * 确认删除库（级联其未删除的表与字段；记录与快照永久保留）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmDeleteDb(Long id) {
        MetaDbDO db = dbMapper.selectByIdForUpdate(id);
        if (db == null) {
            throw exception(META_DB_NOT_EXISTS);
        }
        if (db.getObjectStatus() == null
                || db.getObjectStatus() != MetaDbDO.OBJECT_STATUS_PENDING_DELETE) {
            throw exception(META_DB_NOT_PENDING_DELETE);
        }
        MetaDbDO update = new MetaDbDO();
        update.setId(db.getId());
        update.setObjectStatus(MetaDbDO.OBJECT_STATUS_DELETED);
        dbMapper.updateById(update);
        changeMapper.insert(buildStatusChange(MetaChangeDO.OBJECT_TYPE_DB, db.getId(), null, db.getId(),
                db.getObjectStatus()));
        // 级联：库下未删除的表（源端随库一并消失）
        List<Long> tableIds = tableMapper.selectListByDbId(db.getId()).stream()
                .map(MetaTableDO::getId).sorted().toList();
        for (Long tableId : tableIds) {
            MetaTableDO table = tableMapper.selectByIdForUpdate(tableId);
            if (table == null) {
                continue;
            }
            if (table.getObjectStatus() != null
                    && table.getObjectStatus() == MetaDbDO.OBJECT_STATUS_DELETED) {
                continue;
            }
            markTableDeleted(table);
        }
    }

    /**
     * 表置已删除并级联字段（记录与快照永久保留，仅状态流转）
     */
    private void markTableDeleted(MetaTableDO table) {
        validateUnpublished(table);
        if (Objects.equals(table.getCatalogLinked(), 1)) {
            throw exception(META_TABLE_CATALOG_REFERENCED);
        }
        int updated = tableMapper.update(null, Wrappers.lambdaUpdate(MetaTableDO.class)
                .set(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .set(MetaTableDO::getBizRevision, table.getBizRevision() + 1)
                .eq(MetaTableDO::getId, table.getId())
                .eq(MetaTableDO::getObjectStatus, table.getObjectStatus()));
        if (updated != 1) {
            throw exception(META_BIZ_CONCURRENT_CHANGED);
        }
        for (MetaColumnDO column : columnMapper.selectListByTableIdForUpdate(table.getId())) {
            int retired = columnMapper.update(null, Wrappers.lambdaUpdate(MetaColumnDO.class)
                    .set(MetaColumnDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                    .set(MetaColumnDO::getRevision, column.getRevision() + 1)
                    .eq(MetaColumnDO::getId, column.getId()).eq(MetaColumnDO::getRevision, column.getRevision()));
            if (retired != 1) {
                throw exception(META_BIZ_CONCURRENT_CHANGED);
            }
            mappingCommandService.revokeForRetiredColumn(column.getId(),
                    "所属元数据已确认删除，撤销原字段身份的落标执行资格");
        }
        changeMapper.insert(buildStatusChange(MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                table.getId(), table.getDbId(), table.getObjectStatus()));
    }

    /**
     * 构造元数据对象的状态变更。
     */
    private MetaChangeDO buildStatusChange(int objectType, Long objectId, Long tableId, Long dbId,
            Integer beforeStatus) {
        return MetaChangeDO.builder()
                .changeSource(MetaChangeDO.SOURCE_MANUAL)
                .objectType(objectType)
                .objectId(objectId)
                .tableId(tableId)
                .dbId(dbId)
                .changeType(MetaChangeDO.TYPE_MODIFY)
                .property(PROP_OBJECT_STATUS)
                .beforeValue(toStr(beforeStatus))
                .afterValue(String.valueOf(MetaDbDO.OBJECT_STATUS_DELETED))
                .operator(toStr(SecurityFrameworkUtils.getLoginUserId()))
                .build();
    }

    // ==================== 字段/快照/变更查询 ====================

    /**
     * 字段列表（不含已删除字段）
     */
    @Override
    public List<MetaColumnRespVO> getColumnList(Long tableId) {
        List<MetaColumnRespVO> result = columnViews(validateTableExists(tableId));
        applyColumnAudit(result, loadDisplayReferences(List.of(), result).users());
        return result;
    }

    /**
     * 将元数据字段记录转换为详情展示视图。
     */
    private List<MetaColumnRespVO> columnViews(MetaTableDO table) {
        boolean readOnly = Objects.equals(table.getPublishStatus(), MetaTableDO.PUBLISH_STATUS_YES);
        List<MetaColumnDO> columns = columnMapper.selectListByTableId(table.getId());
        columns.removeIf(column -> column.getObjectStatus() != null
                && column.getObjectStatus() == MetaDbDO.OBJECT_STATUS_DELETED);
        List<MetaColumnRespVO> result = BeanUtils.toBean(columns, MetaColumnRespVO.class);
        fillColumnMappings(result);
        Map<Long, List<MetaTagRespVO>> tags = loadTags(MetaChangeDO.OBJECT_TYPE_COLUMN,
                convertList(result, MetaColumnRespVO::getId));
        result.forEach(column -> {
            column.setTags(tags.getOrDefault(column.getId(), List.of()));
            column.setReadOnly(readOnly);
        });
        return result;
    }

    /**
     * 当前字段详情，包含已确认落标的数据元信息。
     */
    @Override
    public MetaColumnRespVO getColumnDetail(Long id) {
        MetaColumnDO column = columnMapper.selectById(id);
        if (column == null || Objects.equals(column.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_COLUMN_NOT_EXISTS);
        }
        validateTableExists(column.getTableId());
        MetaColumnRespVO result = BeanUtils.toBean(column, MetaColumnRespVO.class);
        fillColumnMappings(List.of(result));
        result.setTags(loadTags(MetaChangeDO.OBJECT_TYPE_COLUMN, List.of(id)).getOrDefault(id, List.of()));
        result.setReadOnly(Objects.equals(validateTableExists(column.getTableId()).getPublishStatus(),
                MetaTableDO.PUBLISH_STATUS_YES));
        applyColumnAudit(List.of(result), loadDisplayReferences(List.of(), List.of(result)).users());
        return result;
    }

    /** 按标准映射事实批量读取当前数据元，避免字段列表逐项查询。 */
    private void fillColumnMappings(List<MetaColumnRespVO> columns) {
        if (columns.isEmpty()) {
            return;
        }
        List<StandardMappingDO> mappings = mappingMapper.selectList(new LambdaQueryWrapperX<StandardMappingDO>()
                .eq(StandardMappingDO::getTargetType, StandardMappingDO.TARGET_TYPE_METADATA_COLUMN)
                .in(StandardMappingDO::getColumnId, convertList(columns, MetaColumnRespVO::getId))
                .eq(StandardMappingDO::getMappingStatus, StandardMappingDO.STATUS_CONFIRMED));
        Map<Long, StandardMappingDO> mappingByColumn = convertMap(mappings, StandardMappingDO::getColumnId);
        Map<Integer, StandardElementDO> elements = new HashMap<>();
        if (!mappings.isEmpty()) {
            elements = convertMap(standardElementMapper.selectBatchIds(
                    convertList(mappings, StandardMappingDO::getElementId)), StandardElementDO::getId);
        }
        for (MetaColumnRespVO result : columns) {
            StandardMappingDO mapping = mappingByColumn.get(result.getId());
            result.setStandardElementId(mapping == null ? null : mapping.getElementId());
            if (mapping == null) {
                continue;
            }
            result.setStandardMappingId(mapping.getId());
            StandardElementDO element = elements.get(mapping.getElementId());
            if (element != null) {
                result.setStandardElementCode(element.getCode());
                result.setStandardElementName(element.getName());
            }
        }
    }

    /**
     * 读取快照列表。
     */
    @Override
    public List<MetaSnapshotRespVO> getSnapshotList(Long tableId) {
        validateTableExists(tableId);
        MetaTableDO table = validateTableExists(tableId);
        List<MetaSnapshotRespVO> result = BeanUtils.toBean(snapshotMapper.selectListByTableIdWithoutBody(tableId),
                MetaSnapshotRespVO.class);
        result.forEach(item -> item.setFinalized(Objects.equals(item.getId(), table.getPublishedSnapshotId())));
        return result;
    }

    /**
     * 快照详情（含全结构快照体）
     */
    @Override
    public MetaSnapshotRespVO getSnapshot(Long id) {
        MetaTableSnapshotDO snapshot = snapshotMapper.selectById(id);
        if (snapshot == null) {
            throw exception(META_SNAPSHOT_NOT_EXISTS);
        }
        MetaTableDO table = validateTableExists(snapshot.getTableId());
        MetaSnapshotRespVO result = BeanUtils.toBean(snapshot, MetaSnapshotRespVO.class);
        result.setFinalized(Objects.equals(snapshot.getId(), table.getPublishedSnapshotId()));
        return result;
    }

    /**
     * 变更明细分页
     */
    @Override
    public PageResult<MetaChangeRespVO> getChangePage(MetaChangePageReqVO pageReqVO) {
        return BeanUtils.toBean(changeMapper.selectPage(pageReqVO), MetaChangeRespVO.class);
    }

    /**
     * 元数据检索：关键字覆盖表名/字段名/业务名称/注释，支持分层/主题域/发布/认责过滤
     */
    @Override
    public PageResult<MetaSearchHitVO> search(MetaSearchReqVO reqVO) {
        Set<Long> extraTableIds = new HashSet<>();
        List<MetaColumnDO> columnHits = List.of();
        if (StrUtil.isNotBlank(reqVO.getKeyword())) {
            columnHits = columnMapper.selectListByKeyword(reqVO.getKeyword(), 200);
            extraTableIds.addAll(convertList(columnHits, MetaColumnDO::getTableId));
        }
        PageResult<MetaTableDO> pageResult = tableMapper.selectSearchPage(reqVO, extraTableIds);
        Map<Long, MetaDbDO> dbMap = loadDbMap(pageResult.getList());
        Map<Long, List<MetaColumnDO>> columnsByTable = new HashMap<>();
        for (MetaColumnDO column : columnHits) {
            columnsByTable.computeIfAbsent(column.getTableId(), key -> new ArrayList<>()).add(column);
        }
        List<MetaSearchHitVO> hits = new ArrayList<>();
        for (MetaTableDO table : pageResult.getList()) {
            MetaDbDO db = dbMap.get(table.getDbId());
            boolean tableHit = matchesKeyword(reqVO.getKeyword(),
                    table.getTableName(), table.getBusinessName(),
                    table.getSourceComment(), table.getCustomComment());
            if (tableHit || StrUtil.isBlank(reqVO.getKeyword())) {
                hits.add(toTableHit(table, db, resolveHitField(reqVO.getKeyword(),
                        table.getTableName(), table.getBusinessName(),
                        table.getSourceComment(), table.getCustomComment())));
            }
            List<MetaColumnDO> matchedColumns = columnsByTable.getOrDefault(table.getId(), List.of());
            for (MetaColumnDO column : matchedColumns) {
                MetaSearchHitVO hit = new MetaSearchHitVO();
                hit.setObjectType(MetaChangeDO.OBJECT_TYPE_COLUMN);
                hit.setObjectId(column.getId());
                hit.setTableId(table.getId());
                hit.setDbName(db != null ? db.getDbName() : null);
                hit.setTableName(table.getTableName());
                hit.setColumnName(column.getColumnName());
                hit.setBusinessName(column.getBusinessName());
                hit.setComment(StrUtil.blankToDefault(column.getCustomComment(), column.getSourceComment()));
                hit.setHitField(resolveHitField(reqVO.getKeyword(),
                        column.getColumnName(), column.getBusinessName(),
                        column.getSourceComment(), column.getCustomComment()));
                hit.setStructVersion(table.getStructVersion());
                hit.setPublishStatus(table.getPublishStatus());
                hit.setSourceSystemName(db != null ? db.getSourceSystemName() : null);
                hits.add(hit);
            }
        }
        return new PageResult<>(hits, pageResult.getTotal());
    }

    /**
     * 简版概览：对象计数、覆盖率、近 30 天变更趋势与最近失败采集
     */
    @Override
    public MetaOverviewRespVO getOverview() {
        MetaOverviewRespVO respVO = new MetaOverviewRespVO();
        respVO.setDbCount(dbMapper.selectCount(new LambdaQueryWrapperX<MetaDbDO>()
                .ne(MetaDbDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)));
        respVO.setTableCount(tableMapper.selectCount(new LambdaQueryWrapperX<MetaTableDO>()
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)));
        respVO.setColumnCount(columnMapper.selectCount(new LambdaQueryWrapperX<MetaColumnDO>()
                .ne(MetaColumnDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)));
        respVO.setPublishedTableCount(tableMapper.selectCount(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getPublishStatus, MetaTableDO.PUBLISH_STATUS_YES)
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)));
        respVO.setUnpublishedTableCount(tableMapper.selectCount(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getPublishStatus, MetaTableDO.PUBLISH_STATUS_NO)
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)));
        respVO.setPendingDeleteTableCount(tableMapper.selectCount(new LambdaQueryWrapperX<MetaTableDO>()
                .eq(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_PENDING_DELETE)));
        List<MetaDbDO> dbs = dbMapper.selectList(new LambdaQueryWrapperX<MetaDbDO>()
                .select(MetaDbDO::getDatasourceId, MetaDbDO::getSourceSystemId)
                .ne(MetaDbDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED));
        respVO.setDatasourceCount(dbs.stream()
                .map(MetaDbDO::getDatasourceId)
                .filter(Objects::nonNull)
                .distinct()
                .count());
        respVO.setSourceSystemCount(dbs.stream()
                .map(MetaDbDO::getSourceSystemId)
                .filter(Objects::nonNull)
                .distinct()
                .count());

        Long commented = tableMapper.selectCount(new LambdaQueryWrapperX<MetaTableDO>()
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .and(inner -> inner.and(c -> c.isNotNull(MetaTableDO::getCustomComment)
                                .ne(MetaTableDO::getCustomComment, ""))
                        .or(c -> c.isNotNull(MetaTableDO::getSourceComment)
                                .ne(MetaTableDO::getSourceComment, ""))));
        Long owned = tableMapper.selectCount(new LambdaQueryWrapperX<MetaTableDO>()
                .ne(MetaTableDO::getObjectStatus, MetaDbDO.OBJECT_STATUS_DELETED)
                .isNotNull(MetaTableDO::getBusinessLeaderUserId)
                .ne(MetaTableDO::getBusinessLeaderUserId, 0L));
        respVO.setCommentCoverage(coverage(commented, respVO.getTableCount()));
        respVO.setOwnerCoverage(coverage(owned, respVO.getTableCount()));

        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<MetaCollectInstanceDO> instances = instanceMapper.selectList(
                new LambdaQueryWrapperX<MetaCollectInstanceDO>()
                        .ge(MetaCollectInstanceDO::getCreateTime, since)
                        .orderByDesc(MetaCollectInstanceDO::getId));
        respVO.setCollectInstanceCount((long) instances.size());
        long finished = instances.stream()
                .filter(item -> !Objects.equals(item.getStatus(), MetaCollectInstanceDO.STATUS_RUNNING))
                .count();
        long success = instances.stream()
                .filter(item -> Objects.equals(item.getStatus(), MetaCollectInstanceDO.STATUS_SUCCESS))
                .count();
        respVO.setCollectSuccessRate(finished == 0 ? null : coverage(success, finished));

        fillChangeTrend(respVO, since);
        fillRecentFails(respVO, instances);
        return respVO;
    }

    /**
     * 批量认责：仅覆盖传入的负责人字段，逐表记录手工变更
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchAssignOwner(MetaBatchOwnerReqVO reqVO) {
        if (reqVO.getTechLeaderUserId() == null && reqVO.getBusinessLeaderUserId() == null
                && reqVO.getOwnerDeptId() == null) {
            throw exception(META_TABLE_PUBLISH_VALIDATE_FAIL, "请至少指定一位负责人或责任部门");
        }
        List<Long> ids = reqVO.getIds().stream().distinct().sorted().toList();
        // 跨库批量操作先按编号锁定全部库，再按编号锁表，避免多批次交叉持锁。
        List<MetaTableDO> observed = ids.stream().map(this::validateTableExists).toList();
        for (Long dbId : observed.stream().map(MetaTableDO::getDbId).distinct().sorted().toList()) {
            MetaDbDO db = dbMapper.selectByIdForUpdate(dbId);
            if (db == null || Objects.equals(db.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
                throw exception(META_DB_NOT_EXISTS);
            }
        }
        for (Long id : ids) {
            MetaTableDO table = tableMapper.selectByIdForUpdate(id);
            validateEditable(table == null ? null : table.getObjectStatus());
            validateUnpublished(table);
            List<MetaChangeDO> changes = new ArrayList<>();
            Long techLeader = reqVO.getTechLeaderUserId() != null
                    ? reqVO.getTechLeaderUserId() : table.getTechLeaderUserId();
            String techPhone = reqVO.getTechLeaderUserId() != null
                    ? reqVO.getTechLeaderPhone() : table.getTechLeaderPhone();
            Long bizLeader = reqVO.getBusinessLeaderUserId() != null
                    ? reqVO.getBusinessLeaderUserId() : table.getBusinessLeaderUserId();
            String bizPhone = reqVO.getBusinessLeaderUserId() != null
                    ? reqVO.getBusinessLeaderPhone() : table.getBusinessLeaderPhone();
            Long deptId = reqVO.getOwnerDeptId() != null
                    ? reqVO.getOwnerDeptId() : table.getOwnerDeptId();
            trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                    PROP_TECH_LEADER, toStr(table.getTechLeaderUserId()), toStr(techLeader));
            trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                    PROP_BUSINESS_LEADER, toStr(table.getBusinessLeaderUserId()), toStr(bizLeader));
            trackChange(changes, table, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(),
                    PROP_OWNER_DEPT, toStr(table.getOwnerDeptId()), toStr(deptId));
            tableMapper.update(null, Wrappers.lambdaUpdate(MetaTableDO.class)
                    .set(MetaTableDO::getTechLeaderUserId, techLeader)
                    .set(MetaTableDO::getTechLeaderPhone, techPhone)
                    .set(MetaTableDO::getBusinessLeaderUserId, bizLeader)
                    .set(MetaTableDO::getBusinessLeaderPhone, bizPhone)
                    .set(MetaTableDO::getOwnerDeptId, deptId)
                    .set(MetaTableDO::getBizRevision, table.getBizRevision() + 1)
                    .eq(MetaTableDO::getId, table.getId()));
            if (!changes.isEmpty()) {
                changeMapper.insertBatch(changes);
            }
        }
    }

    /**
     * 补充元数据对象的变更趋势。
     */
    private void fillChangeTrend(MetaOverviewRespVO respVO, LocalDateTime since) {
        List<MetaChangeDO> changes = changeMapper.selectList(new LambdaQueryWrapperX<MetaChangeDO>()
                .ge(MetaChangeDO::getCreateTime, since)
                .select(MetaChangeDO::getCreateTime, MetaChangeDO::getChangeType));
        Map<String, MetaOverviewRespVO.TrendPoint> points = new LinkedHashMap<>();
        for (int i = 29; i >= 0; i--) {
            String day = LocalDateTime.now().minusDays(i).toLocalDate().toString();
            MetaOverviewRespVO.TrendPoint point = new MetaOverviewRespVO.TrendPoint();
            point.setDay(day);
            point.setAddCount(0L);
            point.setModifyCount(0L);
            point.setDeleteCount(0L);
            points.put(day, point);
        }
        for (MetaChangeDO change : changes) {
            if (change.getCreateTime() == null) {
                continue;
            }
            MetaOverviewRespVO.TrendPoint point = points.get(change.getCreateTime().toLocalDate().toString());
            if (point == null) {
                continue;
            }
            if (Objects.equals(change.getChangeType(), MetaChangeDO.TYPE_ADD)) {
                point.setAddCount(point.getAddCount() + 1);
            } else if (Objects.equals(change.getChangeType(), MetaChangeDO.TYPE_DELETE)) {
                point.setDeleteCount(point.getDeleteCount() + 1);
            } else {
                point.setModifyCount(point.getModifyCount() + 1);
            }
        }
        respVO.setChangeTrend(new ArrayList<>(points.values()));
    }

    /**
     * 补充元数据对象的近期失败。
     */
    private void fillRecentFails(MetaOverviewRespVO respVO, List<MetaCollectInstanceDO> instances) {
        List<MetaCollectInstanceDO> fails = instances.stream()
                .filter(item -> Objects.equals(item.getStatus(), MetaCollectInstanceDO.STATUS_FAIL))
                .limit(20)
                .toList();
        Set<Long> taskIds = new HashSet<>(convertList(fails, MetaCollectInstanceDO::getTaskId));
        Map<Long, MetaCollectTaskDO> taskMap = CollUtil.isEmpty(taskIds) ? Map.of()
                : convertMap(collectTaskMapper.selectBatchIds(taskIds), MetaCollectTaskDO::getId);
        List<MetaOverviewRespVO.FailItem> items = new ArrayList<>();
        for (MetaCollectInstanceDO instance : fails) {
            MetaOverviewRespVO.FailItem item = new MetaOverviewRespVO.FailItem();
            item.setInstanceId(instance.getId());
            item.setTaskId(instance.getTaskId());
            MetaCollectTaskDO task = instance.getTaskId() == null ? null : taskMap.get(instance.getTaskId());
            item.setTaskName(
                    task != null ? task.getName() : (instance.getTaskId() == null ? "导入/登记" : "任务#" + instance.getTaskId()));
            item.setErrorType(instance.getErrorType());
            item.setStage(instance.getStage());
            item.setStartTime(instance.getStartTime());
            items.add(item);
        }
        respVO.setRecentFails(items);
    }

    /**
     * 加载数据库映射。
     */
    private Map<Long, MetaDbDO> loadDbMap(List<MetaTableDO> tables) {
        if (CollUtil.isEmpty(tables)) {
            return Map.of();
        }
        Set<Long> dbIds = new HashSet<>(convertList(tables, MetaTableDO::getDbId));
        return convertMap(dbMapper.selectBatchIds(dbIds), MetaDbDO::getId);
    }

    /**
     * 转换表命中。
     */
    private MetaSearchHitVO toTableHit(MetaTableDO table, MetaDbDO db, String hitField) {
        MetaSearchHitVO hit = new MetaSearchHitVO();
        hit.setObjectType(MetaChangeDO.OBJECT_TYPE_TABLE);
        hit.setObjectId(table.getId());
        hit.setTableId(table.getId());
        hit.setDbName(db != null ? db.getDbName() : null);
        hit.setTableName(table.getTableName());
        hit.setBusinessName(table.getBusinessName());
        hit.setComment(StrUtil.blankToDefault(table.getCustomComment(), table.getSourceComment()));
        hit.setHitField(hitField);
        hit.setStructVersion(table.getStructVersion());
        hit.setPublishStatus(table.getPublishStatus());
        hit.setSourceSystemName(db != null ? db.getSourceSystemName() : null);
        return hit;
    }

    /**
     * 比对关键词。
     */
    private boolean matchesKeyword(String keyword, String... values) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        String lower = keyword.toLowerCase();
        for (String value : values) {
            if (StrUtil.isNotBlank(value) && value.toLowerCase().contains(lower)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析元数据对象的命中字段。
     */
    private String resolveHitField(String keyword, String name, String businessName,
            String sourceComment, String customComment) {
        if (StrUtil.isBlank(keyword)) {
            return "表";
        }
        String lower = keyword.toLowerCase();
        if (StrUtil.isNotBlank(name) && name.toLowerCase().contains(lower)) {
            return "名称";
        }
        if (StrUtil.isNotBlank(businessName) && businessName.toLowerCase().contains(lower)) {
            return "业务名称";
        }
        if (StrUtil.isNotBlank(customComment) && customComment.toLowerCase().contains(lower)) {
            return "平台注释";
        }
        if (StrUtil.isNotBlank(sourceComment) && sourceComment.toLowerCase().contains(lower)) {
            return "源注释";
        }
        return "关联字段";
    }

    /**
     * 计算元数据对象业务属性的覆盖率。
     */
    private BigDecimal coverage(long hit, Long total) {
        if (total == null || total == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(hit * 100.0 / total).setScale(1, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 校验元数据对象的表记录存在，不存在时抛出业务异常。
     */
    private MetaTableDO validateTableExists(Long id) {
        MetaTableDO table = tableMapper.selectById(id);
        if (table == null || Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_TABLE_NOT_EXISTS);
        }
        return table;
    }

    /** 只提取名称字段；不回传连接凭据，也不在持锁状态变更事务内调用外部服务。 */
    private void enrichDisplay(List<MetaTableRespVO> tables) {
        if (tables.isEmpty()) {
            return;
        }
        Map<Long, List<MetaTagRespVO>> tags = loadTags(MetaChangeDO.OBJECT_TYPE_TABLE,
                convertList(tables, MetaTableRespVO::getId));
        applyDisplay(tables, loadDisplayReferences(tables, List.of()));
        tables.forEach(table -> table.setTags(tags.getOrDefault(table.getId(), List.of())));
    }

    /** 仅复制安全展示字段；凭据模型不进入响应或冻结快照。 */
    private DisplayReferences loadDisplayReferences(List<MetaTableRespVO> tables, List<MetaColumnRespVO> columns) {
        Set<Long> users = new HashSet<>();
        Set<Long> departments = new HashSet<>();
        tables.forEach(table -> {
            if (table.getTechLeaderUserId() != null) {
                users.add(table.getTechLeaderUserId());
            }
            if (table.getBusinessLeaderUserId() != null) {
                users.add(table.getBusinessLeaderUserId());
            }
            if (table.getOwnerDeptId() != null) {
                departments.add(table.getOwnerDeptId());
            }
            addUserId(users, table.getCreator());
            addUserId(users, table.getUpdater());
        });
        columns.forEach(column -> {
            addUserId(users, column.getCreator());
            addUserId(users, column.getUpdater());
        });
        Map<Long, AdminUserRespDTO> names = Map.of();
        Map<Long, DeptRespDTO> depts = Map.of();
        Map<Long, ConnectionOption> connections = Map.of();
        try {
            names = users.isEmpty() ? Map.of()
                    : convertMap(adminUserApi.getUserList(users).getCheckedData(), AdminUserRespDTO::getId);
            depts = departments.isEmpty() ? Map.of()
                    : convertMap(deptApi.getDeptList(departments).getCheckedData(), DeptRespDTO::getId);
        } catch (RuntimeException unavailable) {
            log.warn("[loadDisplayReferences][元数据人员展示暂不可用]");
        }
        if (!tables.isEmpty()) {
            try {
                connections = convertMap(metadataCollectionApi.options(SecurityFrameworkUtils.getLoginUserId())
                        .getCheckedData().getConnections(), ConnectionOption::getId);
            } catch (RuntimeException unavailable) {
                log.warn("[loadDisplayReferences][元数据安全连接展示暂不可用]");
            }
        }
        return new DisplayReferences(names, depts, connections, tables.isEmpty() ? Map.of() : loadDatasourceNames());
    }

    /**
     * 添加用户编号。
     */
    private static void addUserId(Set<Long> ids, String value) {
        Long id = auditUserId(value);
        if (id != null) {
            ids.add(id);
        }
    }

    /**
     * 解析元数据审计字段中的操作人编号。
     */
    private static Long auditUserId(String value) {
        try {
            return Long.valueOf(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * 解析元数据字段的最终展示值。
     */
    private static <T> T displayValue(Map<Long, T> values, Long id) {
        return id == null ? null : values.get(id);
    }

    /**
     * 应用展示。
     */
    private static void applyDisplay(List<MetaTableRespVO> tables, DisplayReferences refs) {
        tables.forEach(table -> {
            AdminUserRespDTO tech = displayValue(refs.users(), table.getTechLeaderUserId());
            AdminUserRespDTO business = displayValue(refs.users(), table.getBusinessLeaderUserId());
            AdminUserRespDTO creator = displayValue(refs.users(), auditUserId(table.getCreator()));
            AdminUserRespDTO updater = displayValue(refs.users(), auditUserId(table.getUpdater()));
            DeptRespDTO department = displayValue(refs.departments(), table.getOwnerDeptId());
            ConnectionOption connection = displayValue(refs.connections(), table.getDatasourceId());
            table.setDatasourceName(connection == null ? displayValue(refs.datasourceNames(),
                    table.getDatasourceId()) : connection.getName());
            table.setHost(connection == null ? null : connection.getHost());
            table.setPort(connection == null ? null : connection.getPort());
            table.setUsername(connection == null ? null : connection.getUsername());
            table.setTechLeaderName(tech == null ? null : tech.getNickname());
            table.setBusinessLeaderName(business == null ? null : business.getNickname());
            if (tech != null) {
                table.setTechLeaderPhone(tech.getMobile());
            }
            if (business != null) {
                table.setBusinessLeaderPhone(business.getMobile());
            }
            table.setOwnerDeptName(department == null ? null : department.getName());
            table.setCreatorName(creator == null ? null : creator.getNickname());
            table.setCreatorPhone(creator == null ? null : creator.getMobile());
            table.setUpdaterName(updater == null ? null : updater.getNickname());
            table.setUpdaterPhone(updater == null ? null : updater.getMobile());
            var imported = MetaImportedDisplay.read(table.getDisplaySnapshot());
            table.setDatasourceName(imported.fallback(table.getDatasourceName(), "datasourceName"));
            table.setDbType(imported.fallback(table.getDbType(), "dbType"));
            if (tech == null) {
                table.setTechLeaderName(imported.fallback(table.getTechLeaderName(), "techLeaderName"));
                table.setTechLeaderPhone(imported.fallback(table.getTechLeaderPhone(), "techLeaderPhone"));
            }
            if (business == null) {
                table.setBusinessLeaderName(imported.fallback(table.getBusinessLeaderName(), "businessLeaderName"));
                table.setBusinessLeaderPhone(imported.fallback(table.getBusinessLeaderPhone(), "businessLeaderPhone"));
            }
            if (creator == null) {
                table.setCreatorName(imported.fallback(table.getCreatorName(), "creatorName"));
                table.setCreatorPhone(imported.fallback(table.getCreatorPhone(), "creatorPhone"));
            }
            if (updater == null) {
                table.setUpdaterName(imported.fallback(table.getUpdaterName(), "updaterName"));
                table.setUpdaterPhone(imported.fallback(table.getUpdaterPhone(), "updaterPhone"));
            }
        });
    }

    /**
     * 应用字段审计。
     */
    private static void applyColumnAudit(List<MetaColumnRespVO> columns, Map<Long, AdminUserRespDTO> names) {
        columns.forEach(column -> {
            AdminUserRespDTO creator = displayValue(names, auditUserId(column.getCreator()));
            AdminUserRespDTO updater = displayValue(names, auditUserId(column.getUpdater()));
            column.setCreatorName(creator == null ? null : creator.getNickname());
            column.setCreatorPhone(creator == null ? null : creator.getMobile());
            column.setUpdaterName(updater == null ? null : updater.getNickname());
            column.setUpdaterPhone(updater == null ? null : updater.getMobile());
            var imported = MetaImportedDisplay.read(column.getDisplaySnapshot());
            if (creator == null) {
                column.setCreatorName(imported.fallback(column.getCreatorName(), "creatorName"));
                column.setCreatorPhone(imported.fallback(column.getCreatorPhone(), "creatorPhone"));
            }
            if (updater == null) {
                column.setUpdaterName(imported.fallback(column.getUpdaterName(), "updaterName"));
                column.setUpdaterPhone(imported.fallback(column.getUpdaterPhone(), "updaterPhone"));
            }
        });
    }

    private record DisplayReferences(Map<Long, AdminUserRespDTO> users, Map<Long, DeptRespDTO> departments,
                                     Map<Long, ConnectionOption> connections, Map<Long, String> datasourceNames) {

    }

    /**
     * 删除表。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTable(MetaTableStateReqVO request) {
        MetaTableDO table = lockTable(request.getId());
        validateStateRevision(table, request.getStateRevision());
        markTableDeleted(table);
    }

    /**
     * 校验元数据对象的未发布，不满足约束时拒绝当前操作。
     */
    private void validateUnpublished(MetaTableDO table) {
        if (Objects.equals(table.getPublishStatus(), MetaTableDO.PUBLISH_STATUS_YES)) {
            throw exception(META_TABLE_PUBLISHED_READ_ONLY);
        }
    }

    /**
     * 补充元数据对象的状态。
     */
    private void fillState(MetaTableRespVO view, MetaTableDO table) {
        view.setStateRevision(table.getStateRevision());
        view.setPublishStatus(table.getPublishStatus());
        view.setPublishTime(table.getPublishTime());
        view.setFinalized(table.getPublishedSnapshotId() != null);
        view.setFinalizedSnapshotId(table.getPublishedSnapshotId());
        view.setFinalizedVersion(table.getPublishedVersion());
        view.setFinalizedTime(table.getFinalizedTime());
        view.setFinalizedBy(table.getFinalizedBy());
        view.setReadOnly(Objects.equals(table.getPublishStatus(), MetaTableDO.PUBLISH_STATUS_YES));
    }

    /**
     * 加载标签。
     */
    private Map<Long, List<MetaTagRespVO>> loadTags(int objectType, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<MetaTagRelDO> relations = tagRelMapper.selectList(new LambdaQueryWrapperX<MetaTagRelDO>()
                .eq(MetaTagRelDO::getObjectType, objectType).in(MetaTagRelDO::getObjectId, ids));
        if (relations.isEmpty()) {
            return Map.of();
        }
        Map<Long, MetaTagDO> tags = convertMap(tagMapper.selectBatchIds(convertList(relations, MetaTagRelDO::getTagId)),
                MetaTagDO::getId);
        Map<Long, List<MetaTagRespVO>> result = new HashMap<>();
        for (MetaTagRelDO relation : relations) {
            MetaTagDO tag = tags.get(relation.getTagId());
            if (tag != null) {
                result.computeIfAbsent(relation.getObjectId(), key -> new ArrayList<>())
                        .add(BeanUtils.toBean(tag, MetaTagRespVO.class));
            }
        }
        return result;
    }

    /**
     * 校验元数据对象的可编辑，不满足约束时拒绝当前操作。
     */
    private void validateEditable(Integer objectStatus) {
        if (objectStatus == null || Objects.equals(objectStatus, MetaDbDO.OBJECT_STATUS_DELETED)) {
            throw exception(META_OBJECT_NOT_EDITABLE);
        }
    }

    /**
     * 转换字符串。
     */
    private String toStr(Object value) {
        return ObjectUtil.isNull(value) ? null : String.valueOf(value);
    }

}

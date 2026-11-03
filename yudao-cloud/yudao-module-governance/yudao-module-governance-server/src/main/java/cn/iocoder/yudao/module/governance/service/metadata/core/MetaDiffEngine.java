package cn.iocoder.yudao.module.governance.service.metadata.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.*;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel.CollectedColumn;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel.CollectedTable;
import cn.iocoder.yudao.module.governance.service.standardmapping.StandardMappingCommandService;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_OBJECT_NOT_EDITABLE;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 元数据差异引擎（DIFF + WRITE 阶段）
 *
 * 统一「全量扫描 + 差异写入」：按自然键三级比对，新增/变更/待删除分别落实体、快照与变更明细；
 * 语义版本按变更性质推导（MAJOR/MINOR/PATCH）；每张表独立事务，单表失败不影响整库；
 * 平台维护属性（custom_comment/business_name/business_definition/认责/标签/标准映射/安全分级等）永不覆盖；
 * 行数/存储量等运行指标只更新不升版本；源端消失的表/库进入待确认删除，字段直接置已删除并留痕；
 * 待确认删除允许恢复；已确认删除后的同名重建生成新身份，旧对象与历史快照保留为墓碑
 *
 * @author Li
 */
@Slf4j
@Component
public class MetaDiffEngine {

    /** 变更属性名 */
    public static final String PROP_COMMENT = "COMMENT";

    public static final String PROP_DATA_TYPE = "DATA_TYPE";

    public static final String PROP_LENGTH = "LENGTH";

    public static final String PROP_PRECISION = "PRECISION";

    public static final String PROP_SCALE = "SCALE";

    public static final String PROP_NULLABLE = "NULLABLE";

    public static final String PROP_DEFAULT = "DEFAULT";

    public static final String PROP_PK = "PK";

    public static final String PROP_AUTO_INCREMENT = "AUTO_INCREMENT";

    public static final String PROP_ORDINAL = "ORDINAL";

    public static final String PROP_TABLE_TYPE = "TABLE_TYPE";

    public static final String PROP_PRIMARY_KEY = "PRIMARY_KEY";

    public static final String PROP_OBJECT_STATUS = "OBJECT_STATUS";

    /**
     * URN 环境标识（六模块设计：物理对象 URN 携带环境）
     */
    @Value("${datahub.env:prod}")
    private String env;

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
    private TransactionTemplate transactionTemplate;

    @Resource
    private StandardMappingCommandService mappingCommandService;

    // ==================== 库级 ====================

    /**
     * 按 DB Switch 枚举并用于读表的精确命名空间 upsert 库对象（独立事务）。
     * schema 型产品传 schema，catalog 型产品传 catalog；本层不猜默认 namespace。
     */
    public MetaDbDO upsertDb(MetaCollectContext ctx, String namespaceName) {
        if (StrUtil.isBlank(namespaceName)) {
            throw new IllegalArgumentException("采集命名空间不能为空");
        }
        String dbName = namespaceName;
        return transactionTemplate.execute(status -> {
            ctx.getWriteFence().run();
            MetaDbDO db = dbMapper.selectByNaturalKeyForUpdate(ctx.getDatasourceId(), dbName, namespaceName);
            LocalDateTime now = LocalDateTime.now();
            if (db == null) {
                db = MetaDbDO.builder()
                        .resourceUrn(MetaUrnUtils.pending())
                        .origin(ctx.resolveOrigin())
                        .datasourceId(ctx.getDatasourceId())
                        .sourceSystemId(ctx.getSourceSystemId())
                        .sourceSystemName(ctx.getSourceSystemName())
                        .dbType(ctx.getDbType())
                        .dbName(dbName)
                        .schemaName(namespaceName)
                        .layer(inferLayer(dbName))
                        .tableCount(0)
                        .objectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE)
                        .firstInstanceId(ctx.getInstanceId())
                        .lastInstanceId(ctx.getInstanceId())
                        .firstSeenTime(now)
                        .lastSeenTime(now)
                        .build();
                dbMapper.insert(db);
                db.setResourceUrn(MetaUrnUtils.buildDbUrn(env, ctx.getDatasourceId(), db.getId()));
                dbMapper.updateById(db);
                insertChange(ctx, MetaChangeDO.OBJECT_TYPE_DB, db.getId(), null, db.getId(),
                        MetaChangeDO.TYPE_ADD, null, null, dbName, null, null);
                ctx.getLogger().info(MetaCollectInstanceDO.STAGE_DIFF, "[DB] " + dbName, "新库发现，创建库元数据");
                return db;
            }
            // 待确认删除属于观测误报，可恢复；已确认删除的墓碑不会进入自然键查询。
            if (db.getObjectStatus() != MetaDbDO.OBJECT_STATUS_ACTIVE) {
                insertChange(ctx, MetaChangeDO.OBJECT_TYPE_DB, db.getId(), null, db.getId(),
                        MetaChangeDO.TYPE_MODIFY, PROP_OBJECT_STATUS,
                        String.valueOf(db.getObjectStatus()),
                        String.valueOf(MetaDbDO.OBJECT_STATUS_ACTIVE), null, null);
                db.setObjectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE);
                ctx.getLogger().info(MetaCollectInstanceDO.STAGE_DIFF, "[DB] " + dbName, "库重新发现，恢复为正常状态");
            }
            db.setLastInstanceId(ctx.getInstanceId());
            db.setLastSeenTime(now);
            db.setDbType(ctx.getDbType());
            if (ctx.getSourceSystemId() != null) {
                db.setSourceSystemId(ctx.getSourceSystemId());
                db.setSourceSystemName(ctx.getSourceSystemName());
            }
            dbMapper.updateById(db);
            return db;
        });
    }

    /**
     * 刷新库的表数量统计（运行指标，不升版本）
     */
    public void refreshDbStat(MetaDbDO db) {
        Long count = tableMapper.selectCountByDbId(db.getId(), MetaDbDO.OBJECT_STATUS_ACTIVE);
        MetaDbDO update = new MetaDbDO();
        update.setId(db.getId());
        update.setTableCount(count == null ? 0 : count.intValue());
        dbMapper.updateById(update);
    }

    /**
     * 整库范围下，将源端已消失的库置为待确认删除（不物理删，目录/资产历史不动）
     */
    public void markMissingDbs(MetaCollectContext ctx, Set<String> seenDbNames) {
        List<MetaDbDO> dbs = dbMapper.selectListByDatasourceId(ctx.getDatasourceId());
        for (MetaDbDO db : dbs) {
            if (db.getObjectStatus() != MetaDbDO.OBJECT_STATUS_ACTIVE
                    || seenDbNames.contains(db.getDbName())
                    || db.getOrigin() != MetaDbDO.ORIGIN_COLLECT) {
                continue;
            }
            Boolean marked = transactionTemplate.execute(status -> {
                ctx.getWriteFence().run();
                MetaDbDO current = dbMapper.selectByIdForUpdate(db.getId());
                if (current == null || !Objects.equals(current.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)) {
                    return false;
                }
                MetaDbDO update = new MetaDbDO();
                update.setId(db.getId());
                update.setObjectStatus(MetaDbDO.OBJECT_STATUS_PENDING_DELETE);
                dbMapper.updateById(update);
                insertChange(ctx, MetaChangeDO.OBJECT_TYPE_DB, db.getId(), null, db.getId(),
                        MetaChangeDO.TYPE_DELETE, null, db.getDbName(), null, null, null);
                return true;
            });
            if (Boolean.TRUE.equals(marked)) {
                ctx.getLogger().warn(MetaCollectInstanceDO.STAGE_DIFF, "[DB] " + db.getDbName(),
                        "源端未发现该库，进入待确认删除");
            }
        }
    }

    // ==================== 表级 ====================

    /**
     * 处理单张采集到的表（独立事务）：新增建基线，存量按属性 diff 推导语义版本并落快照与变更
     */
    public void processTable(MetaCollectContext ctx, MetaDbDO db, CollectedTable collected) {
        transactionTemplate.executeWithoutResult(status -> {
            ctx.getWriteFence().run();
            MetaDbDO currentDb = dbMapper.selectByIdForUpdate(db.getId());
            if (currentDb == null || !Objects.equals(currentDb.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)) {
                throw exception(META_OBJECT_NOT_EDITABLE);
            }
            MetaTableDO existing = tableMapper.selectByDbIdAndName(currentDb.getId(), collected.getTableName());
            if (existing == null) {
                createTable(ctx, currentDb, collected);
            } else {
                updateExistingTable(ctx, currentDb, tableMapper.selectByIdForUpdate(existing.getId()), collected);
            }
        });
        ctx.getTableProcessed().incrementAndGet();
        ctx.getColumnProcessed().addAndGet(CollUtil.size(collected.getColumns()));
    }

    /**
     * 整库范围下，将源端已消失的表置为待确认删除
     */
    public void markMissingTables(MetaCollectContext ctx, MetaDbDO db, Set<String> collectedTableNames) {
        List<MetaTableDO> tables = tableMapper.selectListByDbId(db.getId());
        for (MetaTableDO table : tables) {
            if (table.getObjectStatus() == null
                    || table.getObjectStatus() != MetaDbDO.OBJECT_STATUS_ACTIVE
                    || collectedTableNames.contains(table.getTableName())) {
                continue;
            }
            Boolean marked = transactionTemplate.execute(status -> {
                ctx.getWriteFence().run();
                MetaDbDO currentDb = dbMapper.selectByIdForUpdate(db.getId());
                if (currentDb == null || !Objects.equals(currentDb.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)) {
                    return false;
                }
                MetaTableDO current = tableMapper.selectByIdForUpdate(table.getId());
                if (current == null || !Objects.equals(current.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)) {
                    return false;
                }
                MetaTableDO update = new MetaTableDO();
                update.setId(table.getId());
                update.setObjectStatus(MetaDbDO.OBJECT_STATUS_PENDING_DELETE);
                tableMapper.updateById(update);
                insertChange(ctx, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(), table.getId(), db.getId(),
                        MetaChangeDO.TYPE_DELETE, null, table.getTableName(), null,
                        table.getStructVersion(), table.getStructVersion());
                return true;
            });
            if (Boolean.TRUE.equals(marked)) {
                ctx.getTableDelete().incrementAndGet();
                ctx.getLogger().warn(MetaCollectInstanceDO.STAGE_DIFF,
                        "[TABLE] " + db.getDbName() + "." + table.getTableName(),
                        "源端未发现该表，进入待确认删除（快照与历史保留）");
            }
        }
    }

    /**
     * 创建表。
     */
    private void createTable(MetaCollectContext ctx, MetaDbDO db, CollectedTable collected) {
        LocalDateTime now = LocalDateTime.now();
        MetaTableDO table = MetaTableDO.builder()
                .resourceUrn(MetaUrnUtils.pending())
                .origin(ctx.resolveOrigin())
                .dbId(db.getId())
                .datasourceId(ctx.getDatasourceId())
                .tableName(collected.getTableName())
                .tableType(StrUtil.blankToDefault(collected.getTableType(), MetaTableDO.TYPE_TABLE))
                .sourceComment(collected.getRemarks())
                .layer(db.getLayer())
                .columnCount(CollUtil.size(collected.getColumns()))
                .primaryKeys(JsonUtils.toJsonString(safeList(collected.getPrimaryKeys())))
                .structVersion(MetaVersionUtils.FIRST_VERSION)
                .structChangeCount(0)
                .masterFlag(0)
                .sensitiveFlag(0)
                .catalogLinked(0)
                .objectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE)
                .publishStatus(MetaTableDO.PUBLISH_STATUS_NO)
                .firstInstanceId(ctx.getInstanceId())
                .lastInstanceId(ctx.getInstanceId())
                .firstSeenTime(now)
                .lastSeenTime(now)
                .build();
        tableMapper.insert(table);
        table.setResourceUrn(MetaUrnUtils.buildTableUrn(env, ctx.getDatasourceId(), table.getId()));
        tableMapper.updateById(table);

        List<MetaColumnDO> columns = new ArrayList<>();
        for (CollectedColumn cc : safeList(collected.getColumns())) {
            columns.add(buildColumn(ctx, db, table, cc));
        }
        if (!columns.isEmpty()) {
            columnMapper.insertBatch(columns);
            columnMapper.updatePendingUrnByTableId(env, table.getId());
        }

        insertChange(ctx, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(), table.getId(), db.getId(),
                MetaChangeDO.TYPE_ADD, null, null, collected.getTableName(),
                null, MetaVersionUtils.FIRST_VERSION);
        // URN 批量回填在数据库执行，快照必须回读正式身份，不能冻结 pending 占位值。
        List<MetaColumnDO> persistedColumns = columnMapper.selectListByTableId(table.getId());
        insertSnapshot(ctx, db, table, persistedColumns, MetaVersionUtils.LEVEL_NONE,
                columns.size(), 0, 0);

        ctx.getTableAdd().incrementAndGet();
        ctx.getColumnAdd().addAndGet(columns.size());
        ctx.getLogger().info(MetaCollectInstanceDO.STAGE_WRITE,
                "[TABLE] " + db.getDbName() + "." + collected.getTableName(),
                String.format("新表发现，创建表元数据并写基线快照 %s，字段数=%s",
                        MetaVersionUtils.FIRST_VERSION, columns.size()));
    }

    /**
     * 更新现有表。
     */
    private void updateExistingTable(MetaCollectContext ctx, MetaDbDO db, MetaTableDO table,
            CollectedTable collected) {
        List<MetaChangeDO> changes = new ArrayList<>();
        int level = MetaVersionUtils.LEVEL_NONE;
        String versionFrom = table.getStructVersion();
        LocalDateTime now = LocalDateTime.now();

        // 仅恢复待确认删除的原身份；已确认删除的同名表由自然键查询隔离并创建新身份。
        boolean revived = table.getObjectStatus() == null
                || table.getObjectStatus() != MetaDbDO.OBJECT_STATUS_ACTIVE;
        if (revived) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_OBJECT_STATUS,
                    String.valueOf(table.getObjectStatus()), String.valueOf(MetaDbDO.OBJECT_STATUS_ACTIVE)));
            table.setObjectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE);
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_PATCH);
        }

        // 表注释（PATCH）
        if (!Objects.equals(StrUtil.nullToEmpty(table.getSourceComment()),
                StrUtil.nullToEmpty(collected.getRemarks()))) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_COMMENT,
                    table.getSourceComment(), collected.getRemarks()));
            table.setSourceComment(collected.getRemarks());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_PATCH);
        }

        // 表类型（MAJOR：TABLE↔VIEW 视为重建）
        String collectedType = StrUtil.blankToDefault(collected.getTableType(), MetaTableDO.TYPE_TABLE);
        if (!Objects.equals(table.getTableType(), collectedType)) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_TABLE_TYPE,
                    table.getTableType(), collectedType));
            table.setTableType(collectedType);
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MAJOR);
        }

        // 主键构成（MAJOR）
        List<String> oldPks = safeList(JsonUtils.parseArray(table.getPrimaryKeys(), String.class));
        List<String> newPks = safeList(collected.getPrimaryKeys());
        if (!new HashSet<>(oldPks).equals(new HashSet<>(newPks))) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_TABLE, table.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_PRIMARY_KEY,
                    String.join(",", oldPks), String.join(",", newPks)));
            table.setPrimaryKeys(JsonUtils.toJsonString(newPks));
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MAJOR);
        }

        // 字段 diff（按 table_id + column_name upsert 保 ID 稳定）
        int colAdd = 0, colUpdate = 0, colDelete = 0;
        List<MetaColumnDO> oldColumns = columnMapper.selectListByTableIdForUpdate(table.getId());
        Map<String, MetaColumnDO> oldByName = new LinkedHashMap<>();
        for (MetaColumnDO old : oldColumns) {
            oldByName.put(old.getColumnName(), old);
        }
        List<MetaColumnDO> toInsert = new ArrayList<>();
        for (CollectedColumn cc : safeList(collected.getColumns())) {
            MetaColumnDO old = oldByName.remove(cc.getColumnName());
            if (old == null) {
                // 新增字段（MINOR）
                toInsert.add(buildColumn(ctx, db, table, cc));
                colAdd++;
                level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MINOR);
                continue;
            }
            // 属性级比对
            int colLevel = diffColumn(ctx, db, table, old, cc, changes);
            if (Objects.equals(old.getObjectStatus(), MetaDbDO.OBJECT_STATUS_PENDING_DELETE)) {
                changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                        db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_OBJECT_STATUS,
                        String.valueOf(MetaDbDO.OBJECT_STATUS_PENDING_DELETE),
                        String.valueOf(MetaDbDO.OBJECT_STATUS_ACTIVE)));
                old.setObjectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE);
                colLevel = MetaVersionUtils.maxLevel(colLevel, MetaVersionUtils.LEVEL_PATCH);
            }
            if (colLevel > MetaVersionUtils.LEVEL_NONE) {
                old.setRevision(old.getRevision() == null ? 1 : old.getRevision() + 1);
                colUpdate++;
                level = MetaVersionUtils.maxLevel(level, colLevel);
            }
            old.setLastInstanceId(ctx.getInstanceId());
            old.setLastSeenTime(now);
            columnMapper.updateById(old);
        }
        // 源端已消失的字段：置已删除并留痕（MAJOR）
        for (MetaColumnDO missing : oldByName.values()) {
            if (missing.getObjectStatus() != null
                    && missing.getObjectStatus() == MetaDbDO.OBJECT_STATUS_DELETED) {
                continue;
            }
            MetaColumnDO update = new MetaColumnDO();
            update.setId(missing.getId());
            update.setObjectStatus(MetaDbDO.OBJECT_STATUS_DELETED);
            update.setRevision(missing.getRevision() + 1);
            columnMapper.updateById(update);
            mappingCommandService.revokeForRetiredColumn(missing.getId(), "源端字段已消失，撤销原身份的落标执行资格");
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, missing.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_DELETE, null, missing.getColumnName(), null));
            colDelete++;
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MAJOR);
        }
        if (!toInsert.isEmpty()) {
            columnMapper.insertBatch(toInsert);
            columnMapper.updatePendingUrnByTableId(env, table.getId());
            for (MetaColumnDO inserted : toInsert) {
                changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, inserted.getId(), table.getId(),
                        db.getId(), MetaChangeDO.TYPE_ADD, null, null, inserted.getColumnName()));
            }
        }

        // 无任何结构变更：仅刷新运行元数据
        if (changes.isEmpty()) {
            MetaTableDO touch = new MetaTableDO();
            touch.setId(table.getId());
            touch.setLastInstanceId(ctx.getInstanceId());
            touch.setLastSeenTime(now);
            tableMapper.updateById(touch);
            return;
        }

        // 语义版本推进 + 快照 + 变更明细
        String versionTo = MetaVersionUtils.bump(versionFrom, level);
        table.setStructVersion(versionTo);
        table.setColumnCount(countActiveColumns(table.getId()));
        table.setStructChangeCount(table.getStructChangeCount() == null
                ? 1 : table.getStructChangeCount() + 1);
        table.setLastStructChangeTime(now);
        table.setLastInstanceId(ctx.getInstanceId());
        table.setLastSeenTime(now);
        tableMapper.updateById(table);

        for (MetaChangeDO change : changes) {
            change.setVersionFrom(versionFrom);
            change.setVersionTo(versionTo);
        }
        changeMapper.insertBatch(changes);

        List<MetaColumnDO> currentColumns = columnMapper.selectListByTableId(table.getId());
        currentColumns.removeIf(c -> c.getObjectStatus() != null
                && c.getObjectStatus() == MetaDbDO.OBJECT_STATUS_DELETED);
        insertSnapshot(ctx, db, table, currentColumns, level, colAdd, colUpdate, colDelete);

        ctx.getTableUpdate().incrementAndGet();
        ctx.getColumnAdd().addAndGet(colAdd);
        ctx.getColumnUpdate().addAndGet(colUpdate);
        ctx.getColumnDelete().addAndGet(colDelete);
        ctx.getLogger().info(MetaCollectInstanceDO.STAGE_WRITE,
                "[TABLE] " + db.getDbName() + "." + table.getTableName(),
                String.format("结构变更 %s → %s（级别=%s），字段新增=%s 变更=%s 删除=%s",
                        versionFrom, versionTo, levelName(level), colAdd, colUpdate, colDelete));
    }

    /**
     * 字段属性级比对，返回该字段引起的最高变更级别
     */
    private int diffColumn(MetaCollectContext ctx, MetaDbDO db, MetaTableDO table,
            MetaColumnDO old, CollectedColumn cc, List<MetaChangeDO> changes) {
        int level = MetaVersionUtils.LEVEL_NONE;
        // 注释（PATCH，仅覆盖 source_comment，平台注释不动）
        if (!Objects.equals(StrUtil.nullToEmpty(old.getSourceComment()), StrUtil.nullToEmpty(cc.getRemarks()))) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_COMMENT, old.getSourceComment(), cc.getRemarks()));
            old.setSourceComment(cc.getRemarks());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_PATCH);
        }
        // 数据类型（MAJOR）
        if (!StrUtil.equalsIgnoreCase(old.getDataType(), cc.getDataType())) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_DATA_TYPE, old.getDataType(), cc.getDataType()));
            old.setDataType(cc.getDataType());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MAJOR);
        }
        // 长度（收窄 MAJOR / 扩大 MINOR）
        if (!Objects.equals(old.getDataLength(), cc.getDataLength())) {
            boolean narrowed = old.getDataLength() != null && cc.getDataLength() != null
                    && cc.getDataLength() < old.getDataLength();
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_LENGTH,
                    String.valueOf(old.getDataLength()), String.valueOf(cc.getDataLength())));
            old.setDataLength(cc.getDataLength());
            level = MetaVersionUtils.maxLevel(level,
                    narrowed ? MetaVersionUtils.LEVEL_MAJOR : MetaVersionUtils.LEVEL_MINOR);
        }
        // 精度（收窄 MAJOR / 扩大 MINOR）
        if (!Objects.equals(old.getDataPrecision(), cc.getDataPrecision())) {
            boolean narrowed = old.getDataPrecision() != null && cc.getDataPrecision() != null
                    && cc.getDataPrecision() < old.getDataPrecision();
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_PRECISION,
                    String.valueOf(old.getDataPrecision()), String.valueOf(cc.getDataPrecision())));
            old.setDataPrecision(cc.getDataPrecision());
            level = MetaVersionUtils.maxLevel(level,
                    narrowed ? MetaVersionUtils.LEVEL_MAJOR : MetaVersionUtils.LEVEL_MINOR);
        }
        // 小数位（收窄 MAJOR / 扩大 MINOR）
        if (!Objects.equals(old.getDataScale(), cc.getDataScale())) {
            boolean narrowed = old.getDataScale() != null && cc.getDataScale() != null
                    && cc.getDataScale() < old.getDataScale();
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_SCALE,
                    String.valueOf(old.getDataScale()), String.valueOf(cc.getDataScale())));
            old.setDataScale(cc.getDataScale());
            level = MetaVersionUtils.maxLevel(level,
                    narrowed ? MetaVersionUtils.LEVEL_MAJOR : MetaVersionUtils.LEVEL_MINOR);
        }
        // 可空性（MINOR）
        if (!Objects.equals(old.getNullableFlag(), cc.getNullableFlag())) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_NULLABLE,
                    String.valueOf(old.getNullableFlag()), String.valueOf(cc.getNullableFlag())));
            old.setNullableFlag(cc.getNullableFlag());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MINOR);
        }
        // 默认值（PATCH）
        if (!Objects.equals(StrUtil.nullToEmpty(old.getDefaultValue()), StrUtil.nullToEmpty(cc.getDefaultValue()))) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_DEFAULT,
                    old.getDefaultValue(), cc.getDefaultValue()));
            old.setDefaultValue(cc.getDefaultValue());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_PATCH);
        }
        // 主键标志（MAJOR，主键构成变化同时会在表级 PRIMARY_KEY 记录）
        if (!Objects.equals(old.getPkFlag(), cc.getPkFlag())) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_PK,
                    String.valueOf(old.getPkFlag()), String.valueOf(cc.getPkFlag())));
            old.setPkFlag(cc.getPkFlag());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MAJOR);
        }
        // 自增（MINOR）
        if (!Objects.equals(old.getAutoIncrementFlag(), cc.getAutoIncrementFlag())) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_AUTO_INCREMENT,
                    String.valueOf(old.getAutoIncrementFlag()), String.valueOf(cc.getAutoIncrementFlag())));
            old.setAutoIncrementFlag(cc.getAutoIncrementFlag());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_MINOR);
        }
        // 字段顺序（PATCH）
        if (!Objects.equals(old.getOrdinalPosition(), cc.getOrdinalPosition())) {
            changes.add(newChange(ctx, MetaChangeDO.OBJECT_TYPE_COLUMN, old.getId(), table.getId(),
                    db.getId(), MetaChangeDO.TYPE_MODIFY, PROP_ORDINAL,
                    String.valueOf(old.getOrdinalPosition()), String.valueOf(cc.getOrdinalPosition())));
            old.setOrdinalPosition(cc.getOrdinalPosition());
            level = MetaVersionUtils.maxLevel(level, MetaVersionUtils.LEVEL_PATCH);
        }
        return level;
    }

    // ==================== 构建与写入 ====================

    /**
     * 构造元数据差异的字段。
     */
    private MetaColumnDO buildColumn(MetaCollectContext ctx, MetaDbDO db, MetaTableDO table,
            CollectedColumn cc) {
        LocalDateTime now = LocalDateTime.now();
        MetaColumnDO column = MetaColumnDO.builder()
                .resourceUrn(MetaUrnUtils.pending())
                .origin(ctx.resolveOrigin())
                .tableId(table.getId())
                .dbId(db.getId())
                .datasourceId(ctx.getDatasourceId())
                .columnName(cc.getColumnName())
                .revision(1)
                .sensitiveFlag(0)
                .fkFlag(0)
                .uniqueFlag(0)
                .objectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE)
                .firstInstanceId(ctx.getInstanceId())
                .lastInstanceId(ctx.getInstanceId())
                .firstSeenTime(now)
                .lastSeenTime(now)
                .build();
        applyCollectedColumn(column, cc);
        return column;
    }

    /**
     * 采集覆盖技术属性（平台维护属性不在此列，永不覆盖）
     */
    private void applyCollectedColumn(MetaColumnDO column, CollectedColumn cc) {
        column.setOrdinalPosition(cc.getOrdinalPosition());
        column.setSourceComment(cc.getRemarks());
        column.setDataType(cc.getDataType());
        column.setDataLength(cc.getDataLength());
        column.setDataPrecision(cc.getDataPrecision());
        column.setDataScale(cc.getDataScale());
        column.setNullableFlag(cc.getNullableFlag());
        column.setDefaultValue(cc.getDefaultValue());
        column.setPkFlag(cc.getPkFlag());
        column.setAutoIncrementFlag(cc.getAutoIncrementFlag());
    }

    /**
     * 结构基线快照兜底：当前语义版本已有快照则直接返回，否则以当前结构补一份基线快照
     *
     * 正常采集/导入链路都会产生快照；旁路调用须在事务内先锁库、表和字段，保证基线一致
     */
    public MetaTableSnapshotDO ensureCurrentSnapshot(MetaTableDO table) {
        MetaTableSnapshotDO existing =
                snapshotMapper.selectByTableIdAndVersion(table.getId(), table.getStructVersion());
        if (existing != null) {
            return existing;
        }
        List<MetaColumnDO> columns = columnMapper.selectListByTableId(table.getId());
        columns.removeIf(column -> column.getObjectStatus() != null
                && column.getObjectStatus() == MetaDbDO.OBJECT_STATUS_DELETED);
        Map<String, Object> snapshotJson = buildSnapshotJson(table, columns);
        String json = JsonUtils.toJsonString(snapshotJson);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("colAdd", 0);
        summary.put("colUpdate", 0);
        summary.put("colDelete", 0);
        summary.put("breaking", false);
        summary.put("notes", "补充结构基线快照");
        MetaTableSnapshotDO snapshot = MetaTableSnapshotDO.builder()
                .tableId(table.getId())
                .dbId(table.getDbId())
                .structVersion(table.getStructVersion())
                .changeSource(MetaChangeDO.SOURCE_MANUAL)
                .snapshot(json)
                .snapshotHash(DigestUtil.sha256Hex(json))
                .changeSummary(JsonUtils.toJsonString(summary))
                .baselineFlag(0)
                .build();
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    /**
     * 新增元数据差异的快照。
     */
    private void insertSnapshot(MetaCollectContext ctx, MetaDbDO db, MetaTableDO table,
            List<MetaColumnDO> columns, int level,
            int colAdd, int colUpdate, int colDelete) {
        // 幂等：同表同版本快照已存在则跳过（实例重跑场景）
        if (snapshotMapper.selectByTableIdAndVersion(table.getId(), table.getStructVersion()) != null) {
            return;
        }
        Map<String, Object> snapshot = buildSnapshotJson(table, columns);
        String json = JsonUtils.toJsonString(snapshot);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("colAdd", colAdd);
        summary.put("colUpdate", colUpdate);
        summary.put("colDelete", colDelete);
        summary.put("breaking", level == MetaVersionUtils.LEVEL_MAJOR);
        snapshotMapper.insert(MetaTableSnapshotDO.builder()
                .tableId(table.getId())
                .dbId(db.getId())
                .structVersion(table.getStructVersion())
                .instanceId(ctx.getInstanceId())
                .changeSource(ctx.resolveChangeSource())
                .snapshot(json)
                .snapshotHash(DigestUtil.sha256Hex(json))
                .changeSummary(JsonUtils.toJsonString(summary))
                .baselineFlag(0)
                .build());
    }

    /**
     * 构造元数据差异的快照JSON。
     */
    private Map<String, Object> buildSnapshotJson(MetaTableDO table, List<MetaColumnDO> columns) {
        Map<String, Object> tableNode = new LinkedHashMap<>();
        tableNode.put("tableName", table.getTableName());
        tableNode.put("tableType", table.getTableType());
        tableNode.put("comment", table.getSourceComment());
        tableNode.put("primaryKeys", JsonUtils.parseArray(table.getPrimaryKeys(), String.class));
        tableNode.put("columnCount", table.getColumnCount());
        tableNode.put("engine", table.getEngine());
        tableNode.put("indexes", JsonUtils.parseObject(table.getIndexes(), Object.class));
        tableNode.put("partitionInfo", JsonUtils.parseObject(table.getPartitionInfo(), Object.class));
        List<Map<String, Object>> columnNodes = new ArrayList<>();
        for (MetaColumnDO column : columns) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", column.getId());
            node.put("resourceUrn", column.getResourceUrn());
            node.put("columnName", column.getColumnName());
            node.put("ordinal", column.getOrdinalPosition());
            node.put("comment", column.getSourceComment());
            node.put("dataType", column.getDataType());
            node.put("length", column.getDataLength());
            node.put("precision", column.getDataPrecision());
            node.put("scale", column.getDataScale());
            node.put("nullable", column.getNullableFlag());
            node.put("defaultValue", column.getDefaultValue());
            node.put("pk", column.getPkFlag());
            node.put("autoIncrement", column.getAutoIncrementFlag());
            columnNodes.add(node);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("table", tableNode);
        snapshot.put("columns", columnNodes);
        return snapshot;
    }

    /**
     * 新增变更。
     */
    private void insertChange(MetaCollectContext ctx, int objectType, Long objectId, Long tableId,
            Long dbId, int changeType, String property, String beforeValue,
            String afterValue, String versionFrom, String versionTo) {
        MetaChangeDO change = newChange(ctx, objectType, objectId, tableId, dbId, changeType,
                property, beforeValue, afterValue);
        change.setVersionFrom(versionFrom);
        change.setVersionTo(versionTo);
        changeMapper.insert(change);
    }

    /**
     * 构造元数据结构或属性差异记录。
     */
    private MetaChangeDO newChange(MetaCollectContext ctx, int objectType, Long objectId, Long tableId,
            Long dbId, int changeType, String property, String beforeValue,
            String afterValue) {
        return MetaChangeDO.builder()
                .instanceId(ctx.getInstanceId())
                .changeSource(ctx.resolveChangeSource())
                .objectType(objectType)
                .objectId(objectId)
                .tableId(tableId)
                .dbId(dbId)
                .changeType(changeType)
                .property(property)
                .beforeValue(StrUtil.maxLength(beforeValue, 2000))
                .afterValue(StrUtil.maxLength(afterValue, 2000))
                .build();
    }

    /**
     * 统计有效字段。
     */
    private int countActiveColumns(Long tableId) {
        List<MetaColumnDO> columns = columnMapper.selectListByTableId(tableId);
        return (int) columns.stream()
                .filter(c -> c.getObjectStatus() == null
                        || c.getObjectStatus() == MetaDbDO.OBJECT_STATUS_ACTIVE)
                .count();
    }

    /**
     * 按库名推断数仓分层（仅新建时赋默认值，人工可改）
     */
    private String inferLayer(String dbName) {
        if (StrUtil.isBlank(dbName)) {
            return null;
        }
        String lower = dbName.toLowerCase();
        for (String layer : new String[]{"ods", "dwd", "dws", "ads", "dim"}) {
            if (lower.equals(layer) || lower.startsWith(layer + "_") || lower.endsWith("_" + layer)) {
                return layer.toUpperCase();
            }
        }
        return null;
    }

    /**
     * 将元数据变更级别转换为显示名称。
     */
    private String levelName(int level) {
        switch (level) {
            case MetaVersionUtils.LEVEL_MAJOR:
                return "MAJOR";
            case MetaVersionUtils.LEVEL_MINOR:
                return "MINOR";
            case MetaVersionUtils.LEVEL_PATCH:
                return "PATCH";
            default:
                return "NONE";
        }
    }

    /**
     * 将缺失集合转换为空列表，保留已有集合。
     */
    private static <T> List<T> safeList(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }

}

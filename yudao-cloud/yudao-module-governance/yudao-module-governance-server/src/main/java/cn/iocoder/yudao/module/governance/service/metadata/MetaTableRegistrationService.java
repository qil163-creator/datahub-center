package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectContext;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectLogger;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel.CollectedColumn;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel.CollectedTable;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaDiffEngine;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_COLLECT_TASK_DATASOURCE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_TABLE_NOT_EXISTS;

/** 接收建模与主数据发布回写，复用采集的差异比对和写入流程。 */
@Service
public class MetaTableRegistrationService {

    @Resource
    private MetaCollectInstanceMapper instanceMapper;

    @Resource
    private MetaCollectLogMapper logMapper;

    @Resource
    private MetaTableMapper tableMapper;

    @Resource
    private MetaDiffEngine diffEngine;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    public Long registerTable(MetaTableRegistration registration) {
        return registerTablePrepared(registration, prepareRegistration(registration.getDatasourceId()));
    }

    /** 在写入事务外解析数据源，仅返回登记所需的来源信息。 */
    public RegistrationSource prepareRegistration(Long datasourceId) {
        MasterDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(datasourceId,
                META_COLLECT_TASK_DATASOURCE_NOT_EXISTS);
        return new RegistrationSource(datasourceId, conn.getName(), conn.getProductType().name());
    }

    /** 在调用方事务内使用已解析的来源写入登记结果。 */
    public Long registerTablePrepared(MetaTableRegistration registration, RegistrationSource source) {
        if (source == null || !Objects.equals(source.datasourceId(), registration.getDatasourceId())) {
            throw exception(META_COLLECT_TASK_DATASOURCE_NOT_EXISTS);
        }
        CollectedTable table = collectedTable(registration);
        LocalDateTime startTime = LocalDateTime.now();
        MetaCollectInstanceDO instance = MetaCollectInstanceDO.builder()
                .triggerType(MetaCollectInstanceDO.TRIGGER_REGISTER)
                .status(MetaCollectInstanceDO.STATUS_RUNNING)
                .stage(MetaCollectInstanceDO.STAGE_DIFF)
                .dbCount(0).tableCount(0).columnCount(0)
                .tableAdd(0).tableUpdate(0).tableDelete(0)
                .columnAdd(0).columnUpdate(0).columnDelete(0)
                .startTime(startTime)
                .build();
        instanceMapper.insert(instance);

        MetaCollectLogger logger = new MetaCollectLogger(logMapper, instance.getId(), null);
        MetaCollectContext ctx = new MetaCollectContext();
        ctx.setInstanceId(instance.getId());
        ctx.setDatasourceId(registration.getDatasourceId());
        ctx.setSourceSystemName(registration.getSourceSystemName());
        ctx.setDbType(source.dbType());
        ctx.setTriggerType(MetaCollectInstanceDO.TRIGGER_REGISTER);
        ctx.setLogger(logger);

        boolean success = false;
        try {
            String dbName = registration.getDbName().trim();
            logger.info(MetaCollectInstanceDO.STAGE_DIFF, null, String.format(
                    "登记开始：数据源=%s，库=%s，表=%s", source.datasourceName(), dbName, table.getTableName()));
            MetaDbDO db = diffEngine.upsertDb(ctx, dbName);
            diffEngine.processTable(ctx, db, table);
            diffEngine.refreshDbStat(db);

            MetaTableDO tableDO = tableMapper.selectByDbIdAndName(db.getId(), table.getTableName());
            if (tableDO == null) {
                throw exception(META_TABLE_NOT_EXISTS);
            }
            success = true;
            return tableDO.getId();
        } finally {
            finishInstance(instance.getId(), ctx, startTime, success);
            logger.flush();
        }
    }

    private CollectedTable collectedTable(MetaTableRegistration registration) {
        List<CollectedColumn> columns = new ArrayList<>(registration.getColumns().size());
        List<String> primaryKeys = new ArrayList<>();
        int ordinal = 0;
        for (MetaTableRegistration.Column column : registration.getColumns()) {
            String columnName = column.getColumnName().trim();
            int pkFlag = column.getPkFlag() == null ? 0 : column.getPkFlag();
            columns.add(CollectedColumn.builder()
                    .columnName(columnName)
                    .ordinalPosition(++ordinal)
                    .remarks(column.getColumnComment())
                    .dataType(column.getDataType())
                    .dataLength(column.getDataLength())
                    .dataPrecision(column.getDataPrecision())
                    .dataScale(column.getDataScale())
                    .nullableFlag(column.getNullableFlag() == null ? 1 : column.getNullableFlag())
                    .pkFlag(pkFlag)
                    .autoIncrementFlag(0)
                    .build());
            if (pkFlag == 1) {
                primaryKeys.add(columnName);
            }
        }
        return CollectedTable.builder()
                .tableName(registration.getTableName().trim())
                .tableType(MetaTableDO.TYPE_TABLE)
                .remarks(registration.getTableComment())
                .primaryKeys(primaryKeys)
                .columns(columns)
                .build();
    }

    private void finishInstance(Long instanceId, MetaCollectContext ctx, LocalDateTime startTime, boolean success) {
        LocalDateTime endTime = LocalDateTime.now();
        MetaCollectInstanceDO update = new MetaCollectInstanceDO();
        update.setId(instanceId);
        update.setDbCount(1);
        update.setTableCount(ctx.getTableProcessed().get());
        update.setColumnCount(ctx.getColumnProcessed().get());
        update.setTableAdd(ctx.getTableAdd().get());
        update.setTableUpdate(ctx.getTableUpdate().get());
        update.setTableDelete(ctx.getTableDelete().get());
        update.setColumnAdd(ctx.getColumnAdd().get());
        update.setColumnUpdate(ctx.getColumnUpdate().get());
        update.setColumnDelete(ctx.getColumnDelete().get());
        update.setEndTime(endTime);
        update.setDurationMs(Duration.between(startTime, endTime).toMillis());
        update.setStatus(success ? MetaCollectInstanceDO.STATUS_SUCCESS : MetaCollectInstanceDO.STATUS_FAIL);
        if (!success) {
            update.setErrorType(MetaCollectInstanceDO.ERROR_PARSE);
        }
        update.setStage(MetaCollectInstanceDO.STAGE_DONE);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tableAdd", update.getTableAdd());
        report.put("tableUpdate", update.getTableUpdate());
        report.put("columnAdd", update.getColumnAdd());
        report.put("columnUpdate", update.getColumnUpdate());
        report.put("columnDelete", update.getColumnDelete());
        update.setReport(JsonUtils.toJsonString(report));
        instanceMapper.updateById(update);

        ctx.getLogger().info(MetaCollectInstanceDO.STAGE_DONE, null, String.format(
                "登记结束：表新增=%s 变更=%s，字段新增=%s 变更=%s 删除=%s",
                update.getTableAdd(), update.getTableUpdate(), update.getColumnAdd(), update.getColumnUpdate(),
                update.getColumnDelete()));
    }

    public record RegistrationSource(Long datasourceId, String datasourceName, String dbType) {

    }

}

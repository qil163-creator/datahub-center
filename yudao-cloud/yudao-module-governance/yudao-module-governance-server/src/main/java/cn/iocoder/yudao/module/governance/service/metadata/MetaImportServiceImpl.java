package cn.iocoder.yudao.module.governance.service.metadata;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaImportRowVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaTableRegisterReqVO;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_COLLECT_TASK_DATASOURCE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.META_TABLE_NOT_EXISTS;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 数据治理-元数据导入与手工登记 Service 实现
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class MetaImportServiceImpl implements MetaImportService {

    private static final int MAX_ERRORS = 20;

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

    /**
     * Excel 数据字典导入（单 Sheet：每行一个字段，表级信息取该表首行）
     */
    @Override
    public MetaImportRespVO importDict(Long datasourceId, Long sourceSystemId, String sourceSystemName,
            MultipartFile file) throws Exception {
        List<MetaImportRowVO> rows = ExcelUtils.read(file, MetaImportRowVO.class);
        MetaImportRespVO respVO = new MetaImportRespVO();
        // 行校验 + 按 库 → 表 分组（保持模板行序作为字段顺序）
        Map<String, Map<String, List<MetaImportRowVO>>> grouped = new LinkedHashMap<>();
        int invalidRows = 0;
        for (int i = 0; i < rows.size(); i++) {
            MetaImportRowVO row = rows.get(i);
            int rowNo = i + 2;
            String reason = validateRow(row);
            if (reason != null) {
                invalidRows++;
                if (respVO.getErrors().size() < MAX_ERRORS) {
                    respVO.getErrors().add("第 " + rowNo + " 行：" + reason);
                }
                continue;
            }
            grouped.computeIfAbsent(row.getDbName().trim(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getTableName().trim(), key -> new ArrayList<>())
                    .add(row);
        }
        respVO.setInvalidRows(invalidRows);

        // 转换为统一采集模型
        Map<String, List<CollectedTable>> dbTables = new LinkedHashMap<>();
        grouped.forEach((dbName, tables) -> {
            List<CollectedTable> collectedTables = new ArrayList<>();
            tables.forEach((tableName, tableRows) -> collectedTables.add(buildTable(tableName, tableRows)));
            dbTables.put(dbName, collectedTables);
        });

        execute(datasourceId, sourceSystemId, sourceSystemName,
                MetaCollectInstanceDO.TRIGGER_IMPORT, dbTables, respVO);
        return respVO;
    }

    /**
     * 手工登记单张表
     *
     *  @return 表元数据编号
     */
    @Override
    public Long registerTable(MetaTableRegisterReqVO reqVO) {
        return registerTablePrepared(reqVO, prepareRegistration(reqVO.getDatasourceId()));
    }

    /**
     * 只在事务外解析来源。返回值不包含密码、连接串。
     */
    @Override
    public RegistrationSource prepareRegistration(Long datasourceId) {
        MasterDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(datasourceId,
                META_COLLECT_TASK_DATASOURCE_NOT_EXISTS);
        return new RegistrationSource(datasourceId, conn.getName(), conn.getProductType().name());
    }

    /**
     * 使用已校验来源在调用方本地事务内登记；不得再次请求来源服务。
     */
    @Override
    public Long registerTablePrepared(MetaTableRegisterReqVO reqVO, RegistrationSource source) {
        if (source == null || !Objects.equals(source.datasourceId(), reqVO.getDatasourceId())) {
            throw exception(META_COLLECT_TASK_DATASOURCE_NOT_EXISTS);
        }
        List<CollectedColumn> columns = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        int ordinal = 0;
        for (MetaTableRegisterReqVO.RegisterColumn column : reqVO.getColumns()) {
            ordinal++;
            columns.add(CollectedColumn.builder()
                    .columnName(column.getColumnName().trim())
                    .ordinalPosition(ordinal)
                    .remarks(column.getColumnComment())
                    .dataType(column.getDataType())
                    .dataLength(column.getDataLength())
                    .dataPrecision(column.getDataPrecision())
                    .dataScale(column.getDataScale())
                    .nullableFlag(column.getNullableFlag() == null ? 1 : column.getNullableFlag())
                    .defaultValue(column.getDefaultValue())
                    .pkFlag(column.getPkFlag() == null ? 0 : column.getPkFlag())
                    .autoIncrementFlag(column.getAutoIncrementFlag() == null ? 0 : column.getAutoIncrementFlag())
                    .build());
            if (column.getPkFlag() != null && column.getPkFlag() == 1) {
                primaryKeys.add(column.getColumnName().trim());
            }
        }
        CollectedTable table = CollectedTable.builder()
                .tableName(reqVO.getTableName().trim())
                .tableType(MetaTableDO.TYPE_TABLE)
                .remarks(reqVO.getTableComment())
                .primaryKeys(primaryKeys)
                .columns(columns)
                .build();

        MetaImportRespVO respVO = new MetaImportRespVO();
        respVO.setInvalidRows(0);
        Map<String, List<CollectedTable>> dbTables = new LinkedHashMap<>();
        dbTables.put(reqVO.getDbName().trim(), List.of(table));
        MetaCollectContext ctx = execute(reqVO.getDatasourceId(), reqVO.getSourceSystemId(),
                reqVO.getSourceSystemName(), MetaCollectInstanceDO.TRIGGER_REGISTER, dbTables, respVO, source, true);

        // 返回登记结果表编号
        MetaDbDO db = diffEngine.upsertDb(ctx, reqVO.getDbName().trim());
        MetaTableDO tableDO = tableMapper.selectByDbIdAndName(db.getId(), reqVO.getTableName().trim());
        if (tableDO == null) {
            throw exception(META_TABLE_NOT_EXISTS);
        }
        return tableDO.getId();
    }

    /**
     * 旁路通道统一执行：虚拟实例 + 同一 diff 引擎；不做删除识别（只增改）
     */
    private MetaCollectContext execute(Long datasourceId, Long sourceSystemId, String sourceSystemName,
            int triggerType, Map<String, List<CollectedTable>> dbTables,
            MetaImportRespVO respVO) {
        return execute(datasourceId, sourceSystemId, sourceSystemName, triggerType, dbTables, respVO,
                prepareRegistration(datasourceId), false);
    }

    /**
     * 执行元数据导入处理流程，并维护相应运行结果。
     */
    private MetaCollectContext execute(Long datasourceId, Long sourceSystemId, String sourceSystemName,
            int triggerType, Map<String, List<CollectedTable>> dbTables,
            MetaImportRespVO respVO, RegistrationSource source, boolean failFast) {
        LocalDateTime startTime = LocalDateTime.now();
        MetaCollectInstanceDO instance = MetaCollectInstanceDO.builder()
                .triggerType(triggerType)
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
        ctx.setDatasourceId(datasourceId);
        ctx.setSourceSystemId(sourceSystemId);
        ctx.setSourceSystemName(sourceSystemName);
        ctx.setDbType(source.dbType());
        ctx.setTriggerType(triggerType);
        ctx.setLogger(logger);

        int processFail = 0;
        try {
            logger.info(MetaCollectInstanceDO.STAGE_DIFF, null, String.format(
                    "%s开始：数据源=%s，库数量=%s",
                    triggerType == MetaCollectInstanceDO.TRIGGER_IMPORT ? "数据字典导入" : "手工登记",
                    source.datasourceName(), dbTables.size()));
            for (Map.Entry<String, List<CollectedTable>> entry : dbTables.entrySet()) {
                MetaDbDO db = diffEngine.upsertDb(ctx, entry.getKey());
                for (CollectedTable collected : entry.getValue()) {
                    try {
                        diffEngine.processTable(ctx, db, collected);
                    } catch (Exception ex) {
                        processFail++;
                        if (failFast) {
                            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
                        }
                        if (respVO.getErrors().size() < MAX_ERRORS) {
                            respVO.getErrors().add(entry.getKey() + "." + collected.getTableName()
                                    + "：" + StrUtil.maxLength(ex.getMessage(), 200));
                        }
                        logger.error(MetaCollectInstanceDO.STAGE_WRITE,
                                "[TABLE] " + entry.getKey() + "." + collected.getTableName(),
                                "写入失败：" + ex.getMessage());
                        log.error("[execute][导入/登记 表({}.{}) 写入失败]",
                                entry.getKey(), collected.getTableName(), ex);
                    }
                }
                diffEngine.refreshDbStat(db);
            }
        } finally {
            finishInstance(instance, ctx, dbTables.size(), processFail,
                    respVO.getInvalidRows() == null ? 0 : respVO.getInvalidRows(), startTime, logger);
            logger.flush();
        }

        respVO.setInstanceId(instance.getId());
        respVO.setDbCount(dbTables.size());
        respVO.setTableCount(ctx.getTableProcessed().get());
        respVO.setColumnCount(ctx.getColumnProcessed().get());
        respVO.setTableAdd(ctx.getTableAdd().get());
        respVO.setTableUpdate(ctx.getTableUpdate().get());
        respVO.setColumnAdd(ctx.getColumnAdd().get());
        respVO.setColumnUpdate(ctx.getColumnUpdate().get());
        respVO.setColumnDelete(ctx.getColumnDelete().get());
        return ctx;
    }

    /**
     * 结束实例。
     */
    private void finishInstance(MetaCollectInstanceDO instance, MetaCollectContext ctx, int dbCount,
            int processFail, int invalidRows, LocalDateTime startTime,
            MetaCollectLogger logger) {
        LocalDateTime endTime = LocalDateTime.now();
        MetaCollectInstanceDO update = new MetaCollectInstanceDO();
        update.setId(instance.getId());
        update.setDbCount(dbCount);
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
        boolean hasFail = processFail > 0 || invalidRows > 0;
        boolean hasSuccess = ctx.getTableProcessed().get() > 0;
        if (!hasFail) {
            update.setStatus(MetaCollectInstanceDO.STATUS_SUCCESS);
        } else if (hasSuccess) {
            update.setStatus(MetaCollectInstanceDO.STATUS_PARTIAL);
            update.setErrorType(MetaCollectInstanceDO.ERROR_PARTIAL);
        } else {
            update.setStatus(MetaCollectInstanceDO.STATUS_FAIL);
            update.setErrorType(MetaCollectInstanceDO.ERROR_PARSE);
        }
        update.setStage(MetaCollectInstanceDO.STAGE_DONE);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tableAdd", ctx.getTableAdd().get());
        report.put("tableUpdate", ctx.getTableUpdate().get());
        report.put("columnAdd", ctx.getColumnAdd().get());
        report.put("columnUpdate", ctx.getColumnUpdate().get());
        report.put("columnDelete", ctx.getColumnDelete().get());
        report.put("invalidRows", invalidRows);
        report.put("processFail", processFail);
        update.setReport(JsonUtils.toJsonString(report));
        instanceMapper.updateById(update);

        logger.info(MetaCollectInstanceDO.STAGE_DONE, null, String.format(
                "执行完成：表新增=%s 变更=%s，字段新增=%s 变更=%s 删除=%s，非法行=%s，失败表=%s",
                ctx.getTableAdd().get(), ctx.getTableUpdate().get(), ctx.getColumnAdd().get(),
                ctx.getColumnUpdate().get(), ctx.getColumnDelete().get(), invalidRows, processFail));
    }

    /**
     * 校验元数据导入的数据行，不满足约束时拒绝当前操作。
     */
    private String validateRow(MetaImportRowVO row) {
        if (row == null) {
            return "空行";
        }
        if (StrUtil.isBlank(row.getDbName())) {
            return "库名为空";
        }
        if (StrUtil.isBlank(row.getTableName())) {
            return "表名为空";
        }
        if (StrUtil.isBlank(row.getColumnName())) {
            return "字段名为空";
        }
        return null;
    }

    /**
     * 构造元数据导入的表。
     */
    private CollectedTable buildTable(String tableName, List<MetaImportRowVO> tableRows) {
        List<CollectedColumn> columns = new ArrayList<>(tableRows.size());
        List<String> primaryKeys = new ArrayList<>();
        // 同名字段去重（保留首行）
        Set<String> seen = new HashSet<>();
        int ordinal = 0;
        for (MetaImportRowVO row : tableRows) {
            String columnName = row.getColumnName().trim();
            if (!seen.add(columnName)) {
                continue;
            }
            ordinal++;
            boolean pk = parseFlag(row.getPkText(), false);
            columns.add(CollectedColumn.builder()
                    .columnName(columnName)
                    .ordinalPosition(ordinal)
                    .remarks(row.getColumnComment())
                    .dataType(row.getDataType())
                    .dataLength(row.getDataLength())
                    .dataPrecision(row.getDataPrecision())
                    .dataScale(row.getDataScale())
                    .nullableFlag(parseFlag(row.getNullableText(), true) ? 1 : 0)
                    .defaultValue(row.getDefaultValue())
                    .pkFlag(pk ? 1 : 0)
                    .autoIncrementFlag(parseFlag(row.getAutoIncrementText(), false) ? 1 : 0)
                    .build());
            if (pk) {
                primaryKeys.add(columnName);
            }
        }
        // 表注释取该表首个非空值
        String tableComment = tableRows.stream()
                .map(MetaImportRowVO::getTableComment)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        return CollectedTable.builder()
                .tableName(tableName)
                .tableType(MetaTableDO.TYPE_TABLE)
                .remarks(tableComment)
                .primaryKeys(primaryKeys)
                .columns(columns)
                .build();
    }

    /**
     * 是/否 文本解析（兼容 Y/N、1/0、true/false）
     */
    private boolean parseFlag(String text, boolean defaultValue) {
        if (StrUtil.isBlank(text)) {
            return defaultValue;
        }
        String value = text.trim().toLowerCase();
        return "是".equals(value) || "y".equals(value) || "yes".equals(value)
                || "1".equals(value) || "true".equals(value);
    }

}

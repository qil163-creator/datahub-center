package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.framework.dbswitch.core.service.QueryFilterOperator;
import cn.iocoder.yudao.framework.dbswitch.core.service.QueryFilterSql;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedTableMutation;
import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataPageRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 主数据记录维护 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
@Slf4j
public class MasterDataServiceImpl implements MasterDataService {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private MasterModelService modelService;

    @Resource
    private MasterColumnMapper columnMapper;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    /**
     * 分页查询主数据记录（模型须已发布）
     */
    @Override
    public MasterDataPageRespVO getMasterDataPage(MasterDataPageReqVO pageReqVO) {
        MasterModelDO model = modelService.validateModelReleased(pageReqVO.getModelId());
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(model.getId());
        Map<String, MasterColumnDO> columnMap = convertMap(columns, MasterColumnDO::getName);
        MasterDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(model.getDatabaseId(), MASTER_MODEL_DATABASE_NOT_EXISTS);
        ProductTypeEnum productType = conn.getProductType();

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        sql.append("SELECT * FROM ")
                .append(productType.quoteSchemaTableName(conn.getSchema(), model.getTableName()))
                .append(" WHERE 1=1");
        if (CollUtil.isNotEmpty(pageReqVO.getConditions())) {
            if (pageReqVO.getConditions().size() > 50) {
                throw exception(MASTER_DATA_QUERY_CONDITION_INVALID, "查询条件不能超过50项");
            }
            for (MasterDataPageReqVO.Condition condition : pageReqVO.getConditions()) {
                if (condition == null) {
                    throw exception(MASTER_DATA_QUERY_CONDITION_INVALID, "查询条件项不能为空");
                }
                // 旧客户端未提交操作符时，保留空值忽略及 LIKE 通配符语义。
                boolean legacy = condition.getOperator() == null;
                if (legacy && StrUtil.isBlank(condition.getValue())) {
                    continue;
                }
                // 条件字段做白名单校验，类型以服务端字段配置为准
                MasterColumnDO column = columnMap.get(condition.getName());
                if (column == null) {
                    throw exception(MASTER_DATA_QUERY_COLUMN_INVALID, condition.getName());
                }
                if (legacy) {
                    boolean stringType = Integer.valueOf(ColumnMetaData.TYPE_STRING).equals(column.getFieldType());
                    sql.append(" AND ").append(productType.quoteName(column.getName()))
                            .append(stringType ? " LIKE ?" : " = ?");
                    params.add(stringType ? "%" + condition.getValue() + "%"
                            : convertCell(column, condition.getValue()));
                    continue;
                }
                try {
                    QueryFilterOperator operator = QueryFilterOperator.fromCode(condition.getOperator());
                    int logicalType = column.getFieldType() == null ? ColumnMetaData.TYPE_NONE : column.getFieldType();
                    if (!operator.supports(logicalType)) {
                        throw exception(MASTER_DATA_QUERY_CONDITION_INVALID,
                                "字段【" + column.getName() + "】不支持此操作符");
                    }
                    if (operator.requiresValue() && StrUtil.isBlank(condition.getValue())) {
                        throw exception(MASTER_DATA_QUERY_CONDITION_INVALID,
                                "字段【" + column.getName() + "】的查询值不能为空");
                    }
                    // 无值操作符忽略旧输入框遗留的值，不进行类型转换。
                    Object value = operator.requiresValue() ? convertCell(column, condition.getValue()) : null;
                    QueryFilterSql.Fragment fragment = QueryFilterSql.render(productType, column.getName(),
                            logicalType, operator, value);
                    sql.append(" AND ").append(fragment.sql());
                    params.addAll(fragment.parameters());
                } catch (IllegalArgumentException ex) {
                    throw exception(MASTER_DATA_QUERY_CONDITION_INVALID,
                            "字段【" + column.getName() + "】的操作符或查询值不合法");
                } catch (ServiceException ex) {
                    if (MASTER_DATA_TYPE_ERROR.getCode().equals(ex.getCode())) {
                        throw exception(MASTER_DATA_QUERY_CONDITION_INVALID,
                                "字段【" + column.getName() + "】的查询值不符合类型要求");
                    }
                    throw ex;
                }
            }
        }
        // 主键排序保证分页稳定
        List<String> pkNames = pkNames(columns);
        if (CollUtil.isNotEmpty(pkNames)) {
            sql.append(" ORDER BY ").append(pkNames.stream()
                    .map(productType::quoteName).collect(Collectors.joining(", ")));
        }
        IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(productType);
        JdbcSelectResult result = metaDataService.pageDataBySql(
                conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                sql.toString(), params, pageReqVO.getPageNo(), pageReqVO.getPageSize(), null);
        MasterDataPageRespVO respVO = new MasterDataPageRespVO();
        respVO.setColumns(result.getColumns());
        respVO.setList(toWireRows(result.getList() != null ? result.getList() : result.getRowData()));
        respVO.setTotal(result.getTotal() == null ? 0L : result.getTotal());
        return respVO;
    }

    // ==================== 内部方法 ====================

    /**
     * 按完整主键读取原行；空元素表示不存在，不执行任何写入。
     */
    @Override
    public List<Map<String, Object>> snapshotRows(Long modelId, List<Map<String, Object>> rows) {
        MasterModelDO model = modelService.validateModelReleased(modelId);
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(modelId);
        List<MasterColumnDO> keys = columns.stream().filter(c -> Integer.valueOf(1).equals(c.getPk())).toList();
        if (keys.isEmpty()) {
            throw exception(MASTER_MODEL_NO_PK);
        }
        MasterDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(model.getDatabaseId(), MASTER_MODEL_DATABASE_NOT_EXISTS);
        ProductTypeEnum product = conn.getProductType();
        IMetaDataByJdbcService metadata = dbSwitchRuntime.jdbcMetadata(product);
        String sql = "SELECT * FROM " + product.quoteSchemaTableName(conn.getSchema(), model.getTableName())
                + " WHERE " + keys.stream().map(c -> product.quoteName(c.getName()) + " = ?")
                .collect(Collectors.joining(" AND "));
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Object> params = keys.stream().map(c -> convertCell(c, row.get(c.getName()))).toList();
            JdbcSelectResult result = metadata.pageDataBySql(conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                    sql, params, 1, 2, null);
            List<Map<String, Object>> matches = toWireRows(
                    result.getList() != null ? result.getList() : result.getRowData());
            if (matches.size() > 1) {
                throw exception(MASTER_DATA_ROW_INVALID, "物理表主键不唯一");
            }
            snapshots.add(matches.isEmpty() ? null : matches.getFirst());
        }
        return snapshots;
    }

    /**
     * 校验动态记录的字段、类型、主键、长度和批次边界。
     */
    @Override
    public void validateRows(Long modelId, List<Map<String, Object>> rows, String operation) {
        modelService.validateModelReleased(modelId);
        if (rows == null || rows.isEmpty() || rows.size() > 200) {
            throw exception(MASTER_DATA_ROW_INVALID, "每次提交1至200条记录");
        }
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(modelId);
        Map<String, MasterColumnDO> fields = convertMap(columns, MasterColumnDO::getName);
        for (Map<String, Object> row : rows) {
            if (row == null || !fields.keySet().containsAll(row.keySet())) {
                throw exception(MASTER_DATA_ROW_INVALID, "包含未知字段");
            }
            for (MasterColumnDO column : columns) {
                Object value = row.get(column.getName());
                boolean primary = Integer.valueOf(1).equals(column.getPk());
                if ((primary || (!"DELETE".equals(operation) && Integer.valueOf(0).equals(column.getNullable())))
                        && (value == null || value instanceof String text && text.isBlank())) {
                    throw exception(MASTER_DATA_ROW_INVALID, "字段【" + column.getName() + "】不能为空");
                }
                if (!"DELETE".equals(operation) || primary) {
                    Object converted = convertCell(column, value);
                    if (converted instanceof String text && column.getFieldLength() != null
                            && text.length() > column.getFieldLength()) {
                        throw exception(MASTER_DATA_ROW_INVALID, "字段【" + column.getName() + "】超出长度");
                    }
                    if (converted instanceof BigDecimal decimal) {
                        if (column.getFieldScale() != null && decimal.scale() > column.getFieldScale()) {
                            throw exception(MASTER_DATA_ROW_INVALID, "精确小数超过配置的小数位数");
                        }
                        if (column.getFieldLength() != null && decimal.precision() > column.getFieldLength()) {
                            throw exception(MASTER_DATA_ROW_INVALID, "精确小数超过配置的精度");
                        }
                    }
                }
            }
        }
    }

    /**
     * 按模型类型规范快照，精确小数和长整数仍为字符串。
     */
    @Override
    public List<Map<String, Object>> normalizeRows(Long modelId, List<Map<String, Object>> rows, String operation) {
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(modelId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (MasterColumnDO column : columns) {
                if (!"DELETE".equals(operation) || Integer.valueOf(1).equals(column.getPk())) {
                    normalized.put(column.getName(), convertCell(column, row.get(column.getName())));
                }
            }
            result.add(normalized);
        }
        return toWireRows(result);
    }

    /**
     * 委托 DB Switch，在外部数据库事务中按原快照进行整批原子条件写。
     */
    @Override
    public GuardedMutationResult applyGuardedChange(Long modelId, String operation,
            List<Map<String, Object>> before, List<Map<String, Object>> rows) {
        MasterModelDO model = modelService.validateModelReleased(modelId);
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(modelId);
        MasterDatabaseResolver.ConnectionInfo conn =
                databaseResolver.resolve(model.getDatabaseId(), MASTER_MODEL_DATABASE_NOT_EXISTS);
        GuardedTableMutation.Operation kind = switch (operation) {
            case "CREATE" -> GuardedTableMutation.Operation.INSERT;
            case "UPDATE" -> GuardedTableMutation.Operation.UPDATE;
            case "DELETE" -> GuardedTableMutation.Operation.DELETE;
            default -> throw exception(MASTER_DATA_ROW_INVALID, "操作类型不支持");
        };
        List<Map<String, Object>> expected = jdbcRows(columns, before);
        List<Map<String, Object>> values = jdbcRows(columns, rows);
        HikariDataSource source = databaseResolver.createDataSource(conn);
        GuardedMutationResult result;
        try {
            result = dbSwitchRuntime.guardedMutate(source, conn.getProductType(), new GuardedTableMutation(
                    conn.getSchema(), model.getTableName(), convertList(columns, MasterColumnDO::getName),
                    pkNames(columns), kind, expected, values));
        } catch (RuntimeException ex) {
            try {
                source.close();
            } catch (RuntimeException cleanup) {
                log.warn("主数据连接池清理未确认，模型编号={}", modelId);
            }
            throw ex;
        }
        try {
            source.close();
        } catch (RuntimeException ex) {
            log.warn("主数据连接池清理未确认，模型编号={}", modelId);
            return new GuardedMutationResult(result.status(), result.failure(), false);
        }
        if (!result.cleanupConfirmed()) {
            log.warn("主数据连接清理未确认，模型编号={}", modelId);
        }
        return result;
    }

    /**
     * 按模型字段类型转换 JDBC 查询结果。
     */
    private List<Map<String, Object>> jdbcRows(List<MasterColumnDO> columns, List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                result.add(null);
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            for (MasterColumnDO column : columns) {
                Object cell = convertCell(column, row.get(column.getName()));
                if (cell instanceof Date date && !(cell instanceof java.sql.Timestamp)
                        && !(cell instanceof java.sql.Date) && !(cell instanceof java.sql.Time)) {
                    cell = switch (column.getFieldType()) {
                        case ColumnMetaData.TYPE_DATE -> new java.sql.Date(date.getTime());
                        case ColumnMetaData.TYPE_TIME -> new java.sql.Time(date.getTime());
                        case ColumnMetaData.TYPE_TIMESTAMP -> new java.sql.Timestamp(date.getTime());
                        default -> cell;
                    };
                }
                value.put(column.getName(), cell);
            }
            result.add(value);
        }
        return result;
    }

    /**
     * 读取主数据模型定义的主键字段名称。
     */
    private List<String> pkNames(List<MasterColumnDO> columns) {
        return columns.stream()
                .filter(column -> column.getPk() != null && column.getPk() == 1)
                .map(MasterColumnDO::getName).collect(Collectors.toList());
    }

    /**
     * 按字段类型转换单元格值（字符串 → 对应 Java 类型），供写入与查询条件使用
     */
    static List<Map<String, Object>> toWireRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> {
            Map<String, Object> wire = new LinkedHashMap<>();
            row.forEach((key, value) -> wire.put(key, value instanceof BigDecimal decimal
                    ? decimal.toPlainString() : value instanceof BigInteger || value instanceof Long
                    || value instanceof Integer || value instanceof Short || value instanceof Byte
                    || value instanceof java.sql.Timestamp || value instanceof java.sql.Date || value instanceof java.sql.Time
                    ? value.toString() : value));
            return wire;
        }).toList();
    }

    /**
     * 转换单元格。
     */
    static Object convertCell(MasterColumnDO column, Object cellData) {
        if (cellData == null) {
            return null;
        }
        try {
            int fieldType = column.getFieldType();
            String text = String.valueOf(cellData);
            if (ColumnMetaData.TYPE_STRING == fieldType) {
                if (!(cellData instanceof String)) {
                    throw new IllegalArgumentException("字符串字段须使用文本值");
                }
                return cellData;
            }
            if (ColumnMetaData.TYPE_NUMBER == fieldType) {
                if (StrUtil.isBlank(text)) {
                    return null;
                }
                double value = Double.parseDouble(text);
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("非有限数值");
                }
                return value;
            }
            if (ColumnMetaData.TYPE_INTEGER == fieldType) {
                if (StrUtil.isBlank(text)) {
                    return null;
                }
                if (cellData instanceof Double || cellData instanceof Float) {
                    throw new IllegalArgumentException("长整数须使用十进制字符串");
                }
                return Long.parseLong(text);
            }
            if (ColumnMetaData.TYPE_BIGNUMBER == fieldType) {
                // 精确小数必须以文本传入；JSON 浮点数已发生精度损失。
                if (!(cellData instanceof String)) {
                    throw new IllegalArgumentException("Exact decimals require a decimal string");
                }
                if (StrUtil.isBlank(text)) {
                    return null;
                }
                BigDecimal decimal = new BigDecimal(text);
                return column.getFieldScale() == null ? decimal
                        : decimal.setScale(column.getFieldScale(), java.math.RoundingMode.UNNECESSARY);
            }
            if (ColumnMetaData.TYPE_BOOLEAN == fieldType) {
                if (cellData instanceof Boolean || StrUtil.isBlank(text)) {
                    return StrUtil.isBlank(text) ? null : cellData;
                }
                if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                    throw new IllegalArgumentException("布尔值不正确");
                }
                return Boolean.parseBoolean(text);
            }
            if (ColumnMetaData.TYPE_DATE == fieldType) {
                if (cellData instanceof Number number) {
                    return new java.sql.Date(number.longValue());
                }
                return cellData instanceof Date date ? new java.sql.Date(date.getTime())
                        : StrUtil.isBlank(text) ? null : java.sql.Date.valueOf(text);
            }
            if (ColumnMetaData.TYPE_TIMESTAMP == fieldType) {
                if (cellData instanceof Number number) {
                    return new java.sql.Timestamp(number.longValue());
                }
                if (cellData instanceof java.sql.Timestamp) {
                    return cellData;
                }
                return cellData instanceof Date date ? new java.sql.Timestamp(date.getTime())
                        : StrUtil.isBlank(text) ? null : java.sql.Timestamp.valueOf(text.replace('T', ' '));
            }
            if (ColumnMetaData.TYPE_TIME == fieldType) {
                if (cellData instanceof Number number) {
                    return new java.sql.Time(number.longValue());
                }
                return cellData instanceof Date date ? new java.sql.Time(date.getTime())
                        : StrUtil.isBlank(text) ? null : java.sql.Time.valueOf(text);
            }
        } catch (Exception ex) {
            throw exception(MASTER_DATA_TYPE_ERROR,
                    String.format("字段【%s】的值不符合类型要求", column.getName()));
        }
        return cellData;
    }

}

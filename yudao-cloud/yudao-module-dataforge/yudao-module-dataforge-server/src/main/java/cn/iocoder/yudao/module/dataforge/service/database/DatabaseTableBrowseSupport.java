package cn.iocoder.yudao.module.dataforge.service.database;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableMeta;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabasePreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableMetaRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableRespVO;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_TABLE_NOT_EXISTS;

/**
 * 数据源表浏览：表名白名单解析与元数据/样例结果映射。
 */
final class DatabaseTableBrowseSupport {

    static final int DEFAULT_LIMIT = 20;

    static final int MAX_LIMIT = 100;

    static final int MAX_CELL_CHARS = 2000;

    /**
     * 限制数据源表浏览支持工具的实例化，统一通过静态能力使用。
     */
    private DatabaseTableBrowseSupport() {
    }

    /**
     * 解析表名。
     *
     * @param tables 表
     * @param requested 已请求
     * @return 解析表名文本
     */
    static String resolveTableName(List<DatabaseTableRespVO> tables, String requested) {
        if (StrUtil.isBlank(requested)) {
            throw exception(DATABASE_TABLE_NOT_EXISTS);
        }
        String target = requested.trim();
        if (tables != null) {
            for (DatabaseTableRespVO table : tables) {
                if (table != null && StrUtil.equalsIgnoreCase(table.getTableName(), target)) {
                    return table.getTableName();
                }
            }
        }
        throw exception(DATABASE_TABLE_NOT_EXISTS);
    }

    /**
     * 解析上限。
     *
     * @param limit 读取或处理上限
     * @return 解析上限数值
     */
    static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /**
     * 转换为元数据。
     *
     * @param meta 元数据
     * @param fallbackSchema 回退结构
     * @return 数据源表元数据响应数据
     */
    static DatabaseTableMetaRespVO toMetaVO(SchemaTableMeta meta, String fallbackSchema) {
        DatabaseTableMetaRespVO vo = new DatabaseTableMetaRespVO();
        if (meta == null) {
            vo.setSchemaName(fallbackSchema);
            vo.setColumns(Collections.emptyList());
            vo.setPrimaryKeys(Collections.emptyList());
            return vo;
        }
        vo.setSchemaName(StrUtil.blankToDefault(meta.getSchemaName(), fallbackSchema));
        vo.setTableName(meta.getTableName());
        vo.setTableType(displayTableType(meta));
        vo.setRemarks(meta.getRemarks());
        vo.setPrimaryKeys(meta.getPrimaryKeys() == null
                ? Collections.emptyList() : List.copyOf(meta.getPrimaryKeys()));
        vo.setCreateSql(meta.getCreateSql());
        vo.setColumns(toColumnVos(meta.getColumns()));
        return vo;
    }

    /**
     * 转换为预览。
     *
     * @param data 待处理数据
     * @param limit 读取或处理上限
     * @return 数据源预览响应数据
     */
    static DatabasePreviewRespVO toPreviewVO(SchemaTableData data, int limit) {
        DatabasePreviewRespVO vo = new DatabasePreviewRespVO();
        List<String> sourceColumns = data == null || data.getColumns() == null
                ? Collections.emptyList() : data.getColumns();
        List<String> keys = new ArrayList<>(sourceColumns.size());
        List<DatabasePreviewRespVO.Column> columns = new ArrayList<>(sourceColumns.size());
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String label : sourceColumns) {
            String safeLabel = label == null ? "" : label;
            String normalized = safeLabel.toLowerCase(Locale.ROOT);
            int occurrence = occurrences.merge(normalized, 1, Integer::sum);
            String key = occurrence == 1 ? safeLabel : safeLabel + "_" + occurrence;
            keys.add(key);
            columns.add(new DatabasePreviewRespVO.Column(key, safeLabel, null));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<List<Object>> sourceRows = data == null || data.getRows() == null
                ? Collections.emptyList() : data.getRows();
        boolean truncated = sourceRows.size() > limit;
        int count = truncated ? limit : sourceRows.size();
        for (int rowIndex = 0; rowIndex < count; rowIndex++) {
            List<Object> sourceRow = sourceRows.get(rowIndex);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int columnIndex = 0; columnIndex < keys.size(); columnIndex++) {
                Object value = sourceRow != null && columnIndex < sourceRow.size()
                        ? sourceRow.get(columnIndex) : null;
                row.put(keys.get(columnIndex), normalizeCell(value));
            }
            rows.add(row);
        }
        vo.setColumns(columns);
        vo.setRows(rows);
        vo.setTruncated(truncated);
        return vo;
    }

    /**
     * 获取数据源表浏览支持的失败详情。
     *
     * @param ex 异常信息
     * @return 数据源表浏览支持的失败详情文本
     */
    static String failureDetail(Throwable ex) {
        Throwable root = ExceptionUtil.getRootCause(ex);
        String detail = root != null ? root.getMessage() : (ex == null ? null : ex.getMessage());
        return StrUtil.maxLength(StrUtil.blankToDefault(detail, "未知错误"), 300);
    }

    /**
     * 判断是否满足连接失败条件。
     *
     * @param ex 异常信息
     * @return 判断结果
     */
    static boolean isConnectionFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof SQLTimeoutException
                    || current instanceof SQLTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if (state != null && state.startsWith("08")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        String msg = StrUtil.nullToEmpty(failureDetail(ex)).toLowerCase(Locale.ROOT);
        return msg.contains("connection refused")
                || msg.contains("connect timed out")
                || msg.contains("connection timed out")
                || msg.contains("unknown host")
                || msg.contains("no route to host")
                || msg.contains("the connection attempt failed")
                || msg.contains("check that the hostname and port")
                || msg.contains("authentication failed")
                || msg.contains("password authentication failed")
                || msg.contains("communications link failure")
                || msg.contains("通讯链路失败")
                || msg.contains("无法连接");
    }

    /**
     * 规范化单元格。
     *
     * @param value 待处理的值
     * @return 数据源表浏览支持
     */
    static Object normalizeCell(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = String.valueOf(value);
        return text.length() <= MAX_CELL_CHARS ? text : text.substring(0, MAX_CELL_CHARS);
    }

    /**
     * 获取数据源表浏览支持的展示表类型。
     *
     * @param meta 元数据
     * @return 数据源表浏览支持的展示表类型文本
     */
    private static String displayTableType(SchemaTableMeta meta) {
        try {
            if (meta.isViewTable() || "VIEW".equalsIgnoreCase(meta.getTableType())) {
                return "VIEW";
            }
        } catch (RuntimeException ignored) {
            // tableType 未设置时按物理表展示
        }
        return "BASE TABLE";
    }

    /**
     * 转换为列响应对象集合。
     *
     * @param columns 列集合
     * @return 转换为列响应对象集合结果集合
     */
    private static List<DatabaseTableColumnRespVO> toColumnVos(List<ColumnDescription> columns) {
        if (CollUtil.isEmpty(columns)) {
            return Collections.emptyList();
        }
        List<DatabaseTableColumnRespVO> result = new ArrayList<>(columns.size());
        for (ColumnDescription column : columns) {
            DatabaseTableColumnRespVO vo = new DatabaseTableColumnRespVO();
            vo.setFieldName(column.getFieldName());
            vo.setFieldTypeName(column.getFieldTypeName());
            vo.setRemarks(column.getRemarks());
            vo.setNullable(column.isNullable());
            vo.setPk(column.isPk());
            vo.setAutoIncrement(column.isAutoIncrement());
            vo.setLength(column.getLength() != null ? column.getLength() : column.getDisplaySize());
            vo.setPrecision(column.getPrecision() != null ? column.getPrecision() : column.getPrecisionSize());
            vo.setScale(column.getScaleSize());
            vo.setDefaultValue(column.getDefaultValue());
            result.add(vo);
        }
        return result;
    }

}

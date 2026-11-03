package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.TableIndexEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexFieldMeta;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 把 {@link DatabaseMetaData#getIndexInfo} 的结果集按索引还原（移植自 dbswitch 2.0.2 的 queryTableIndexes）。
 * <p>
 * 跳过 tableIndexStatistic 统计行；同一索引的唯一性或部分索引条件不一致、键序号重复或不连续、索引名为空时拒绝，
 * 不按表列顺序或列名猜测键顺序。主键背后的索引同样返回，是否跳过由调用方按实际主键决定。
 * 不拥有、不关闭调用方的结果集。
 */
public final class JdbcIndexMetadata {

    /**
     * 初始化 {@code JdbcIndexMetadata}。
     */
    private JdbcIndexMetadata() {
    }

    /**
     * 收集当前输入中的结构或数据结果。
     */
    public static List<IndexDescription> collect(ResultSet rows) throws SQLException {
        boolean filterColumn = hasColumn(rows, "FILTER_CONDITION");
        Map<String, Builder> indexes = new LinkedHashMap<>();
        while (rows.next()) {
            short type = rows.getShort("TYPE");
            if (!rows.wasNull() && type == DatabaseMetaData.tableIndexStatistic) {
                continue;
            }
            String name = rows.getString("INDEX_NAME");
            if (name == null || name.isEmpty()) {
                throw invalid();
            }
            boolean nonUnique = rows.getBoolean("NON_UNIQUE");
            if (rows.wasNull()) {
                throw invalid();
            }
            int ordinal = rows.getInt("ORDINAL_POSITION");
            if (rows.wasNull() || ordinal < 1) {
                throw invalid();
            }
            String column = rows.getString("COLUMN_NAME");
            String order = rows.getString("ASC_OR_DESC");
            String filter = filterColumn ? rows.getString("FILTER_CONDITION") : null;
            indexes.computeIfAbsent(name, Builder::new).add(nonUnique, ordinal, column, order, filter);
        }
        List<IndexDescription> result = new ArrayList<>(indexes.size());
        for (Builder builder : indexes.values()) {
            result.add(builder.build());
        }
        return List.copyOf(result);
    }

    /**
     * 判断是否具有列。
     */
    private static boolean hasColumn(ResultSet rows, String label) throws SQLException {
        ResultSetMetaData metadata = rows.getMetaData();
        if (metadata == null) {
            return true;
        }
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            if (label.equalsIgnoreCase(metadata.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException invalid() {
        return new SQLException("Invalid JDBC index metadata");
    }

    private static final class Builder {

        private final String name;

        private Boolean nonUnique;

        private String filter;

        private final TreeMap<Integer, IndexFieldMeta> keys = new TreeMap<>();

        /**
         * 初始化 {@code Builder}。
         */
        private Builder(String name) {
            this.name = name;
        }

        /**
         * 将当前值加入集合或累计统计。
         */
        private void add(boolean rowNonUnique, int ordinal, String column, String order,
                String rowFilter) throws SQLException {
            if (nonUnique == null) {
                nonUnique = rowNonUnique;
            } else if (nonUnique != rowNonUnique) {
                throw invalid();
            }
            String condition = rowFilter == null || rowFilter.isBlank() ? null : rowFilter;
            if (condition != null) {
                if (filter != null && !Objects.equals(filter, condition)) {
                    throw invalid();
                }
                filter = condition;
            }
            Boolean ascending = "A".equalsIgnoreCase(order) ? Boolean.TRUE : "D".equalsIgnoreCase(
                    order) ? Boolean.FALSE : null;
            if (keys.putIfAbsent(ordinal, new IndexFieldMeta(column, ordinal, ascending)) != null) {
                throw invalid();
            }
        }

        /**
         * 返回当前构建。
         */
        private IndexDescription build() throws SQLException {
            if (keys.lastKey() != keys.size()) {
                throw invalid();
            }
            return new IndexDescription(name, nonUnique ? TableIndexEnum.NORMAL : TableIndexEnum.UNIQUE,
                    List.copyOf(keys.values()), filter);
        }
    }
}

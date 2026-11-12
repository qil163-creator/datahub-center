package cn.iocoder.yudao.framework.quality.core.profile;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 单遍聚合 SQL 及其结果列布局：第 1 列为抽样行数，之后按列顺序、按 {@link ProfileColumnKind#metrics()} 依次排列。
 *
 * <p>结果一律按位置读取，不依赖别名大小写折叠（Oracle/达梦大写、Hive 小写）。</p>
 *
 * @param sql     可直接执行的聚合 SQL
 * @param columns 探查列，顺序与 SQL 中的表达式一致
 * @author Li
 */
public record ProfileAggregatePlan(String sql, List<ProfileColumn> columns) {

    /**
     * 返回结果集的期望列数。
     */
    public int expectedColumnCount() {
        return 1 + columns.stream().mapToInt(column -> column.kind().metrics().size()).sum();
    }

    /**
     * 从已定位到首行的结果集读取统计；调用方负责执行、超时与关闭。
     */
    public ProfileAggregateResult read(ResultSet rs) throws SQLException {
        if (rs.getMetaData().getColumnCount() != expectedColumnCount()) {
            throw new SQLException("Profile aggregate result shape mismatch");
        }
        long rows = rs.getLong(1);
        int index = 2;
        List<ProfileColumnStats> stats = new ArrayList<>(columns.size());
        for (ProfileColumn column : columns) {
            long nonNull = 0;
            Long distinct = null;
            Long minLength = null;
            Long maxLength = null;
            String min = null;
            String max = null;
            BigDecimal avgLength = null;
            BigDecimal avgValue = null;
            for (ProfileMetric metric : column.kind().metrics()) {
                switch (metric) {
                    case NON_NULL -> nonNull = rs.getLong(index);
                    case DISTINCT -> distinct = readLong(rs, index);
                    case MIN -> min = ProfileSqlGenerator.truncate(rs.getString(index));
                    case MAX -> max = ProfileSqlGenerator.truncate(rs.getString(index));
                    case AVG_LENGTH -> avgLength = readDecimal(rs, index);
                    case MIN_LENGTH -> minLength = readLong(rs, index);
                    case MAX_LENGTH -> maxLength = readLong(rs, index);
                    case AVG_VALUE -> avgValue = readDecimal(rs, index);
                    default -> throw new IllegalStateException("Unknown profile metric " + metric);
                }
                index++;
            }
            stats.add(new ProfileColumnStats(column, nonNull, distinct, min, max, avgLength, minLength, maxLength,
                    avgValue));
        }
        return new ProfileAggregateResult(rows, stats);
    }

    /**
     * 读取可空整数统计。
     */
    private static Long readLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    /**
     * 以文本读取十进制统计，避免驱动把 numeric/double 先转成浮点；NaN、Infinity 等不可表示值记为 null。
     */
    private static BigDecimal readDecimal(ResultSet rs, int index) throws SQLException {
        String text = rs.getString(index);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 单遍聚合结果。
     *
     * @param sampledRows 实际参与统计的抽样行数
     * @param columns     各列统计
     */
    public record ProfileAggregateResult(long sampledRows, List<ProfileColumnStats> columns) {

    }

}

package cn.iocoder.yudao.framework.quality.core.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 单列 Top-N 取值分布查询：结果第 1 列为取值，第 2 列为出现次数，已按次数倒序；不含 NULL。
 *
 * @param column 探查列
 * @param sql    可直接执行的查询 SQL（已按产品加行数上限）
 * @param topN   最多读取的行数
 * @author Li
 */
public record ProfileTopValuesQuery(ProfileColumn column, String sql, int topN) {

    /**
     * 读取至多 topN 行取值分布；取值文本截断至 {@link ProfileSqlGenerator#MAX_VALUE_LENGTH} 个字符。
     */
    public List<ValueCount> read(ResultSet rs) throws SQLException {
        List<ValueCount> values = new ArrayList<>();
        while (values.size() < topN && rs.next()) {
            values.add(new ValueCount(ProfileSqlGenerator.truncate(rs.getString(1)), rs.getLong(2)));
        }
        return values;
    }

    /**
     * 取值与出现次数。
     *
     * @param value 取值文本（截断后）
     * @param count 出现次数
     */
    public record ValueCount(String value, long count) {

    }

}

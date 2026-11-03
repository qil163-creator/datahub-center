package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import org.apache.commons.lang3.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import javax.sql.DataSource;
import java.util.List;

/**
 * MySQL协议兼容的OLAP数据库读取基础实现。
 */
public abstract class MysqlCompatibleOlapDatabaseOperator extends MysqlDatabaseOperator {

    /**
     * 初始化 {@code MysqlCompatibleOlapDatabaseOperator}及其父类状态。
     */
    public MysqlCompatibleOlapDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schemaName, String tableName, List<String> fields,
            List<String> orders) {
        String sql = String.format("select `%s` from `%s`.`%s` order by `%s` asc limit %s",
                StringUtils.join(fields, "`,`"),
                schemaName, tableName, StringUtils.join(orders, "`,`"), Long.MAX_VALUE);
        return this.selectTableData(sql, Integer.MIN_VALUE);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String sql, List<String> fields, List<String> orders) {
        sql = String.format("select `%s` from (%s) t order by `%s` asc limit %s",
                StringUtils.join(fields, "`,`"),
                sql, StringUtils.join(orders, "`,`"), Long.MAX_VALUE);
        return this.selectTableData(sql, Integer.MIN_VALUE);
    }
}

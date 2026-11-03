package cn.iocoder.yudao.framework.dbswitch.dbwriter.db2;

import lombok.extern.slf4j.Slf4j;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata;

import javax.sql.DataSource;
import java.util.List;

/**
 * DB2数据库写入实现类
 *
 * @author jrl
 */
@Slf4j
public class DB2WriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    /**
     * 初始化 {@code DB2WriterImpl}及其父类状态。
     */
    public DB2WriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "DB2";
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : Db2CursorMetadata.fields(fields))
                + " FROM " + Db2CursorMetadata.relation(schema, table) + " WHERE 1=2";
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        return "INSERT INTO " + Db2CursorMetadata.relation(schemaName, tableName) + " ("
                + Db2CursorMetadata.fields(fields) + ") VALUES (" + String.join(",",
                java.util.Collections.nCopies(fields.size(), "?")) + ")";
    }

}

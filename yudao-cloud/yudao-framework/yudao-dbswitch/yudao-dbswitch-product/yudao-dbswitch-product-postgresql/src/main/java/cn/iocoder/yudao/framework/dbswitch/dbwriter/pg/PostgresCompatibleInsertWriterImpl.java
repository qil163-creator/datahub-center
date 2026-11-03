package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;

/**
 * PostgreSQL 协议产品共用的 INSERT 写入基础实现。
 * <p>
 * 只负责双引号标识符与 INSERT 语句；产品名、能力声明和其他行为由各产品自己的子类提供。
 */
public abstract class PostgresCompatibleInsertWriterImpl extends AbstractDatabaseWriter implements IDatabaseWriter {

    /**
     * 初始化 {@code PostgresCompatibleInsertWriterImpl}及其父类状态。
     */
    protected PostgresCompatibleInsertWriterImpl(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 生成读取表元数据的 SQL。
     */
    @Override
    protected String selectTableMetaDataSqlString(String schema, String table, List<String> fields) {
        return "SELECT " + (fields == null || fields.isEmpty() ? "*" : PostgresCompatibleIdentifiers.fields(fields))
                + " FROM " + PostgresCompatibleIdentifiers.table(schema, table) + " WHERE 1=2";
    }

    /**
     * 生成目标表的 INSERT 语句。
     */
    @Override
    protected String insertSql(List<String> fields) {
        return plainInsertSql(schemaName, tableName, fields);
    }

    /** 普通表的计划与实际写入共用同一模板生成器。 */
    protected static String plainInsertSql(String schema, String table, List<String> fields) {
        return "INSERT INTO " + PostgresCompatibleIdentifiers.table(schema, table)
                + " (" + PostgresCompatibleIdentifiers.fields(fields) + ") VALUES ("
                + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }
}

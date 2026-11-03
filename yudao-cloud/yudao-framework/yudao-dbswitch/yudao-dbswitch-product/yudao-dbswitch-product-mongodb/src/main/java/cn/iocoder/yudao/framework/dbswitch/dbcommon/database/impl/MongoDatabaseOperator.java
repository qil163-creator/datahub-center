package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 显式原生集合操作；不允许无界 shell SQL 或隐式删除集合。 */
public class MongoDatabaseOperator extends AbstractDatabaseOperator {

    private int fetchSize = 500;

    /**
     * 初始化 {@code MongoDatabaseOperator}及其父类状态。
     */
    public MongoDatabaseOperator(DataSource source) {
        super(source);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.MONGODB;
    }

    /**
     * 获取读取(fetch)数据的批次大小。
     */
    @Override
    public int getFetchSize() {
        return fetchSize;
    }

    /**
     * 设置读取(fetch)数据的批次大小。
     */
    @Override
    public void setFetchSize(int size) {
        if (size < 1 || size > 10000) {
            throw new IllegalArgumentException("MongoDB fetch size must be between 1 and 10000");
        }
        fetchSize = size;
    }

    /**
     * 返回原生搬运路径使用的列定义。
     */
    @Override
    public List<ColumnDescription> nativeMigrationColumns(String schema, String table) {
        describeNativeTable(schema, table);
        return MongoMigrationFrame.columns();
    }

    /**
     * 原生索引及集合结构支持独立于关系型 SQL DDL。
     */
    @Override
    public boolean supportsNativeTableDefinition() {
        return true;
    }

    /**
     * 读取数据库原生表定义及其元数据证据。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition describeNativeTable(String schema,
            String table) {
        return MongoNativeStructure.describe(dataSource, schema, table, runtimeOptions.maxCacheBytes(),
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition definition,
            String schema, String table, Map<String, String> mapping, boolean replace) {
        return MongoNativeStructure.prepare(dataSource, definition, schema, table, mapping, replace,
                runtimeOptions.maxCacheBytes(), runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 校验目标原生定义并生成可复核的结构演进计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeSchemaEvolution(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition desired, String schema,
            String table) {
        return MongoNativeStructure.evolve(dataSource, desired, schema, table, runtimeOptions.maxCacheBytes(),
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        MongoNativeMetadata.identifier(schema);
        MongoNativeMetadata.identifier(table);
        return schema + "." + table;
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> orders) {
        return MongoCursorRows.open(dataSource, schema, table, fields, orders, fetchSize, runtimeOptions);
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return queryTableData(schema, table, fields, List.of());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String reference) {
        return queryTableData(reference, MongoMigrationFrame.FIELDS, List.of());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String reference, List<String> fields) {
        return queryTableData(reference, fields, List.of());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String reference, List<String> fields, List<String> orders) {
        if (reference == null) {
            throw new IllegalArgumentException("MongoDB requires a database.collection read handle");
        }
        String[] names = reference.split("[.]", -1);
        if (names.length != 2) {
            throw new IllegalArgumentException("MongoDB requires a database.collection read handle");
        }
        return queryTableData(names[0], names[1], fields, orders);
    }

    /** 固定且无快照的单类型水位游标；交付边界参见 MongoCursorReader。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return MongoCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        mutate(schema, table, false);
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        mutate(schema, table, true);
    }

    /**
     * 按当前变更定义修改目标对象或数据。
     */
    private void mutate(String schema, String table, boolean drop) {
        MongoNativeMetadata.identifier(schema);
        MongoNativeMetadata.identifier(table);
        boolean attempted = false;
        try (Connection connection = dataSource.getConnection()) {
            var before = MongoNativeMetadata.ordinaryCollection(connection, schema, table);
            var session = MongoNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                    runtimeOptions.queryTimeoutSeconds());
            Map<String, Object> command = new LinkedHashMap<>();
            command.put(drop ? "drop" : "delete", table);
            if (!drop) {
                command.put("deletes", List.of(Map.of("q", Map.of(), "limit", 0)));
                command.put("ordered", true);
            }
            command.put("writeConcern",
                    Map.of("w", "majority", "j", true, "wtimeout", 1000L * runtimeOptions.queryTimeoutSeconds()));
            byte[] payload = session.encode(
                    cn.iocoder.yudao.framework.dbswitch.common.util.SingletonObject.OBJECT_MAPPER.writeValueAsString(
                            command));
            attempted = true;
            var result = MongoReplies.success(session, session.command(schema, payload));
            MongoReplies.noWriteErrors(result);
            if (!drop) {
                var after = MongoNativeMetadata.ordinaryCollection(connection, schema, table);
                if (!before.equals(after)) {
                    throw new SQLException("MongoDB collection identity or options changed during truncate");
                }
            }
        } catch (Exception failure) {
            if (attempted) {
                throw new UnconfirmedWriteException();
            }
            throw new IllegalStateException("MongoDB collection mutation was not submitted");
        }
    }

    /**
     * 返回当前字段是否属于主键。
     */
    @Override
    public Boolean getPkExist(String schema, String table, Map<String, Object> values) throws SQLException {
        MongoNativeMetadata.identifier(schema);
        MongoNativeMetadata.identifier(table);
        if (values == null || !values.keySet().equals(java.util.Set.of("_id"))) {
            throw new IllegalArgumentException("MongoDB identity lookup requires only _id");
        }
        try (Connection connection = dataSource.getConnection()) {
            MongoNativeMetadata.ordinaryCollection(connection, schema, table);
            var session = MongoNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                    runtimeOptions.queryTimeoutSeconds());
            byte[] filter = session.document(Map.of("_id", MongoCellCodec.encode(session, values.get("_id"), true)));
            var command = new LinkedHashMap<String, byte[]>();
            command.put("count", MongoCellCodec.encode(session, table, false));
            command.put("query", MongoMigrationFrame.wrapDocument(filter));
            command.put("limit", MongoCellCodec.encode(session, 1, false));
            command.put("maxTimeMS",
                    MongoCellCodec.encode(session, 1000L * runtimeOptions.queryTimeoutSeconds(), false));
            return MongoReplies.integer(
                    MongoReplies.success(session, session.command(schema, session.document(command))).path("n")) == 1;
        }
    }
}

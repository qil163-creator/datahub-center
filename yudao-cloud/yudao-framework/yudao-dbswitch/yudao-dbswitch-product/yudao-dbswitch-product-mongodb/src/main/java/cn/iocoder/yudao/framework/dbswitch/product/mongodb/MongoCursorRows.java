package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MissingCellValue;
import cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;

import javax.sql.DataSource;
import javax.sql.rowset.RowSetMetaDataImpl;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.HashSet;
import java.util.List;

/** 通过现有 DB Switch 读取器契约提供的延迟、有界 BSON 游标。 */
public final class MongoCursorRows {

    /**
     * 初始化 {@code MongoCursorRows}。
     */
    private MongoCursorRows() {
    }

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    public static StatementResultSet open(DataSource source, String schema, String table, List<String> requested,
            List<String> orders, int fetchSize, DbSwitchOptions options) {
        MongoNativeMetadata.identifier(schema);
        MongoNativeMetadata.identifier(table);
        List<String> fields = requested == null || requested.isEmpty() ? MongoMigrationFrame.FIELDS : List.copyOf(
                requested);
        boolean frame = fields.contains(MongoMigrationFrame.DOCUMENT);
        if (frame) {
            MongoMigrationFrame.requireFields(fields);
        } else {
            fields.forEach(MongoNativeSession::field);
        }
        if (new HashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("MongoDB projection requires distinct fields");
        }
        if (orders != null) {
            orders.forEach(MongoNativeSession::field);
        }
        Connection connection = null;
        try {
            connection = source.getConnection();
            var info = MongoNativeMetadata.ordinaryCollection(connection, schema, table);
            if (info.getJSONObject("options").containsKey("collation")
                    && !"simple".equals(info.getJSONObject("options").getJSONObject("collation").getStr("locale"))) {
                throw new SQLException("MongoDB native matching requires simple collation");
            }
            var session = MongoNativeSession.open(connection, options.maxCacheBytes(), options.queryTimeoutSeconds());
            var cursor = session.find(schema, table, frame ? null : fields, orders, fetchSize);
            return new Rows(connection, session, cursor, fields, frame, fetchSize).result();
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
            throw new IllegalStateException("MongoDB native read could not be opened");
        }
    }

    private static final class Rows {

        private final Connection connection;

        private final MongoNativeSession session;

        private final MongoNativeSession.Cursor cursor;

        private final List<String> fields;

        private final boolean frame;

        private final int fetchSize;

        private final RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();

        private boolean closed;

        private boolean exhausted;

        private Object[] row;

        private int ordinal;

        private Statement statement;

        private ResultSet result;

        /**
         * 初始化 {@code Rows}。
         */
        Rows(Connection connection, MongoNativeSession session, MongoNativeSession.Cursor cursor,
                List<String> fields, boolean frame, int fetchSize) throws SQLException {
            this.connection = connection;
            this.session = session;
            this.cursor = cursor;
            this.fields = fields;
            this.frame = frame;
            this.fetchSize = fetchSize;
            metadata.setColumnCount(fields.size());
            for (int i = 0; i < fields.size(); i++) {
                metadata.setColumnName(i + 1, fields.get(i));
                metadata.setColumnLabel(i + 1, fields.get(i));
                metadata.setColumnType(i + 1, Types.OTHER);
                metadata.setColumnTypeName(i + 1, "BSON_VALUE");
                metadata.setNullable(i + 1, ResultSetMetaData.columnNoNulls);
            }
        }

        /**
         * 推进游标并返回下一条数据或后续读取状态。
         */
        private boolean next() throws SQLException {
            if (closed) {
                throw new SQLException("MongoDB result is closed");
            }
            if (exhausted) {
                return false;
            }
            try {
                byte[] document = cursor.next();
                if (document == null) {
                    row = null;
                    exhausted = true;
                    cursor.close();
                    return false;
                }
                if (frame) {
                    row = MongoMigrationFrame.detach(session, document);
                } else {
                    var observed = session.cells(document);
                    row = new Object[fields.size()];
                    for (int i = 0; i < row.length; i++) {
                        String field = fields.get(i);
                        byte[] value = observed.get(field);
                        row[i] = value == null ? MissingCellValue.INSTANCE : MongoCellCodec.detach(session, value,
                                field.equals("_id"));
                    }
                }
                ordinal++;
                return true;
            } catch (SQLException | RuntimeException failure) {
                try {
                    close();
                } catch (SQLException ignored) {
                }
                throw new SQLException("MongoDB row could not be read without loss");
            }
        }

        /**
         * 返回值。
         */
        private Object value(Object selector) throws SQLException {
            if (closed || row == null) {
                throw new SQLException("MongoDB result has no current row");
            }
            int index = selector instanceof Integer number ? number : fields.indexOf(selector) + 1;
            if (index < 1 || index > fields.size()) {
                throw new SQLException("Unknown MongoDB result column");
            }
            return row[index - 1];
        }

        /**
         * 关闭游标读取状态并释放其持有的结果集及相关资源。
         */
        private void close() throws SQLException {
            if (!closed) {
                cursor.close();
                closed = true;
                row = null;
            }
        }

        /**
         * 返回结果。
         */
        StatementResultSet result() {
            statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "close", "cancel" -> {
                            close();
                            yield null;
                        }
                        case "isClosed" -> closed;
                        case "getConnection" -> connection;
                        case "getResultSet" -> result;
                        case "getFetchSize" -> fetchSize;
                        case "toString" -> "MongoDB native cursor statement";
                        default -> throw new SQLFeatureNotSupportedException(
                                "MongoDB cursor statement operation is unavailable");
                    });
            result = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> next();
                        case "close" -> {
                            close();
                            yield null;
                        }
                        case "isClosed" -> closed;
                        case "getObject" -> {
                            Object value = value(args[0]);
                            if (args.length == 2 && args[1] instanceof Class<?> type && !type.isInstance(value)) {
                                throw new SQLException("MongoDB result requires its native owned value type");
                            }
                            yield value;
                        }
                        case "getString" -> {
                            Object value = value(args[0]);
                            if (!(value instanceof NativeCellValue nativeValue)) {
                                throw new SQLException("Missing MongoDB field has no textual value");
                            }
                            yield session.canonicalJson(nativeValue.encoded());
                        }
                        // 绝不根据字段缺失或显式编码的 BSON null 凭空生成 SQL NULL。
                        case "wasNull" -> false;
                        case "getMetaData" -> metadata;
                        case "getStatement" -> statement;
                        case "getFetchSize" -> fetchSize;
                        case "getRow" -> ordinal;
                        case "findColumn" -> {
                            int index = fields.indexOf(args[0]) + 1;
                            if (index == 0) {
                                throw new SQLException("Unknown MongoDB column");
                            }
                            yield index;
                        }
                        case "getType" -> ResultSet.TYPE_FORWARD_ONLY;
                        case "getConcurrency" -> ResultSet.CONCUR_READ_ONLY;
                        case "toString" -> "MongoDB native cursor result";
                        default -> throw new SQLFeatureNotSupportedException(
                                "MongoDB native value cannot be implicitly converted");
                    });
            var owner = new StatementResultSet() {
                private boolean ended;

                /**
                 * 关闭游标读取状态并释放其持有的结果集及相关资源。
                 */
                @Override
                public void close() {
                    if (ended) {
                        return;
                    }
                    boolean failed = false;
                    try {
                        Rows.this.close();
                    } catch (SQLException failure) {
                        failed = true;
                    }
                    try {
                        connection.close();
                    } catch (SQLException failure) {
                        failed = true;
                    }
                    if (failed) {
                        throw new IllegalStateException("MongoDB native read cleanup is unconfirmed");
                    }
                    ended = true;
                }
            };
            owner.setConnection(connection);
            owner.setStatement(statement);
            owner.setResultset(result);
            return owner;
        }
    }
}

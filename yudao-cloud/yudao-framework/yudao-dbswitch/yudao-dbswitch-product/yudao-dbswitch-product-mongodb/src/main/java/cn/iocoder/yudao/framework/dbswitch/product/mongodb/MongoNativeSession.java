package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 连接持有的原生 BSON 传输。厂商类和凭据绝不离开此适配器。 */
public final class MongoNativeSession {

    private final Connection connection;

    private final Object physical;

    private final ClassLoader loader;

    private final Class<?> bsonType;

    private final Class<?> documentType;

    private final Class<?> valueType;

    private final Class<?> rawType;

    private final Class<?> databaseType;

    private final Class<?> collectionType;

    private final Class<?> findType;

    private final Class<?> cursorType;

    private final Method getDatabase;

    private final Field nativeDatabase;

    private final Object codec;

    private final Class<?> codecType;

    private final long limit;

    private final int timeoutSeconds;

    /**
     * 打开MongoDB原生会话并准备后续访问。
     */
    public static MongoNativeSession open(Connection connection, long limit, int timeoutSeconds) throws SQLException {
        return new MongoNativeSession(connection, limit, timeoutSeconds);
    }

    /**
     * 校验构造参数并初始化 {@code MongoNativeSession}。
     */
    private MongoNativeSession(Connection connection, long limit, int timeoutSeconds) throws SQLException {
        if (connection == null || limit < 1 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("MongoDB native bounds are required");
        }
        this.connection = connection;
        this.limit = limit;
        this.timeoutSeconds = timeoutSeconds;
        try {
            physical = DriverNativeAccess.unwrap(connection, "com.gitee.jdbc.mongodb.MongoConnection");
            loader = physical.getClass().getClassLoader();
            var bridge = Class.forName("com.gitee.jdbc.mongodb.MongoConnection", false, loader);
            var wrappedDatabase = Class.forName("com.gitee.jdbc.mongodb.wrappers.WrappedMongoDatabase", false, loader);
            databaseType = Class.forName("com.mongodb.client.MongoDatabase", false, loader);
            collectionType = Class.forName("com.mongodb.client.MongoCollection", false, loader);
            bsonType = Class.forName("org.bson.conversions.Bson", false, loader);
            documentType = Class.forName("org.bson.BsonDocument", false, loader);
            valueType = Class.forName("org.bson.BsonValue", false, loader);
            rawType = Class.forName("org.bson.RawBsonDocument", false, loader);
            findType = Class.forName("com.mongodb.client.FindIterable", false, loader);
            cursorType = Class.forName("com.mongodb.client.MongoCursor", false, loader);
            codecType = Class.forName("org.bson.codecs.Codec", false, loader);
            codec = Class.forName("org.bson.codecs.BsonDocumentCodec", false, loader).getConstructor().newInstance();
            getDatabase = bridge.getMethod("getDatabase", String.class);
            // 此包装器不暴露公开的原生 runCommand。先校验一个已知桥接结构，
            // 再仅调用标准 SDK API；不求值 JavaScript，也不重复创建客户端。
            nativeDatabase = wrappedDatabase.getDeclaredField("mongoDatabase");
            if (nativeDatabase.getType() != databaseType || !nativeDatabase.trySetAccessible()) {
                throw new ReflectiveOperationException();
            }
            databaseType.getMethod("runCommand", bsonType, Class.class);
            rawType.getConstructor(byte[].class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new SQLException("MongoDB native bridge contract is unavailable");
        }
    }

    /** 组件生成的规范扩展 JSON 由固定的 BSON 编解码器解码，绝不交给 shell。 */
    public byte[] encode(String canonicalJson) throws SQLException {
        if (canonicalJson == null || 2L * canonicalJson.length() > limit) {
            throw new SQLException("MongoDB command exceeds its value boundary");
        }
        return within(() -> bytes(rawType.getMethod("parse", String.class).invoke(null, canonicalJson)));
    }

    /**
     * 返回当前命令。
     */
    public byte[] command(String schema, byte[] command) throws SQLException {
        MongoNativeMetadata.identifier(schema);
        bounded(command);
        return withinRemote(() -> {
            Object database = database(schema);
            Object reply = databaseType.getMethod("runCommand", bsonType, Class.class)
                    .invoke(database, rawType.getConstructor(byte[].class).newInstance((Object) command), rawType);
            return bytes(reply);
        });
    }

    /** 用于有界元数据及回执的规范扩展 JSON，绝不是数据搬移表示。 */
    public String canonicalJson(byte[] document) throws SQLException {
        return json(document, "EXTENDED");
    }

    /** 仅供现有元数据使用的兼容表示；原生传输始终使用 BSON 字节。 */
    public String metadataJson(byte[] document) throws SQLException {
        return json(document, "RELAXED");
    }

    /**
     * 按当前值域约定解析或转换JSON 值。
     */
    private String json(byte[] document, String mode) throws SQLException {
        bounded(document);
        return within(() -> {
            Class<?> settingsType = Class.forName("org.bson.json.JsonWriterSettings", false, loader);
            Class<?> builderType = Class.forName("org.bson.json.JsonWriterSettings$Builder", false, loader);
            Class<?> modeType = Class.forName("org.bson.json.JsonMode", false, loader);
            Object builder = settingsType.getMethod("builder").invoke(null);
            builderType.getMethod("outputMode", modeType).invoke(builder, modeType.getField(mode).get(null));
            Object settings = builderType.getMethod("build").invoke(builder);
            Object raw = rawType.getConstructor(byte[].class).newInstance((Object) document);
            String json = (String) rawType.getMethod("toJson", settingsType).invoke(raw, settings);
            if (2L * json.length() > limit) {
                throw new SQLException("MongoDB metadata representation exceeds its boundary");
            }
            return json;
        });
    }

    /**
     * 在受控原生会话中读取并转换 BSON Decimal128 值。
     */
    public java.math.BigDecimal decimal128(byte[] cell) throws SQLException {
        bounded(cell);
        return within(() -> {
            Object document = rawType.getConstructor(byte[].class).newInstance((Object) cell);
            Object value = ((Map<?, ?>) document).get("v");
            Object decimal = valueType.getMethod("asDecimal128").invoke(value);
            Object decimal128 = Class.forName("org.bson.BsonDecimal128", false, loader).getMethod("getValue")
                    .invoke(decimal);
            return (java.math.BigDecimal) Class.forName("org.bson.types.Decimal128", false, loader)
                    .getMethod("bigDecimalValue").invoke(decimal128);
        });
    }

    /** 单元格是包含单个名为 v 的成员的 BSON 文档，在类加载器之间保留原生类型和字节。 */
    public Map<String, byte[]> cells(byte[] document) throws SQLException {
        bounded(document);
        return within(() -> {
            Object decoded = rawType.getConstructor(byte[].class).newInstance((Object) document);
            Map<String, byte[]> result = new LinkedHashMap<>();
            for (var entry : ((Map<?, ?>) decoded).entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new SQLException("MongoDB field name is invalid");
                }
                Object cell = documentType.getConstructor(String.class, valueType).newInstance("v", entry.getValue());
                result.put(name, bytes(rawType.getConstructor(Object.class, codecType).newInstance(cell, codec)));
            }
            return result;
        });
    }

    /** 组装单元格时，不扩宽数值类型，也不将驱动对象序列化为 JSON 字符串。 */
    public byte[] document(Map<String, byte[]> cells) throws SQLException {
        return within(() -> {
            Object document = documentType.getConstructor().newInstance();
            long total = 5;
            for (var entry : cells.entrySet()) {
                if (entry.getKey() == null || entry.getKey().indexOf('\0') >= 0 || entry.getKey().length() > 255) {
                    throw new SQLException("MongoDB BSON member name is invalid");
                }
                bounded(entry.getValue());
                total = Math.addExact(total, entry.getValue().length + 2L * entry.getKey().length());
                if (total > limit) {
                    throw new SQLException("MongoDB document exceeds its byte boundary");
                }
                Object value = rawType.getConstructor(byte[].class).newInstance((Object) entry.getValue());
                Map<?, ?> map = (Map<?, ?>) value;
                if (map.size() != 1 || !map.containsKey("v")) {
                    throw new SQLException("MongoDB cell envelope is invalid");
                }
                documentType.getMethod("put", String.class, valueType).invoke(document, entry.getKey(), map.get("v"));
            }
            return bytes(rawType.getConstructor(Object.class, codecType).newInstance(document, codec));
        });
    }

    /**
     * 按当前值域约定解析或转换数组。
     */
    public byte[] array(List<byte[]> cells) throws SQLException {
        return within(() -> {
            List<Object> values = new java.util.ArrayList<>();
            long total = 5;
            for (byte[] cell : cells) {
                bounded(cell);
                total = Math.addExact(total, cell.length + 16L);
                if (total > limit) {
                    throw new SQLException("MongoDB array exceeds its byte boundary");
                }
                var decoded = (Map<?, ?>) rawType.getConstructor(byte[].class).newInstance((Object) cell);
                if (decoded.size() != 1 || !decoded.containsKey("v")) {
                    throw new SQLException("MongoDB array cell envelope is invalid");
                }
                values.add(decoded.get("v"));
            }
            Object array = Class.forName("org.bson.BsonArray", false, loader).getConstructor(List.class)
                    .newInstance(values);
            Object envelope = documentType.getConstructor(String.class, valueType).newInstance("v", array);
            return bytes(rawType.getConstructor(Object.class, codecType).newInstance(envelope, codec));
        });
    }

    /**
     * 返回当前查找。
     */
    public Cursor find(String schema, String table, List<String> fields, List<String> orders,
            int fetchSize) throws SQLException {
        var sort = new LinkedHashMap<String, Integer>();
        if (orders != null) {
            orders.forEach(name -> sort.put(name, 1));
        }
        return find(schema, table, null, fields, sort, fetchSize);
    }

    /** 有界 find，带显式编码的过滤文档和指定方向的排序；此处不解释或重写二者。 */
    public Cursor find(String schema, String table, byte[] filter, List<String> fields, Map<String, Integer> orders,
            int fetchSize) throws SQLException {
        MongoNativeMetadata.identifier(schema);
        MongoNativeMetadata.identifier(table);
        if (fetchSize < 1 || fetchSize > 10000) {
            throw new IllegalArgumentException("MongoDB fetch size is outside the bounded range");
        }
        if (fields != null) {
            fields.forEach(MongoNativeSession::field);
        }
        if (orders != null) {
            orders.keySet().forEach(MongoNativeSession::field);
        }
        if (orders != null) {
            orders.values().forEach(direction -> {
                if (!Integer.valueOf(1).equals(direction) && !Integer.valueOf(-1).equals(direction)) {
                    throw new IllegalArgumentException("MongoDB sort requires an explicit direction");
                }
            });
        }
        if (filter != null) {
            bounded(filter);
        }
        return withinRemote(() -> {
            Object collection = databaseType.getMethod("getCollection", String.class, Class.class)
                    .invoke(database(schema), table, rawType);
            Object iterable = collectionType.getMethod("find").invoke(collection);
            if (filter != null) {
                findType.getMethod("filter", bsonType)
                        .invoke(iterable, rawType.getConstructor(byte[].class).newInstance((Object) filter));
            }
            if (fields != null && !fields.isEmpty()) {
                Map<String, Integer> projection = new LinkedHashMap<>();
                fields.forEach(name -> projection.put(name, 1));
                if (!fields.contains("_id")) {
                    projection.put("_id", 0);
                }
                Object bson = documentType.getMethod("parse", String.class).invoke(null,
                        cn.iocoder.yudao.framework.dbswitch.common.util.SingletonObject.OBJECT_MAPPER.writeValueAsString(
                                projection));
                findType.getMethod("projection", bsonType).invoke(iterable, bson);
            }
            if (orders != null && !orders.isEmpty()) {
                Object bson = documentType.getMethod("parse", String.class).invoke(null,
                        cn.iocoder.yudao.framework.dbswitch.common.util.SingletonObject.OBJECT_MAPPER.writeValueAsString(
                                orders));
                findType.getMethod("sort", bsonType).invoke(iterable, bson);
            }
            findType.getMethod("batchSize", int.class).invoke(iterable, fetchSize);
            findType.getMethod("maxTime", long.class, TimeUnit.class)
                    .invoke(iterable, (long) timeoutSeconds, TimeUnit.SECONDS);
            Object nativeCursor = Class.forName("com.mongodb.client.MongoIterable", false, loader).getMethod("iterator")
                    .invoke(iterable);
            return new Cursor(nativeCursor);
        });
    }

    public final class Cursor implements AutoCloseable {

        private final Object cursor;

        private boolean closed;

        /**
         * 初始化 {@code Cursor}。
         */
        private Cursor(Object cursor) {
            this.cursor = cursor;
        }

        /**
         * 推进游标并返回下一条数据或后续读取状态。
         */
        public byte[] next() throws SQLException {
            if (closed) {
                throw new SQLException("MongoDB cursor is closed");
            }
            return withinRemote(() -> (boolean) cursorType.getMethod("hasNext").invoke(cursor)
                    ? bytes(cursorType.getMethod("next").invoke(cursor)) : null);
        }

        /**
         * 关闭 {@code MongoNativeSession} 当前持有的资源。
         */
        @Override
        public void close() throws SQLException {
            if (!closed) {
                withinRemote(() -> {
                    cursorType.getMethod("close").invoke(cursor);
                    return null;
                });
                closed = true;
            }
        }
    }

    /**
     * 返回字段。
     */
    public static void field(String name) {
        if (name == null || !name.matches("_id|[A-Za-z][A-Za-z0-9_]{0,254}")) {
            throw new IllegalArgumentException("MongoDB requires an ordinary top-level field");
        }
    }

    /**
     * 返回当前数据库。
     */
    private Object database(String schema) throws ReflectiveOperationException {
        return nativeDatabase.get(getDatabase.invoke(physical, schema));
    }

    /**
     * 返回字节数组。
     */
    private byte[] bytes(Object raw) throws ReflectiveOperationException, SQLException {
        Object buffer = rawType.getMethod("getByteBuffer").invoke(raw);
        ByteBuffer bytes = (ByteBuffer) Class.forName("org.bson.ByteBuf", false, loader).getMethod("asNIO")
                .invoke(buffer);
        if (bytes.remaining() > limit) {
            throw new SQLException("MongoDB response exceeds its byte boundary");
        }
        byte[] result = new byte[bytes.remaining()];
        bytes.get(result);
        return result;
    }

    /**
     * 校验当前输入未超过约定的大小或数量上限。
     */
    private void bounded(byte[] bytes) throws SQLException {
        if (bytes == null || bytes.length < 5 || bytes.length > limit) {
            throw new SQLException("MongoDB BSON value exceeds its boundary");
        }
    }

    /**
     * 在连接可用及当前期限约束下调用原生操作。
     */
    private <T> T within(Operation<T> operation) throws SQLException {
        if (connection.isClosed()) {
            throw new SQLException("MongoDB connection is closed");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new SQLException("MongoDB native operation interrupted");
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            return operation.run();
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof InterruptedException) {
                thread.interrupt();
            }
            throw new SQLException("MongoDB native operation did not return a usable result");
        } catch (SQLException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw new SQLException("MongoDB native bridge operation failed");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * 在远端访问期限和受控原生上下文内调用操作。
     */
    private <T> T withinRemote(Operation<T> operation) throws SQLException {
        return DriverNativeAccess.invoke(connection, 1000L * timeoutSeconds, () -> within(operation));
    }

    @FunctionalInterface
    private interface Operation<T> {

        /**
         * 在当前连接及受控原生运行时上下文中调用 MongoDB 工作回调。
         */
        T run() throws Exception;
    }
}

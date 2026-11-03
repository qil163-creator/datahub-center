package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.*;

/** 通过配置的 shell JDBC 驱动获取有界原生 MongoDB 元数据。
 * 驱动专有类不泄漏到隔离类加载器之外。不改变 catalog、凭据或数据。
 * 此基础能力不代表支持 SQL、质量规则、事务或迁移。
 */
public final class MongoNativeMetadata {

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]{0,62}";

    /**
     * 初始化 {@code MongoNativeMetadata}。
     */
    private MongoNativeMetadata() {
    }

    /**
     * 返回当前数据库。
     */
    public static String database(String schema) {
        identifier(schema);
        return "client.getDatabase('" + schema + "')";
    }

    /**
     * 返回当前集合。
     */
    public static String collection(String schema, String table) {
        identifier(table);
        return database(schema) + ".getCollection('" + table + "')";
    }

    /**
     * 检查或转换当前数据库标识符。
     */
    public static void identifier(String identifier) {
        if (identifier == null || !identifier.matches(NAME)) {
            throw new IllegalArgumentException("MongoDB requires an ordinary physical identifier");
        }
    }

    /**
     * 检查 MongoDB 目标是当前路径支持的普通集合。
     */
    public static JSONObject ordinaryCollection(Connection connection, String schema,
            String table) throws SQLException {
        identifier(schema);
        identifier(table);
        JSONObject response = command(connection, schema, "{listCollections:1,filter:{name:'" + table
                + "'},nameOnly:false,cursor:{batchSize:2},maxTimeMS:5000}");
        JSONObject cursor = response.getJSONObject("cursor");
        if (cursor == null || !"0".equals(cursor.getStr("id"))) {
            throw unsupported("MongoDB collection cursor is incomplete");
        }
        JSONArray batch = cursor.getJSONArray("firstBatch");
        if (batch == null || batch.size() != 1) {
            throw unsupported("MongoDB collection is absent or ambiguous");
        }
        JSONObject info = batch.getJSONObject(0);
        JSONObject options = info.getJSONObject("options");
        if (!table.equals(info.getStr("name")) || !"collection".equals(info.getStr("type")) || options == null
                || options.containsKey("timeseries") || options.containsKey("viewOn")
                || options.containsKey("encryptedFields") || Boolean.TRUE.equals(options.getBool("capped"))) {
            throw unsupported("MongoDB ordinary physical collection is required");
        }
        return info;
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    public static String fingerprint(Connection connection, String reference) throws SQLException {
        if (connection == null || reference == null || !reference.matches(NAME + "\\." + NAME)) {
            throw unsupported("MongoDB requires an explicit database and collection");
        }
        String[] parts = reference.split("\\.");
        if (Set.of("admin", "config", "local").contains(parts[0])) {
            throw unsupported("MongoDB user database is required");
        }
        List<String> first = identity(connection, parts[0], parts[1]);
        if (!first.equals(identity(connection, parts[0], parts[1]))) {
            throw unsupported("MongoDB identity changed during capture");
        }
        return DigestUtil.sha256Hex(String.join("\0", first));
    }

    /**
     * 校验并取得默认编号索引。
     */
    public static void requireDefaultIdIndex(Connection connection, String schema, String table) throws SQLException {
        identifier(table);
        // MongoDB 7.0 每个集合最多允许 64 个索引；读取完整的有界游标。
        JSONObject response = command(connection, schema,
                "{listIndexes:'" + table + "',cursor:{batchSize:65},maxTimeMS:5000}");
        JSONObject cursor = response.getJSONObject("cursor");
        JSONArray indexes = cursor == null ? null : cursor.getJSONArray("firstBatch");
        if (cursor == null || !"0".equals(cursor.getStr("id")) || indexes == null || indexes.size() != 1) {
            throw unsupported("MongoDB additional indexes require native DDL export");
        }
        JSONObject index = indexes.getJSONObject(0), keys = index.getJSONObject("key");
        if (!"_id_".equals(index.getStr("name")) || keys == null || keys.size() != 1
                || !Integer.valueOf(1).equals(keys.getInt("_id"))) {
            throw unsupported("MongoDB non-default index requires native DDL export");
        }
    }

    /**
     * 返回身份信息。
     */
    private static List<String> identity(Connection connection, String schema, String table) throws SQLException {
        var version = command(connection, "admin", "{buildInfo:1,maxTimeMS:5000}");
        if (!"7.0.43".equals(version.getStr("version")) || version.getJSONArray("modules") == null
                || !version.getJSONArray("modules").isEmpty()) {
            throw unsupported("MongoDB version or edition is not verified");
        }
        JSONObject auth = command(connection, "admin", "{connectionStatus:1,showPrivileges:true,maxTimeMS:5000}")
                .getJSONObject("authInfo");
        if (auth == null || auth.getJSONArray("authenticatedUsers") == null || auth.getJSONArray("authenticatedUsers")
                .size() != 1) {
            throw unsupported("MongoDB authenticated principal is unavailable");
        }
        JSONObject user = auth.getJSONArray("authenticatedUsers").getJSONObject(0);
        String userName = user.getStr("user"), userDb = user.getStr("db");
        try {
            identifier(userName);
            identifier(userDb);
        } catch (IllegalArgumentException failure) {
            throw unsupported("MongoDB principal name is outside the verified scope");
        }
        JSONArray privileges = auth.getJSONArray("authenticatedUserPrivileges");
        if (privileges == null || privileges.isEmpty() || privileges.size() > 512) {
            throw unsupported("MongoDB bounded native privileges are required");
        }
        var grants = new TreeSet<String>();
        boolean readable = false;
        for (int i = 0; i < privileges.size(); i++) {
            JSONObject grant = privileges.getJSONObject(i), resource = grant.getJSONObject("resource");
            JSONArray actions = grant.getJSONArray("actions");
            if (resource == null || actions == null || actions.isEmpty() || actions.size() > 512) {
                throw unsupported("MongoDB privilege shape is not verified");
            }
            boolean applies = Boolean.TRUE.equals(resource.getBool("anyResource"))
                    || ("".equals(resource.getStr("db")) || schema.equals(resource.getStr("db")))
                    && ("".equals(resource.getStr("collection")) || table.equals(resource.getStr("collection")));
            readable |= applies && actions.contains("find");
            var sortedActions = new TreeSet<String>();
            for (Object action : actions) {
                if (!(action instanceof String text) || !text.matches("[A-Za-z][A-Za-z0-9]{0,63}")) {
                    throw unsupported("MongoDB privilege action is invalid");
                }
                sortedActions.add(text);
            }
            grants.add(canonical(resource) + ":" + String.join(",", sortedActions));
        }
        if (!readable) {
            throw unsupported("MongoDB collection read privilege is required");
        }
        JSONArray accounts = command(connection, userDb, "{usersInfo:{user:'" + userName + "',db:'" + userDb
                + "'},showCredentials:false,showPrivileges:false,maxTimeMS:5000}").getJSONArray("users");
        if (accounts == null || accounts.size() != 1) {
            throw unsupported("MongoDB native account generation is unavailable");
        }
        JSONObject account = accounts.getJSONObject(0);
        if (!userName.equals(account.getStr("user")) || !userDb.equals(account.getStr("db"))) {
            throw unsupported("MongoDB account identity disagrees with the session");
        }
        JSONObject info = ordinaryCollection(connection, schema, table);
        JSONObject physical = info.getJSONObject("info");
        if (physical == null || Boolean.TRUE.equals(physical.getBool("readOnly"))) {
            throw unsupported("MongoDB physical identity is unavailable");
        }
        return List.of("MONGODB_7_0_43_COLLECTION_V1", schema, table, userDb, userName,
                uuid(account.getJSONObject("userId")), uuid(physical.getJSONObject("uuid")),
                canonical(info.getJSONObject("options")), String.join("\n", grants));
    }

    /**
     * 按当前值域约定解析或转换UUID 值。
     */
    private static String uuid(JSONObject document) throws SQLException {
        JSONObject binary = document == null ? null : document.getJSONObject("$binary");
        if (binary == null || !"04".equals(binary.getStr("subType"))) {
            throw unsupported("MongoDB native UUID is unavailable");
        }
        String encoded = binary.getStr("base64");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 16 || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
                throw new IllegalArgumentException();
            }
            return HexFormat.of().formatHex(bytes);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw unsupported("MongoDB native UUID is invalid");
        }
    }

    /**
     * 将值整理为当前协议使用的规范形式。
     */
    private static String canonical(Object value) throws SQLException {
        return JSONUtil.toJsonStr(canonicalValue(value, 0));
    }

    /**
     * 将当前值整理为稳定比较使用的规范形式。
     */
    private static Object canonicalValue(Object value, int depth) throws SQLException {
        if (depth > 16) {
            throw unsupported("MongoDB metadata nesting exceeds the verified bound");
        }
        if (value instanceof JSONObject object) {
            var entries = new TreeMap<String, Object>();
            for (var entry : object.entrySet()) {
                entries.put(entry.getKey(), canonicalValue(entry.getValue(), depth + 1));
            }
            return entries;
        }
        if (value instanceof JSONArray array) {
            var entries = new ArrayList<Object>();
            for (Object item : array) {
                entries.add(canonicalValue(item, depth + 1));
            }
            return entries;
        }
        if (value instanceof String text && (text.indexOf('\0') >= 0 || text.length() > 4096)) {
            throw unsupported("MongoDB metadata field is invalid");
        }
        return value;
    }

    /**
     * 返回当前命令。
     */
    private static JSONObject command(Connection connection, String schema, String command) throws SQLException {
        identifier(schema);
        var nativeSession = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection,
                131072, 5);
        String json = nativeSession.metadataJson(nativeSession.command(schema, nativeSession.encode(command)));
        if (json == null || json.length() > 65536 || json.indexOf('\0') >= 0) {
            throw unsupported("MongoDB metadata response exceeds the verified bound");
        }
        try {
            JSONObject result = JSONUtil.parseObj(json);
            if (result.getBigDecimal("ok") == null || result.getBigDecimal("ok")
                    .compareTo(java.math.BigDecimal.ONE) != 0) {
                throw unsupported("MongoDB metadata command was not successful");
            }
            return result;
        } catch (RuntimeException invalid) {
            throw unsupported("MongoDB metadata response is not valid JSON");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }
}

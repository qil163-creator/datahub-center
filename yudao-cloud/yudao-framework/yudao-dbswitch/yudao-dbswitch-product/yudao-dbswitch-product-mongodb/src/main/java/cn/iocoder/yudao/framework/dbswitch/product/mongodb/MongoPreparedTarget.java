package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import javax.sql.DataSource;

/** 与单个现存物理集合代际绑定的不修改观测。 */
public record MongoPreparedTarget(String schema, String table, List<String> fields, String uuid, boolean frame) {

    /**
     * 初始化 {@code MongoPreparedTarget}。
     */
    public MongoPreparedTarget {
        fields = List.copyOf(fields);
    }

    /**
     * 根据恢复定义和目标条件准备 MongoDB 原生目标操作。
     */
    public static MongoPreparedTarget prepare(DataSource source, String schema, String table, List<String> fields) {
        MongoNativeMetadata.identifier(schema);
        MongoNativeMetadata.identifier(table);
        if (fields == null || fields.isEmpty() || new HashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("MongoDB requires distinct prepared fields");
        }
        boolean frame = fields.contains(MongoMigrationFrame.DOCUMENT);
        if (frame) {
            MongoMigrationFrame.requireFields(fields);
        } else {
            fields.forEach(MongoNativeSession::field);
        }
        try (Connection connection = source.getConnection()) {
            var info = MongoNativeMetadata.ordinaryCollection(connection, schema, table);
            if (info.getJSONObject("options").containsKey("collation")
                    && !"simple".equals(info.getJSONObject("options").getJSONObject("collation").getStr("locale"))) {
                throw new SQLException("MongoDB native key matching requires simple collation");
            }
            var identity = info.getJSONObject("info");
            if (identity == null || identity.getJSONObject("uuid") == null) {
                throw new SQLException("MongoDB physical collection identity is missing");
            }
            return new MongoPreparedTarget(schema, table, fields, identity.getJSONObject("uuid").toString(), frame);
        } catch (SQLException failure) {
            throw new IllegalStateException("MongoDB existing target could not be prepared");
        }
    }

    /**
     * 检查数据库连接是否满足当前操作的约定。
     */
    public void verify(Connection connection) throws SQLException {
        var info = MongoNativeMetadata.ordinaryCollection(connection, schema, table).getJSONObject("info");
        if (info == null || info.getJSONObject("uuid") == null || !uuid.equals(info.getJSONObject("uuid").toString())) {
            throw new SQLException("MongoDB target generation changed after preparation");
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "MongoPreparedTarget[fields=" + fields.size() + ",nativeFrame=" + frame + "]";
    }
}

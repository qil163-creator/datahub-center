package cn.iocoder.yudao.framework.dbswitch.product.hive;

import java.sql.Connection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** 原生 Hive UPDATE/DELETE 的已验证表及会话前提；声明式主键不构成证明。 */
public record HiveAcidTable(Set<String> immutableColumns) {

    /**
     * 初始化 {@code HiveAcidTable}。
     */
    public HiveAcidTable {
        immutableColumns = Set.copyOf(immutableColumns);
    }

    /**
     * 检查数据库连接、模式、表是否满足当前操作的约定。
     */
    public static HiveAcidTable require(Connection connection, String schema, String table,
            int timeout) throws Exception {
        var properties = new HashMap<String, String>();
        var immutable = new HashSet<String>();
        String qualified = HiveJdbcWriteSupport.table(schema, table);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery("SHOW TBLPROPERTIES " + qualified)) {
                if (rows.getMetaData().getColumnCount() != 2) {
                    throw new IllegalStateException("Hive table property response is not supported");
                }
                int count = 0;
                while (rows.next()) {
                    if (++count > 4096) {
                        throw new IllegalStateException("Hive table properties exceed the bound");
                    }
                    String key = rows.getString(1), value = rows.getString(2);
                    if (key != null && value != null) {
                        properties.put(key.strip().toLowerCase(java.util.Locale.ROOT), value.strip());
                    }
                }
            }
            if (!"true".equalsIgnoreCase(properties.get("transactional"))
                    || (properties.containsKey("transactional_properties") && !"default".equalsIgnoreCase(
                    properties.get("transactional_properties")))) {
                throw new IllegalStateException("Hive UPDATE/DELETE requires a full ACID table");
            }
            boolean orc = false, partitions = false;
            try (var rows = statement.executeQuery("DESCRIBE FORMATTED " + qualified)) {
                if (rows.getMetaData().getColumnCount() != 3) {
                    throw new IllegalStateException("Hive storage response is not supported");
                }
                int count = 0;
                while (rows.next()) {
                    if (++count > 8192) {
                        throw new IllegalStateException("Hive storage metadata exceeds the bound");
                    }
                    String key = rows.getString(1), value = rows.getString(2);
                    key = key == null ? "" : key.strip();
                    value = value == null ? "" : value.strip();
                    if (key.equals("# Partition Information")) {
                        partitions = true;
                        continue;
                    }
                    if (key.startsWith("# Detailed")) {
                        partitions = false;
                    }
                    if (partitions && !key.isBlank() && !key.startsWith("#")) {
                        immutable.add(key);
                    }
                    if (key.equals("InputFormat:")) {
                        orc = value.equals("org.apache.hadoop.hive.ql.io.orc.OrcInputFormat");
                    }
                    if (key.equals("Bucket Columns:")) {
                        if (!value.startsWith("[") || !value.endsWith("]")) {
                            throw new IllegalStateException("Unrecognized Hive bucket columns");
                        }
                        String contents = value.substring(1, value.length() - 1).strip();
                        if (!contents.isEmpty()) {
                            for (String field : contents.split(",")) {
                                field = field.strip();
                                if (!field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                                    throw new IllegalStateException(
                                            "Hive bucket key needs an exact catalog representation");
                                }
                                immutable.add(field);
                            }
                        }
                    }
                }
            }
            if (!orc) {
                throw new IllegalStateException("Hive full ACID writes require ORC storage");
            }
            try (var rows = statement.executeQuery("SET hive.txn.manager")) {
                if (!rows.next() || !rows.getString(1).strip()
                        .equals("hive.txn.manager=org.apache.hadoop.hive.ql.lockmgr.DbTxnManager")) {
                    throw new IllegalStateException("Hive ACID transaction manager is not active");
                }
            }
        }
        return new HiveAcidTable(immutable);
    }
}

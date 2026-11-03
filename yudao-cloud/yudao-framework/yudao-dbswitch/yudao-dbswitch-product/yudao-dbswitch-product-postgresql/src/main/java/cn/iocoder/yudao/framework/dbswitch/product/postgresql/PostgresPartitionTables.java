package cn.iocoder.yudao.framework.dbswitch.product.postgresql;

import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL 表枚举。分区子表单独列出并记下唯一父表，不并进普通表，也不整表隐藏。
 * 传统继承子表仍然列出。分区父表和分区子表不是默认同步的普通表。
 */
public final class PostgresPartitionTables {

    static final int MAX_RELATIONS = 10_000;

    static final int MAX_CATALOG_ROWS = 20_000;

    public static final String SQL = """
            SELECT n.nspname, c.relname, pg_catalog.obj_description(c.oid, 'pg_class'), c.relkind,
                   c.relispartition, pn.nspname, pc.relname
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_inherits i ON i.inhrelid = c.oid
            LEFT JOIN pg_catalog.pg_class pc ON pc.oid = i.inhparent
            LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid = pc.relnamespace
            WHERE n.nspname = ? AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
            ORDER BY c.relname, c.oid, i.inhseqno
            """;

    public enum Kind {
        /** 普通表或外部表。 */
        TABLE,
        VIEW,
        /** 声明式分区父表，不是默认同步的普通表。 */
        PARTITION_PARENT,
        /** 声明式分区子表，带唯一父表，不是默认同步的普通表。 */
        PARTITION_CHILD,
        /** 传统 INHERITS 子表，保留在枚举里，不按分区隐藏。 */
        INHERITED
    }

    /** 一行 pg_class，左连接后的 pg_inherits 父表。父表两列都空表示没有父表。 */
    public record CatalogRow(String schema, String name, String remarks, String relkind,
                             boolean partition, String parentSchema, String parentName) {

    }

    private record Parent(String schema, String name) {

    }

    public static final class Listed extends TableDescription {

        private final Kind kind;

        private final String parentSchema;

        private final String parentTable;

        /**
         * 初始化 {@code Listed}。
         */
        private Listed(String schema, String name, String remarks, Kind kind, String parentSchema, String parentTable) {
            this.kind = kind;
            this.parentSchema = parentSchema;
            this.parentTable = parentTable;
            setSchemaName(schema);
            setTableName(name);
            setRemarks(remarks);
            // DBTableType 只有 TABLE/VIEW/SQL。分区不能走未知类型，否则下游 setTableType 会失败。
            setTableType(kind == Kind.VIEW ? "VIEW" : "TABLE");
        }

        /**
         * 返回种类。
         */
        public Kind kind() {
            return kind;
        }

        /**
         * 返回父级模式。
         */
        public String parentSchema() {
            return parentSchema;
        }

        /**
         * 返回父级表。
         */
        public String parentTable() {
            return parentTable;
        }

        /** 分区父表和分区子表不能当作普通表默认同步。显式点名仍使用完整枚举。 */
        public boolean defaultSync() {
            return kind == Kind.TABLE || kind == Kind.INHERITED;
        }
    }

    /**
     * 初始化 {@code PostgresPartitionTables}。
     */
    private PostgresPartitionTables() {
    }

    /**
     * 读取当前输入中的数据并按约定结构返回。
     */
    public static List<Listed> read(Connection connection, String schema) throws SQLException {
        if (connection == null) {
            throw new SQLException("PostgreSQL connection is required");
        }
        try (var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, schema);
            statement.setQueryTimeout(30);
            statement.setMaxRows(MAX_CATALOG_ROWS + 1);
            try (var rows = statement.executeQuery()) {
                return classify(catalogRows(rows));
            }
        }
    }

    /**
     * 根据解析后的语句结构判断 SQL 操作类别。
     */
    public static List<Listed> classify(List<CatalogRow> rows) throws SQLException {
        if (rows == null) {
            throw new SQLException("PostgreSQL catalog rows are required");
        }
        if (rows.size() > MAX_CATALOG_ROWS) {
            throw new SQLException("PostgreSQL table list exceeds its budget");
        }
        var grouped = new LinkedHashMap<String, Group>();
        for (var row : rows) {
            requireIdentity(row);
            String key = row.schema() + "\0" + row.name();
            var existing = grouped.get(key);
            if (existing == null) {
                if (grouped.size() == MAX_RELATIONS) {
                    throw new SQLException("PostgreSQL table list exceeds its budget");
                }
                grouped.put(key, new Group(row));
            } else {
                existing.accept(row);
            }
        }
        var listed = new ArrayList<Listed>(grouped.size());
        for (var group : grouped.values()) {
            listed.add(group.toListed());
        }
        return List.copyOf(listed);
    }

    /** 默认同步只保留普通表和传统继承子表，不把分区父表或子表算进去。 */
    public static List<Listed> defaultSyncTargets(List<Listed> listed) {
        if (listed == null) {
            throw new IllegalArgumentException("PostgreSQL table list is required");
        }
        var targets = new ArrayList<Listed>();
        for (var table : listed) {
            if (table != null && table.defaultSync()) {
                targets.add(table);
            }
        }
        return List.copyOf(targets);
    }

    /**
     * 读取并累计系统目录行，拒绝超过当前目录数量预算。
     */
    static List<CatalogRow> catalogRows(ResultSet rows) throws SQLException {
        var catalog = new ArrayList<CatalogRow>();
        while (rows.next()) {
            if (catalog.size() == MAX_CATALOG_ROWS) {
                throw new SQLException("PostgreSQL table list exceeds its budget");
            }
            boolean partition = rows.getBoolean(5);
            if (rows.wasNull()) {
                throw new SQLException("PostgreSQL relispartition is missing");
            }
            catalog.add(new CatalogRow(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                    partition, rows.getString(6), rows.getString(7)));
        }
        return catalog;
    }

    /**
     * 校验并取得身份信息。
     */
    private static void requireIdentity(CatalogRow row) throws SQLException {
        if (row == null || row.schema() == null || row.name() == null || row.relkind() == null
                || row.schema().isBlank() || row.name().isBlank() || row.relkind().length() != 1
                || row.schema().indexOf('\0') >= 0 || row.name().indexOf('\0') >= 0
                || row.schema().length() > 256 || row.name().length() > 256) {
            throw new SQLException("PostgreSQL relation identity is incomplete");
        }
    }

    private static final class Group {

        private final String schema;

        private final String name;

        private final String remarks;

        private final String relkind;

        private final boolean partition;

        private final LinkedHashSet<Parent> parents = new LinkedHashSet<>();

        private boolean missingParent;

        /**
         * 初始化 {@code Group}。
         */
        private Group(CatalogRow row) throws SQLException {
            schema = row.schema();
            name = row.name();
            remarks = row.remarks();
            relkind = row.relkind();
            partition = row.partition();
            addParent(row);
        }

        /**
         * 接收当前输入并更新当前处理器的状态。
         */
        private void accept(CatalogRow row) throws SQLException {
            if (!schema.equals(row.schema()) || !name.equals(row.name()) || partition != row.partition()
                    || !relkind.equals(row.relkind()) || !Objects.equals(remarks, row.remarks())) {
                throw new SQLException("Relation identity changed during table list");
            }
            addParent(row);
        }

        /**
         * 添加父级。
         */
        private void addParent(CatalogRow row) throws SQLException {
            boolean absent = row.parentSchema() == null && row.parentName() == null;
            boolean present = row.parentSchema() != null && row.parentName() != null;
            if (!absent && !present) {
                throw new SQLException("Incomplete PostgreSQL partition ancestry");
            }
            if (absent) {
                missingParent = true;
                return;
            }
            if (row.parentSchema().isBlank() || row.parentName().isBlank()
                    || row.parentSchema().indexOf('\0') >= 0 || row.parentName().indexOf('\0') >= 0
                    || row.parentSchema().length() > 256 || row.parentName().length() > 256) {
                throw new SQLException("Incomplete PostgreSQL partition ancestry");
            }
            parents.add(new Parent(row.parentSchema(), row.parentName()));
        }

        /**
         * 转换为已列出。
         */
        private Listed toListed() throws SQLException {
            if (missingParent && !parents.isEmpty()) {
                throw new SQLException("Incomplete PostgreSQL partition ancestry");
            }
            if ("v".equals(relkind) || "m".equals(relkind)) {
                if (partition || !parents.isEmpty()) {
                    throw new SQLException("PostgreSQL view cannot be a partition child");
                }
                return new Listed(schema, name, remarks, Kind.VIEW, null, null);
            }
            if (partition) {
                if ((!"r".equals(relkind) && !"p".equals(relkind) && !"f".equals(relkind)) || parents.size() != 1) {
                    throw new SQLException("Incomplete PostgreSQL partition ancestry");
                }
                Parent parent = parents.getFirst();
                if (parent.schema().equals(schema) && parent.name().equals(name)) {
                    throw new SQLException("Incomplete PostgreSQL partition ancestry");
                }
                return new Listed(schema, name, remarks, Kind.PARTITION_CHILD, parent.schema(), parent.name());
            }
            if ("p".equals(relkind)) {
                return new Listed(schema, name, remarks, Kind.PARTITION_PARENT, null, null);
            }
            if ("r".equals(relkind) && !parents.isEmpty()) {
                return new Listed(schema, name, remarks, Kind.INHERITED, null, null);
            }
            if ("r".equals(relkind) || "f".equals(relkind)) {
                return new Listed(schema, name, remarks, Kind.TABLE, null, null);
            }
            throw new SQLException("PostgreSQL relation kind is not listable");
        }
    }
}

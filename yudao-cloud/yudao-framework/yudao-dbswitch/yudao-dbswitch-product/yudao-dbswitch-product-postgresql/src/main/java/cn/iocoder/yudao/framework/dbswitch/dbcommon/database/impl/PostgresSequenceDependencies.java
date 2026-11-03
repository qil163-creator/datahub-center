package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.JdbcReadDeadline;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresReadSnapshot;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.core.model.SequenceDependencyGraph.*;

/** 仅基于目录计算传递闭包；读取序列位置绝不会推进 nextval。 */
final class PostgresSequenceDependencies {

    private final Connection connection;

    private final ReadSnapshot snapshot;

    private final JdbcReadDeadline deadline;

    private final int timeout;

    private final long databaseOid;

    private int referenceCount;

    /**
     * 从 PostgreSQL 系统目录读取所选关系的序列依赖闭包。
     */
    static SequenceDependencyGraph read(DataSource source, List<TableReadPlan.Relation> selected, int timeoutSeconds) {
        return read(source, selected, timeoutSeconds, false);
    }

    /**
     * 读取有界。
     */
    static SequenceDependencyGraph readBounded(DataSource source, List<TableReadPlan.Relation> selected,
            int timeoutSeconds) {
        return read(source, selected, timeoutSeconds, true);
    }

    /**
     * 从 PostgreSQL 系统目录读取所选关系的序列依赖闭包。
     */
    private static SequenceDependencyGraph read(DataSource source, List<TableReadPlan.Relation> selected,
            int timeoutSeconds, boolean bounded) {
        if (selected == null || selected.isEmpty() || selected.size() > MAX_RELATIONS
                || new HashSet<>(selected).size() != selected.size()) {
            throw new IllegalArgumentException("A bounded distinct migration selection is required");
        }
        var selection = Set.copyOf(selected);
        for (var relation : selection) {
            RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, relation.schema(), relation.table());
        }
        int boundedTimeout = Math.min(120, timeoutSeconds);
        if (boundedTimeout < 1) {
            throw new IllegalArgumentException("Migration dependency timeout must be positive");
        }
        try (var acquisition = bounded ? new PostgresConnectionDeadline(source, boundedTimeout) : null) {
            SequenceDependencyGraph graph;
            try (var connection = acquisition == null ? source.getConnection() : acquisition.open()) {
                try (var deadline = acquisition == null ? new JdbcReadDeadline(connection,
                        boundedTimeout * 1000) : null;
                     var snapshot = PostgresReadSnapshot.open(connection, boundedTimeout)) {
                    if (connection.getMetaData().getDatabaseMajorVersion() < 13) {
                        throw new SQLException("Migration dependency snapshot requires PostgreSQL 13 or newer");
                    }
                    var reader = new PostgresSequenceDependencies(connection, snapshot,
                            acquisition == null ? deadline : acquisition.jdbc(), boundedTimeout);
                    graph = reader.discover(selection);
                }
            }
            if (acquisition != null) {
                acquisition.check();
            }
            return graph;
        } catch (SQLException failure) {
            throw new IllegalStateException("PostgreSQL migration dependency observation failed");
        }
    }

    /**
     * 校验构造参数并初始化 {@code PostgresSequenceDependencies}。
     */
    private PostgresSequenceDependencies(Connection connection, ReadSnapshot snapshot, JdbcReadDeadline deadline,
            int timeout) throws SQLException {
        this.connection = connection;
        this.snapshot = snapshot;
        this.deadline = deadline;
        this.timeout = timeout;
        try (var query = query("SELECT oid::bigint FROM pg_catalog.pg_database WHERE datname=current_database()", 2);
             var row = query.executeQuery()) {
            if (!row.next()) {
                throw new SQLException("Database identity was not observed");
            }
            databaseOid = row.getLong(1);
            if (row.next()) {
                throw new SQLException("Ambiguous database identity");
            }
        }
    }

    /**
     * 根据当前输入构建并返回 {@code SequenceDependencyGraph}。
     */
    private SequenceDependencyGraph discover(Set<TableReadPlan.Relation> selected) throws SQLException {
        var pending = new ArrayDeque<>(selected);
        var visited = new HashSet<TableReadPlan.Relation>();
        Map<Long, Sequence> sequences = new TreeMap<>();
        while (!pending.isEmpty()) {
            deadline.check();
            var relation = pending.removeFirst();
            if (!visited.add(relation)) {
                continue;
            }
            if (visited.size() > MAX_RELATIONS) {
                throw new IllegalArgumentException("Sequence dependency relation limit exceeded");
            }
            long relationOid = relationOid(relation);
            for (long sequenceOid : sequencesFor(relationOid)) {
                if (sequences.containsKey(sequenceOid)) {
                    continue;
                }
                if (sequences.size() >= MAX_SEQUENCES) {
                    throw new IllegalArgumentException("Sequence dependency node limit exceeded");
                }
                Sequence sequence = readSequence(sequenceOid);
                sequences.put(sequenceOid, sequence);
                if (sequence.owner().value() != null) {
                    pending.addLast(sequence.owner().value().relation());
                }
                sequence.references().forEach(reference -> pending.addLast(reference.column().relation()));
            }
        }
        deadline.check();
        return new SequenceDependencyGraph(snapshot.evidence(), selected, List.copyOf(sequences.values()));
    }

    /**
     * 通过系统目录查询指定关系的对象标识。
     */
    private long relationOid(TableReadPlan.Relation relation) throws SQLException {
        try (var query = query(
                "SELECT c.oid::bigint FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                        + "WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p','v','m','f') AND c.relpersistence='p'",
                2)) {
            query.setString(1, relation.schema());
            query.setString(2, relation.table());
            try (var row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("A dependency relation is missing or unsupported");
                }
                long oid = row.getLong(1);
                if (row.next()) {
                    throw new SQLException("Ambiguous relation identity");
                }
                return oid;
            }
        }
    }

    /**
     * 读取指定关系依赖的序列集合。
     */
    private List<Long> sequencesFor(long relationOid) throws SQLException {
        String sql = "SELECT s.oid::bigint FROM pg_catalog.pg_depend dep JOIN pg_catalog.pg_class s ON s.oid=dep.objid AND s.relkind='S' "
                + "WHERE dep.classid='pg_catalog.pg_class'::regclass AND dep.refclassid='pg_catalog.pg_class'::regclass "
                + "AND dep.refobjid=?::oid AND dep.refobjsubid>0 AND dep.deptype IN ('a','i') "
                + "UNION SELECT s.oid::bigint FROM pg_catalog.pg_depend dep JOIN pg_catalog.pg_attrdef d ON d.oid=dep.objid "
                + "JOIN pg_catalog.pg_class s ON s.oid=dep.refobjid AND s.relkind='S' "
                + "WHERE dep.classid='pg_catalog.pg_attrdef'::regclass AND dep.refclassid='pg_catalog.pg_class'::regclass AND d.adrelid=?::oid";
        List<Long> result = new ArrayList<>();
        try (var query = query(sql, MAX_SEQUENCES + 1)) {
            query.setLong(1, relationOid);
            query.setLong(2, relationOid);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (result.size() >= MAX_SEQUENCES) {
                        throw new IllegalArgumentException("Sequence dependency node limit exceeded");
                    }
                    result.add(rows.getLong(1));
                }
            }
        }
        return result;
    }

    /**
     * 读取序列。
     */
    private Sequence readSequence(long sequenceOid) throws SQLException {
        String schema, name, type;
        long start, increment, minimum, maximum, cache;
        boolean cycle, stateVisible;
        try (var query = query(
                "SELECT n.nspname,c.relname,pg_catalog.format_type(s.seqtypid,NULL),s.seqstart,s.seqincrement,"
                        + "s.seqmin,s.seqmax,s.seqcache,s.seqcycle,pg_catalog.has_sequence_privilege(c.oid,'SELECT') "
                        + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                        + "JOIN pg_catalog.pg_sequence s ON s.seqrelid=c.oid WHERE c.oid=?::oid AND c.relkind='S' AND c.relpersistence='p'",
                2)) {
            query.setLong(1, sequenceOid);
            try (var row = query.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Sequence definition is unavailable");
                }
                schema = row.getString(1);
                name = row.getString(2);
                type = row.getString(3);
                start = row.getLong(4);
                increment = row.getLong(5);
                minimum = row.getLong(6);
                maximum = row.getLong(7);
                cache = row.getLong(8);
                cycle = row.getBoolean(9);
                stateVisible = row.getBoolean(10);
                if (row.next()) {
                    throw new SQLException("Ambiguous sequence definition");
                }
            }
        }
        var identity = new Identity(databaseOid, sequenceOid, schema, name);
        var references = new ArrayList<Reference>();
        MetadataValue<Column> owner = MetadataValue.absent();
        try (var query = query("SELECT n.nspname,c.relname,c.oid::bigint,a.attnum,a.attname,a.attidentity "
                + "FROM pg_catalog.pg_depend dep JOIN pg_catalog.pg_class c ON c.oid=dep.refobjid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum=dep.refobjsubid AND NOT a.attisdropped "
                + "WHERE dep.classid='pg_catalog.pg_class'::regclass AND dep.refclassid='pg_catalog.pg_class'::regclass "
                + "AND dep.objid=?::oid AND dep.refobjsubid>0 AND dep.deptype IN ('a','i')", 2)) {
            query.setLong(1, sequenceOid);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    var column = column(rows);
                    owner = MetadataValue.value(column);
                    String identityKind = rows.getString(6);
                    if ("a".equals(identityKind) || "d".equals(identityKind)) {
                        addReference(references, new Reference(column, "a".equals(
                                identityKind) ? ReferenceKind.IDENTITY_ALWAYS : ReferenceKind.IDENTITY_BY_DEFAULT,
                                MetadataValue.absent()));
                    }
                    if (rows.next()) {
                        throw new SQLException("Sequence has multiple ownership dependencies");
                    }
                }
            }
        }
        try (var query = query(
                "SELECT n.nspname,c.relname,c.oid::bigint,a.attnum,a.attname,pg_catalog.pg_get_expr(d.adbin,d.adrelid) "
                        + "FROM pg_catalog.pg_depend dep JOIN pg_catalog.pg_attrdef d ON d.oid=dep.objid "
                        + "JOIN pg_catalog.pg_class c ON c.oid=d.adrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                        + "JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum=d.adnum AND NOT a.attisdropped "
                        + "WHERE dep.classid='pg_catalog.pg_attrdef'::regclass AND dep.refclassid='pg_catalog.pg_class'::regclass AND dep.refobjid=?::oid "
                        + "ORDER BY c.oid,a.attnum", MAX_REFERENCES + 1)) {
            query.setLong(1, sequenceOid);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    addReference(references, new Reference(column(rows), ReferenceKind.DEFAULT_EXPRESSION,
                            MetadataValue.value(rows.getString(6))));
                }
            }
        }
        MetadataValue<ColumnSequenceMetadata.Position> position = MetadataValue.unknown();
        if (stateVisible) {
            try (var checkpoint = snapshot.checkpoint(); var query = query("SELECT last_value,is_called FROM "
                    + RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, schema, name), 2);
                 var rows = query.executeQuery()) {
                if (rows.next()) {
                    position = MetadataValue.value(
                            new ColumnSequenceMetadata.Position(rows.getLong(1), rows.getBoolean(2)));
                }
                if (rows.next()) {
                    throw new SQLException("Ambiguous sequence position");
                }
            } catch (SQLException unavailable) {
                position = MetadataValue.unknown();
            }
        }
        deadline.check();
        return new Sequence(identity, new Definition(type, start, increment, minimum, maximum, cache, cycle, position),
                owner, references);
    }

    /**
     * 添加限定关系名。
     */
    private void addReference(List<Reference> references, Reference value) {
        if (++referenceCount > MAX_REFERENCES) {
            throw new IllegalArgumentException("Sequence dependency reference limit exceeded");
        }
        references.add(value);
    }

    /**
     * 返回列。
     */
    private static Column column(ResultSet row) throws SQLException {
        return new Column(new TableReadPlan.Relation(row.getString(1), row.getString(2)), row.getLong(3), row.getInt(4),
                row.getString(5));
    }

    /**
     * 返回查询。
     */
    private PreparedStatement query(String sql, int maxRows) throws SQLException {
        deadline.check();
        var statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(maxRows);
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }
}

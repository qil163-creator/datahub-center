package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresIdentityTransfer;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;

/** 一个源端资源对应一个目标依赖组；请求 DTO 无法构造其私有绑定。 */
public final class PostgresSequenceTransferScope implements SequenceTransferScope {

    private final DataSource source;

    private final int timeout;

    private final boolean replace, truncate;

    private final Mode mode;

    private final Map<TableReadPlan.Relation, Binding> bindings = new LinkedHashMap<>();

    private final List<Group> groups = new ArrayList<>();

    private final Set<TableReadPlan.Relation> existing = new HashSet<>();

    private final Map<TableReadPlan.Relation, Long> tableOids = new HashMap<>();

    private boolean prepared, completed, closed;

    private static final class Group {

        final SequenceDependencyGraph.Sequence origin;

        final Set<ColumnKey> references;

        final ColumnKey owner;

        final SequenceDependencyGraph.ReferenceKind identityKind;

        String schema, name;

        long oid;

        ColumnSequenceMetadata before, beforeDdl;

        /**
         * 初始化 {@code Group}。
         */
        Group(SequenceDependencyGraph.Sequence origin, Set<ColumnKey> references, ColumnKey owner, String schema,
                String name,
                SequenceDependencyGraph.ReferenceKind identityKind) {
            this.origin = origin;
            this.references = Set.copyOf(references);
            this.owner = owner;
            this.schema = schema;
            this.name = name;
            this.identityKind = identityKind;
        }

        /**
         * 返回身份信息。
         */
        boolean identity() {
            return identityKind != null;
        }

        /**
         * 构造当前样本读取得到的能力或类型证据。
         */
        ColumnSequenceMetadata sampled() {
            return origin.definition().columnMetadata(origin.identity(), ColumnSequenceMetadata.Kind.OWNED_DEFAULT);
        }

        /**
         * 返回当前数据库对象经过引用处理的限定名称。
         */
        String qualified() {
            return relation(new TableReadPlan.Relation(schema, name));
        }
    }

    private record ColumnKey(TableReadPlan.Relation table, String column) {

    }

    /**
     * 打开序列传输范围并准备后续访问。
     */
    public static SequenceTransferScope open(DataSource source, SequenceDependencyGraph graph, List<TablePlan> tables,
            boolean replace, boolean truncate, boolean failIfExists, int timeout, Mode mode) {
        return new PostgresSequenceTransferScope(source, graph, tables, replace, truncate, failIfExists, timeout, mode);
    }

    /**
     * 校验构造参数并初始化 {@code PostgresSequenceTransferScope}。
     */
    private PostgresSequenceTransferScope(DataSource source, SequenceDependencyGraph graph, List<TablePlan> tables,
            boolean replace, boolean truncate, boolean failIfExists, int timeout, Mode mode) {
        this.source = Objects.requireNonNull(source);
        this.timeout = Math.min(120, timeout);
        this.replace = replace;
        this.truncate = truncate;
        this.mode = Objects.requireNonNull(mode);
        if (mode == Mode.STRUCTURE_ONLY && truncate) {
            throw new IllegalArgumentException("Structure-only transfer cannot truncate existing data");
        }
        graph.requireClosedSelection();
        if (timeout < 1 || tables.isEmpty() || tables.size() > SequenceDependencyGraph.MAX_RELATIONS) {
            throw new IllegalArgumentException("Invalid sequence transfer bounds");
        }
        var targets = new HashSet<TableReadPlan.Relation>();
        for (var table : tables) {
            if (bindings.putIfAbsent(table.source(), new Binding(table)) != null || !targets.add(table.target())) {
                throw new IllegalArgumentException("Sequence transfer has duplicate table mappings");
            }
        }
        if (!bindings.keySet().equals(graph.selectedRelations())) {
            throw new IllegalArgumentException("Sequence transfer mapping must cover the observed selection exactly");
        }
        for (var sequence : graph.sequences()) {
            var identities = sequence.references().stream()
                    .filter(ref -> ref.kind() != SequenceDependencyGraph.ReferenceKind.DEFAULT_EXPRESSION).toList();
            if (!identities.isEmpty() && !sequence.shared()) {
                continue; // 普通自增标识保留按表处理的策略。
            }
            if (identities.size() > 1 || !identities.isEmpty() && !identities.getFirst().column()
                    .equals(sequence.owner().value())) {
                throw new IllegalArgumentException("An identity sequence requires exactly its observed native owner");
            }
            if (sequence.references().isEmpty()) {
                throw new IllegalArgumentException("Unconsumed owned sequence cannot be transferred implicitly");
            }
            var sample = sequence.definition()
                    .columnMetadata(sequence.identity(), ColumnSequenceMetadata.Kind.OWNED_DEFAULT);
            if (mode == Mode.DATA_AND_STRUCTURE && (sample.cycle() || sample.position()
                    .state() != MetadataValue.State.VALUE)) {
                throw new IllegalArgumentException("Shared data transfer requires an observed non-cycling position");
            }
            var references = new HashSet<ColumnKey>();
            for (var reference : sequence.references()) {
                if (reference.kind() == SequenceDependencyGraph.ReferenceKind.DEFAULT_EXPRESSION
                        && !reference.expression().value().matches("nextval\\('(?:[^']|'')+'::regclass\\)")) {
                    throw new UnsupportedOperationException(
                            "Shared sequence default needs a verified simple nextval expression");
                }
                references.add(mapped(reference.column()));
            }
            ColumnKey owner = sequence.owner().value() == null ? null : mapped(sequence.owner().value());
            String targetSchema = references.iterator().next().table().schema();
            String stable = references.stream().map(ref -> relation(ref.table()) + "." + quote(ref.column())).sorted()
                    .collect(java.util.stream.Collectors.joining("|"));
            Group group = new Group(sequence, references, owner, targetSchema,
                    "dbswitch_shared_" + SqlScriptPlan.digest(stable).substring(0, 32),
                    identities.isEmpty() ? null : identities.getFirst().kind());
            groups.add(group);
            for (var reference : sequence.references()) {
                bindings.get(reference.column().relation()).columns.put(mapped(reference.column()).column(), group);
            }
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("No default sequence dependency to coordinate");
        }
        try (var deadline = new PostgresConnectionDeadline(source, this.timeout)) {
            try (var connection = deadline.open()) {
                for (var binding : bindings.values()) {
                    long oid = tableOid(connection, binding.plan.target(), false);
                    if (oid != 0) {
                        existing.add(binding.plan.target());
                        tableOids.put(binding.plan.target(), oid);
                    }
                    deadline.check();
                }
            }
            deadline.check();
        } catch (SQLException failure) {
            throw new IllegalStateException("Target dependency preflight failed");
        }
        if (failIfExists && !existing.isEmpty()) {
            throw new IllegalStateException("Explicit create refuses existing sequence targets");
        }
        if (!existing.isEmpty()) {
            var targetGraph = PostgresSequenceDependencies.readBounded(source, List.copyOf(existing), timeout);
            targetGraph.requireClosedSelection(); // 也包含仅存在于目标端的引用和归属，不只是选中源端。
            for (var group : groups) {
                boolean touchesExisting = group.references.stream().anyMatch(ref -> existing.contains(ref.table()));
                if (!touchesExisting) {
                    continue;
                }
                var candidates = targetGraph.sequences().stream()
                        .filter(seq -> seq.references().stream().anyMatch(ref ->
                                group.references.contains(new ColumnKey(ref.column().relation(), ref.column().name()))))
                        .toList();
                if (candidates.size() != 1) {
                    throw new IllegalArgumentException("Target shared sequence mapping is incomplete or ambiguous");
                }
                var actual = candidates.getFirst();
                var actualRefs = actual.references().stream()
                        .map(ref -> new ColumnKey(ref.column().relation(), ref.column().name()))
                        .collect(java.util.stream.Collectors.toSet());
                ColumnKey actualOwner = actual.owner().value() == null ? null : new ColumnKey(
                        actual.owner().value().relation(), actual.owner().value().name());
                if (!actualRefs.equals(group.references) || !Objects.equals(actualOwner, group.owner)
                        || actual.references().stream().anyMatch(
                        ref -> group.identity() && new ColumnKey(ref.column().relation(), ref.column().name()).equals(
                                group.owner)
                                ? ref.kind() != group.identityKind : ref.kind() != SequenceDependencyGraph.ReferenceKind.DEFAULT_EXPRESSION
                                || !ref.expression().value().matches("nextval\\('(?:[^']|'')+'::regclass\\)"))) {
                    throw new IllegalArgumentException(
                            "Target shared sequence dependencies differ from the complete source mapping");
                }
                var sample = actual.definition()
                        .columnMetadata(actual.identity(), ColumnSequenceMetadata.Kind.OWNED_DEFAULT);
                if (!sample.structuralIdentity().equals(group.sampled().structuralIdentity()) || sample.position()
                        .state() != MetadataValue.State.VALUE) {
                    throw new IllegalArgumentException("Target shared sequence structure or position is unverified");
                }
                group.schema = actual.identity().schema();
                group.name = actual.identity().name();
                group.before = sample;
                group.oid = actual.identity().sequenceOid();
            }
        }
        try (var deadline = new PostgresConnectionDeadline(source, this.timeout)) {
            try (var connection = deadline.open()) {
                for (var group : groups) {
                    if (group.before != null && sequenceOid(connection, group) != group.oid) {
                        throw new IllegalStateException("Target sequence changed during preflight");
                    }
                }
            }
            deadline.check();
        } catch (SQLException failure) {
            throw new IllegalStateException("Target sequence ownership preflight failed");
        }
    }

    /**
     * 将当前对象或数据行按既定映射转换为目标形式。
     */
    private ColumnKey mapped(SequenceDependencyGraph.Column column) {
        var binding = bindings.get(column.relation());
        String field = binding == null ? null : binding.plan.columns().get(column.name());
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Sequence dependency column was removed from the mapping");
        }
        return new ColumnKey(binding.plan.target(), field);
    }

    /**
     * 返回绑定信息。
     */
    @Override
    public TableBinding binding(TableReadPlan.Relation sourceTable) {
        var binding = bindings.get(sourceTable);
        if (binding == null) {
            throw new IllegalArgumentException("Unmapped dependency relation");
        }
        return binding;
    }

    /**
     * 根据已声明依赖组准备序列创建、状态及目标绑定。
     */
    @Override
    public void prepare(Map<TableReadPlan.Relation, List<String>> statements) {
        requireActive();
        if (prepared || !statements.keySet().equals(bindings.keySet())) {
            throw new IllegalStateException("Dependency structure preparation is out of order");
        }
        List<String> ddl = new ArrayList<>();
        if (replace && !existing.isEmpty()) {
            ddl.add("DROP TABLE " + existing.stream().map(PostgresSequenceTransferScope::relation).sorted()
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        for (var group : orderedGroups()) {
            if (!group.identity() && (group.before == null || replace && group.owner != null)) {
                ddl.add("CREATE SEQUENCE " + group.qualified() + " AS " + group.sampled()
                        .dataType() + " " + group.sampled().optionsSql());
            }
        }
        for (var binding : orderedBindings()) {
            if (replace || !existing.contains(binding.plan.target())) {
                ddl.addAll(statements.get(binding.plan.source()));
            }
        }
        // 任何依赖表的默认值绑定序列前，序列所属的自增列必须已存在。
        for (var binding : orderedBindings()) {
            if (replace || !existing.contains(binding.plan.target())) {
                ddl.addAll(binding.defaultStatements());
            }
        }
        for (var group : orderedGroups()) {
            if (!group.identity() && group.owner != null && (group.before == null || replace)) {
                ddl.add("ALTER SEQUENCE " + group.qualified() + " OWNED BY " + relation(
                        group.owner.table()) + "." + quote(group.owner.column()));
            }
        }
        if (truncate && !replace && !existing.isEmpty()) {
            ddl.add("TRUNCATE TABLE " + existing.stream().map(PostgresSequenceTransferScope::relation).sorted()
                    .collect(java.util.stream.Collectors.joining(", ")) + " CONTINUE IDENTITY");
        }
        var plans = ddl.stream().map(sql -> SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql)).toList();
        transaction(context -> {
            for (var binding : orderedBindings()) {
                if (existing.contains(binding.plan.target())) {
                    lock(context.connection(), binding.plan.target(), "ACCESS EXCLUSIVE");
                }
            }
            verifyTables(context.connection());
            for (var group : orderedGroups()) {
                if (group.before != null) {
                    if (sequenceOid(context.connection(), group) != group.oid) {
                        throw new IllegalStateException("Target sequence was replaced before structure preparation");
                    }
                    execute(context, "ALTER SEQUENCE " + group.qualified() + " CACHE " + group.sampled().cache());
                    verifyReferences(context.connection(), group);
                    group.beforeDdl = observe(context.connection(), group);
                }
            }
            for (var plan : plans) {
                for (var step : plan.steps()) {
                    execute(context, step.sql());
                }
            }
            for (var binding : orderedBindings()) {
                tableOids.put(binding.plan.target(), tableOid(context.connection(), binding.plan.target(), true));
            }
            for (var group : orderedGroups()) {
                group.oid = sequenceOid(context.connection(), group);
                verifyReferences(context.connection(), group);
            }
            // 源端序列计数器不属于结构，绝不能在此复制其非 MVCC 采样。
            // 显式替换仍不得复用目标序列此前已分配的值。
            if (mode == Mode.STRUCTURE_ONLY && replace) {
                for (var group : orderedGroups()) {
                    if (group.beforeDdl != null && group.owner != null) {
                        long next = restartAfterStructureReplacement(group.beforeDdl,
                                observe(context.connection(), group));
                        execute(context, "ALTER SEQUENCE " + group.qualified() + " RESTART WITH " + next);
                    }
                }
            }
        });
        prepared = true;
    }

    /** 结构替换保留目标分配边界，不使用源端计数器或行极值。 */
    private static long restartAfterStructureReplacement(ColumnSequenceMetadata previous,
            ColumnSequenceMetadata recreated) {
        if (!previous.structuralIdentity().equals(recreated.structuralIdentity())) {
            throw new IllegalArgumentException(
                    "Recreated sequence attributes differ from the locked target observation");
        }
        if (!previous.cycle()) {
            return PostgresIdentityTransfer.alignedNext(previous, recreated, null);
        }
        BigInteger next = previous.nextObservedValue();
        // PostgreSQL CYCLE 从 MINVALUE 或 MAXVALUE 恢复，而非初始 START WITH 值。
        if (next.compareTo(BigInteger.valueOf(previous.maximum())) > 0) {
            return previous.minimum();
        }
        if (next.compareTo(BigInteger.valueOf(previous.minimum())) < 0) {
            return previous.maximum();
        }
        return next.longValueExact();
    }

    /**
     * 所有成员必须先完成。数据模式对齐序列状态；结构模式只确认已准备 DDL。
     */
    @Override
    public void complete() {
        requireActive();
        if (!prepared || completed || bindings.values().stream().anyMatch(binding -> !binding.finished)) {
            throw new IllegalStateException("Shared sequence completion requires all confirmed table results");
        }
        if (mode == Mode.STRUCTURE_ONLY) {
            completed = true;
            return;
        }
        transaction(context -> {
            for (var binding : orderedBindings()) {
                lock(context.connection(), binding.plan.target(), "ACCESS EXCLUSIVE");
            }
            verifyTables(context.connection());
            for (var group : orderedGroups()) {
                execute(context, "ALTER SEQUENCE " + group.qualified() + " CACHE " + group.sampled().cache());
            }
            for (var group : orderedGroups()) {
                if (sequenceOid(context.connection(), group) != group.oid) {
                    throw new IllegalStateException("Target shared sequence was replaced");
                }
                verifyReferences(context.connection(), group);
                var observed = observe(context.connection(), group);
                BigInteger extreme = null;
                for (var ref : group.references) {
                    try (var query = context.connection().createStatement()) {
                        query.setQueryTimeout(timeout);
                        try (var rows = query.executeQuery(
                                "SELECT " + (observed.increment() > 0 ? "max(" : "min(") + quote(
                                        ref.column()) + ") FROM " + relation(ref.table()))) {
                            if (!rows.next()) {
                                throw new SQLException("Shared sequence aggregate is missing");
                            }
                            var value = rows.getBigDecimal(1);
                            if (value != null) {
                                var integer = value.toBigIntegerExact();
                                extreme = extreme == null ? integer : observed.increment() > 0 ? extreme.max(
                                        integer) : extreme.min(integer);
                            }
                        }
                    }
                }
                long next = PostgresIdentityTransfer.alignedNext(group.sampled(), observed, extreme);
                // 替换前在锁保护下立即获得的观测结果优先于预检结果。
                // 两次观测之间分配的值在 DROP 后绝不能重新可用。
                if (group.beforeDdl != null) {
                    long previous = PostgresIdentityTransfer.alignedNext(group.beforeDdl, observed, extreme);
                    next = observed.increment() > 0 ? Math.max(next, previous) : Math.min(next, previous);
                }
                execute(context, "ALTER SEQUENCE " + group.qualified() + " RESTART WITH " + next);
            }
        });
        completed = true;
    }

    /**
     * 设置关闭标记。
     */
    @Override
    public void close() {
        closed = true;
    }

    /**
     * 校验并取得活跃。
     */
    private void requireActive() {
        if (closed) {
            throw new IllegalStateException("Sequence scope is closed");
        }
    }

    /**
     * 按字段顺序返回已经解析的参数绑定。
     */
    private List<Binding> orderedBindings() {
        return bindings.values().stream().sorted(Comparator.comparing(binding -> relation(binding.plan.target())))
                .toList();
    }

    /**
     * 按依赖或声明顺序返回分组定义。
     */
    private List<Group> orderedGroups() {
        return groups.stream().sorted(Comparator.comparing(Group::qualified)).toList();
    }

    /** 自增标识写入器仅接受此管理方签发且关联到确切数据源的真实绑定。 */
    public static TableBinding requireBinding(TableBinding value, DataSource source) {
        if (!(value instanceof PostgresSequenceTransferScope.Binding binding) || binding.owner().source != source) {
            throw new IllegalArgumentException("Untrusted or foreign sequence transfer binding");
        }
        return binding;
    }

    private final class Binding implements TableBinding {

        final TablePlan plan;

        final Map<String, Group> columns = new HashMap<>();

        volatile boolean finished;

        /**
         * 初始化 {@code Binding}。
         */
        Binding(TablePlan plan) {
            this.plan = plan;
        }

        /**
         * 返回归属标识。
         */
        PostgresSequenceTransferScope owner() {
            return PostgresSequenceTransferScope.this;
        }

        /**
         * 判断当前路径是否负责写入指定列。
         */
        @Override
        public boolean managesColumn(String column) {
            return columns.containsKey(column);
        }

        /**
         * 组装建表操作使用的列定义。
         */
        @Override
        public List<ColumnDescription> columnsForCreate(List<ColumnDescription> values) {
            var result = new ArrayList<ColumnDescription>();
            for (var original : values) {
                var column = original.copy();
                if (columns.containsKey(column.getFieldName())) {
                    var group = columns.get(column.getFieldName());
                    var facts = column.getMetadataEvidence();
                    if (!facts.provenance().nativeProduct(ProductTypeEnum.POSTGRESQL) || facts.nativeType()
                            .value() == null
                            || !facts.nativeType().value().matches("smallint|integer|bigint")) {
                        throw new IllegalArgumentException(
                                "Shared nextval requires a proven PostgreSQL integer default column");
                    }
                    boolean identity = group.identity() && group.owner.equals(
                            new ColumnKey(plan.target(), column.getFieldName()));
                    if (identity) {
                        var kind = group.identityKind == SequenceDependencyGraph.ReferenceKind.IDENTITY_ALWAYS
                                ? ColumnSequenceMetadata.Kind.IDENTITY_ALWAYS : ColumnSequenceMetadata.Kind.IDENTITY_BY_DEFAULT;
                        var sequence = group.origin.definition().columnMetadata(group.origin.identity(), kind);
                        String expected = "GENERATED " + (kind == ColumnSequenceMetadata.Kind.IDENTITY_ALWAYS ? "ALWAYS" : "BY DEFAULT")
                                + " AS IDENTITY (" + sequence.optionsSql() + ")";
                        if (!expected.equals(facts.identity().value()) || !sequence.dataType()
                                .equals(facts.nativeType().value())
                                || facts.defaultExpression().state() != MetadataValue.State.ABSENT) {
                            throw new IllegalArgumentException("Identity column and dependency graph disagree");
                        }
                        column.setMetadataEvidence(new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(),
                                facts.defaultExpression(), facts.nullable(),
                                facts.numericPrecision(), facts.numericScale(), facts.characterLength(),
                                facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(),
                                MetadataValue.value(sequence)));
                        result.add(column);
                        continue;
                    }
                    if (facts.identity().state() != MetadataValue.State.ABSENT) {
                        throw new IllegalArgumentException(
                                "Default dependency cannot remove native identity semantics");
                    }
                    column.setDefaultValue(null);
                    column.setMetadataEvidence(
                            new ColumnMetadataEvidence(facts.provenance(), facts.nativeType(), MetadataValue.absent(),
                                    facts.nullable(),
                                    facts.numericPrecision(), facts.numericScale(), facts.characterLength(),
                                    facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(),
                                    MetadataValue.absent()));
                }
                result.add(column);
            }
            return List.copyOf(result);
        }

        /**
         * 返回标识列关联的序列名称映射。
         */
        @Override
        public Map<String, TableReadPlan.Relation> identitySequenceNames() {
            var result = new LinkedHashMap<String, TableReadPlan.Relation>();
            columns.forEach((column, group) -> {
                if (group.identity() && group.owner.equals(new ColumnKey(plan.target(), column))) {
                    result.put(column, new TableReadPlan.Relation(group.schema, group.name));
                }
            });
            return Map.copyOf(result);
        }

        /**
         * 说明当前插入是否允许覆盖系统生成的列值。
         */
        @Override
        public boolean overrideSystemValue() {
            return columns.entrySet().stream().anyMatch(entry ->
                    entry.getValue().identityKind == SequenceDependencyGraph.ReferenceKind.IDENTITY_ALWAYS
                            && entry.getValue().owner.equals(new ColumnKey(plan.target(), entry.getKey())));
        }

        /**
         * 返回当前产品需要的默认语句集合。
         */
        @Override
        public List<String> defaultStatements() {
            return columns.entrySet().stream()
                    .filter(entry -> !entry.getValue().identity() || !entry.getValue().owner.equals(
                            new ColumnKey(plan.target(), entry.getKey())))
                    .sorted(Map.Entry.comparingByKey()).map(entry ->
                            "ALTER TABLE " + relation(plan.target()) + " ALTER COLUMN " + quote(
                                    entry.getKey()) + " SET DEFAULT nextval('" + entry.getValue().qualified()
                                    .replace("'", "''") + "'::regclass)").toList();
        }

        /**
         * 准备写入器。
         */
        @Override
        public void prepareWriter(String schema, String table, List<String> fields) {
            requireActive();
            if (mode == Mode.STRUCTURE_ONLY || !prepared || !plan.target()
                    .equals(new TableReadPlan.Relation(schema, table)) || !fields.containsAll(columns.keySet())) {
                throw new IllegalStateException("Writer does not match the prepared sequence scope");
            }
        }

        /**
         * 在写入下一批数据前检查当前连接和目标状态。
         */
        @Override
        public void beforeBatch(Connection connection) throws SQLException {
            requireActive();
            if (mode == Mode.STRUCTURE_ONLY || !prepared || completed || finished) {
                throw new IllegalStateException("Shared sequence writer is not active");
            }
            lock(connection, plan.target(), "ROW EXCLUSIVE");
            if (tableOid(connection, plan.target(), true) != tableOids.get(plan.target())) {
                throw new IllegalStateException("Target dependency table was replaced");
            }
            for (var group : new HashSet<>(columns.values())) {
                if (sequenceOid(connection, group) != group.oid) {
                    throw new IllegalStateException("Target dependency sequence was replaced");
                }
            }
        }

        /**
         * 汇总当前表处理完成后的统计和结果。
         */
        @Override
        public void tableCompleted() {
            requireActive();
            if (!prepared || finished) {
                throw new IllegalStateException("Duplicate table completion");
            }
            finished = true;
        }
    }

    @FunctionalInterface
    private interface Work {

        /**
         * 在当前连接上调用序列搬运的工作回调。
         */
        void execute(JdbcBatchExecutor.WriteContext context) throws SQLException;
    }

    /**
     * 返回事务。
     */
    private void transaction(Work work) {
        try (var deadline = new PostgresConnectionDeadline(source, timeout)) {
            JdbcBatchExecutor.execute(source, context -> {
                work.execute(context);
                deadline.check();
                return null;
            }, (connection, ignored) -> deadline.check(), deadline::observe);
            deadline.check();
        } catch (SQLException expiredAfterClose) {
            throw new cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException();
        }
    }

    /**
     * 在当前连接上调用序列搬运的工作回调。
     */
    private void execute(JdbcBatchExecutor.WriteContext context, String sql) throws SQLException {
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql);
        for (var step : plan.steps()) {
            try (var statement = context.connection().createStatement()) {
                statement.setQueryTimeout(timeout);
                context.markWriteAttempted();
                statement.execute(step.sql());
            }
        }
    }

    /**
     * 获取当前操作所需的数据库或资源锁。
     */
    private void lock(Connection connection, TableReadPlan.Relation table, String mode) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute("LOCK TABLE " + relation(table) + " IN " + mode + " MODE");
        }
    }

    /**
     * 重新读取表对象标识，检查迁移作用域内物理表未被替换。
     */
    private void verifyTables(Connection connection) throws SQLException {
        for (var entry : tableOids.entrySet()) {
            if (tableOid(connection, entry.getKey(), true) != entry.getValue()) {
                throw new IllegalStateException("Target dependency relation changed");
            }
        }
    }

    /**
     * 通过系统目录查询当前表的对象标识。
     */
    private long tableOid(Connection connection, TableReadPlan.Relation table, boolean required) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT c.oid::bigint FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind='r' AND c.relpersistence='p'")) {
            query.setQueryTimeout(timeout);
            query.setString(1, table.schema());
            query.setString(2, table.table());
            try (var row = query.executeQuery()) {
                if (!row.next()) {
                    if (required) {
                        throw new SQLException("Target relation missing");
                    }
                    return 0;
                }
                return row.getLong(1);
            }
        }
    }

    /**
     * 查询序列在系统目录中的对象标识。
     */
    private long sequenceOid(Connection connection, Group group) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT c.oid::bigint,pg_catalog.pg_has_role(current_user,c.relowner,'USAGE') FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind='S' AND c.relpersistence='p'")) {
            query.setQueryTimeout(timeout);
            query.setString(1, group.schema);
            query.setString(2, group.name);
            try (var row = query.executeQuery()) {
                if (!row.next() || !row.getBoolean(2)) {
                    throw new IllegalArgumentException(
                            "Shared sequence alteration requires effective target ownership");
                }
                return row.getLong(1);
            }
        }
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    private ColumnSequenceMetadata observe(Connection connection, Group group) throws SQLException {
        var expected = group.sampled();
        try (var query = connection.prepareStatement(
                "SELECT pg_catalog.format_type(seqtypid,NULL),seqstart,seqincrement,seqmin,seqmax,seqcache,seqcycle FROM pg_catalog.pg_sequence WHERE seqrelid=?::oid")) {
            query.setQueryTimeout(timeout);
            query.setLong(1, group.oid);
            try (var row = query.executeQuery()) {
                if (!row.next() || !Objects.equals(row.getString(1), expected.dataType()) || row.getLong(
                        2) != expected.start() || row.getLong(3) != expected.increment()
                        || row.getLong(4) != expected.minimum() || row.getLong(5) != expected.maximum() || row.getLong(
                        6) != expected.cache() || row.getBoolean(7) != expected.cycle()) {
                    throw new IllegalArgumentException("Shared sequence attributes changed");
                }
            }
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var row = statement.executeQuery("SELECT last_value,is_called FROM " + group.qualified())) {
                if (!row.next()) {
                    throw new SQLException("Shared sequence position unavailable");
                }
                return new ColumnSequenceMetadata(ColumnSequenceMetadata.Kind.OWNED_DEFAULT, group.schema, group.name,
                        expected.dataType(), expected.start(), expected.increment(), expected.minimum(),
                        expected.maximum(), expected.cache(), expected.cycle(),
                        MetadataValue.value(new ColumnSequenceMetadata.Position(row.getLong(1), row.getBoolean(2))));
            }
        }
    }

    /**
     * 检查当前序列引用集合与计划中的列依赖一致。
     */
    private void verifyReferences(Connection connection, Group group) throws SQLException {
        var actual = new HashSet<ColumnKey>();
        try (var query = connection.prepareStatement(
                "SELECT n.nspname,c.relname,a.attname,pg_catalog.pg_get_expr(d.adbin,d.adrelid) FROM pg_catalog.pg_depend dep JOIN pg_catalog.pg_attrdef d ON d.oid=dep.objid JOIN pg_catalog.pg_class c ON c.oid=d.adrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum=d.adnum WHERE dep.classid='pg_catalog.pg_attrdef'::regclass AND dep.refclassid='pg_catalog.pg_class'::regclass AND dep.refobjid=?::oid")) {
            query.setQueryTimeout(timeout);
            query.setMaxRows(SequenceDependencyGraph.MAX_REFERENCES + 1);
            query.setLong(1, group.oid);
            try (var row = query.executeQuery()) {
                while (row.next()) {
                    if (!row.getString(4).matches("nextval\\('(?:[^']|'')+'::regclass\\)")) {
                        throw new IllegalStateException("Shared target default changed");
                    }
                    actual.add(new ColumnKey(new TableReadPlan.Relation(row.getString(1), row.getString(2)),
                            row.getString(3)));
                    if (actual.size() > SequenceDependencyGraph.MAX_REFERENCES) {
                        throw new IllegalStateException("Target dependency limit exceeded");
                    }
                }
            }
        }
        try (var query = connection.prepareStatement(
                "SELECT n.nspname,c.relname,a.attname,dep.deptype,a.attidentity FROM pg_catalog.pg_depend dep JOIN pg_catalog.pg_class c ON c.oid=dep.refobjid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum=dep.refobjsubid WHERE dep.classid='pg_catalog.pg_class'::regclass AND dep.refclassid='pg_catalog.pg_class'::regclass AND dep.objid=?::oid AND dep.refobjsubid>0 AND dep.deptype IN ('a','i')")) {
            query.setQueryTimeout(timeout);
            query.setLong(1, group.oid);
            try (var row = query.executeQuery()) {
                ColumnKey owner = row.next() ? new ColumnKey(
                        new TableReadPlan.Relation(row.getString(1), row.getString(2)), row.getString(3)) : null;
                if (!Objects.equals(owner, group.owner)) {
                    throw new IllegalStateException("Shared target ownership dependency changed");
                }
                if (owner != null) {
                    String expected = group.identityKind == SequenceDependencyGraph.ReferenceKind.IDENTITY_ALWAYS ? "a" : "d";
                    if (group.identity() ? !"i".equals(row.getString(4)) || !expected.equals(row.getString(5))
                            : !"a".equals(row.getString(4)) || !row.getString(5).isEmpty()) {
                        throw new IllegalStateException("Shared target native identity changed");
                    }
                    if (group.identity()) {
                        actual.add(owner);
                    }
                }
                if (row.next()) {
                    throw new IllegalStateException("Shared target has multiple owners");
                }
            }
        }
        if (!actual.equals(group.references)) {
            throw new IllegalStateException("Shared target references changed");
        }
        observe(connection, group);
    }

    /**
     * 按PostgreSQL方言转义并引用标识符。
     */
    private static String quote(String value) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL, value);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private static String relation(TableReadPlan.Relation value) {
        return RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, value.schema(), value.table());
    }
}

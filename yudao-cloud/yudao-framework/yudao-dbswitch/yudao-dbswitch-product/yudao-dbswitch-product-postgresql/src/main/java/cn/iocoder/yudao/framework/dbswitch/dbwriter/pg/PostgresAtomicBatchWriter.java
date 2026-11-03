package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 仅限 PG16 普通永久表；数据与旁路账本回执共享 JdbcBatchExecutor 的事务。 */
public final class PostgresAtomicBatchWriter implements IAtomicBatchWriter {

    private static final String LEDGER = "dbswitch_batch_receipt_v1";

    private static final String HEAD = "dbswitch_batch_head_v1";

    private static final String OPERATIONS = "dbswitch_batch_operation_v1";

    private static final String RECEIPT_COLUMNS = "scope,batch_key,target_schema,target_table,batch_column,row_column,"
            + "status_column,fields,payload_hash,row_count,receipt_id,committed_at,transaction_id,route_hash,relation_hash,materialized_hash";

    private final DataSource source;

    private final DatabasePostgresImpl dialect = new DatabasePostgresImpl();

    /**
     * 校验构造参数并初始化 {@code PostgresAtomicBatchWriter}。
     */
    public PostgresAtomicBatchWriter(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    /**
     * 在已有已授权 schema 中显式执行运行时旁账 DDL，绝不改变目标表。
     */
    @Override
    public void prepareLedger(String schema) {
        AtomicBatchTarget.identifier(schema);
        JdbcBatchExecutor.execute(source, context -> {
            Connection connection = context.connection();
            setup(connection);
            try (var lock = connection.prepareStatement(
                    "SELECT pg_catalog.pg_advisory_xact_lock(17420937,pg_catalog.hashtext(?))")) {
                lock.setString(1, schema);
                lock.execute();
            }
            String sql = "CREATE TABLE IF NOT EXISTS " + ledger(schema) + " ("
                    + "scope varchar(256) COLLATE \"C\" NOT NULL,batch_key varchar(256) COLLATE \"C\" NOT NULL,"
                    + "target_schema text NOT NULL,target_table text NOT NULL,batch_column text NOT NULL,"
                    + "row_column text NOT NULL,status_column text NOT NULL,fields text[] NOT NULL,"
                    + "payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),"
                    + "row_count bigint NOT NULL CHECK (row_count >= 0),receipt_id uuid NOT NULL UNIQUE,"
                    + "committed_at timestamptz NOT NULL,transaction_id text NOT NULL,"
                    + "route_hash varchar(64) NOT NULL,relation_hash varchar(64) NOT NULL,"
                    + "PRIMARY KEY(scope,batch_key))";
            try (var statement = connection.createStatement()) {
                context.markWriteAttempted();
                statement.execute(sql);
            }
            // 同名视图、非日志表或行级安全表不能作为可接受账本。
            requireStorage(connection, ledger(schema));
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + operations(schema) + " ("
                        + "scope varchar(256) COLLATE \"C\" NOT NULL,batch_key varchar(256) COLLATE \"C\" NOT NULL,"
                        + "target_schema text NOT NULL,target_table text NOT NULL,batch_column text NOT NULL,"
                        + "row_column text NOT NULL,status_column text NOT NULL,fields text[] NOT NULL,"
                        + "payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[a-f0-9]{64}$'),"
                        + "row_count bigint NOT NULL CHECK(row_count>=0),receipt_id uuid NOT NULL UNIQUE,"
                        + "committed_at timestamptz NOT NULL,transaction_id text NOT NULL,"
                        + "route_hash varchar(64) NOT NULL,relation_hash varchar(64) NOT NULL,"
                        + "operation_key varchar(256) COLLATE \"C\" NOT NULL,expected_hash varchar(64) NOT NULL CHECK(expected_hash ~ '^[a-f0-9]{64}$'),"
                        + "revision bigint NOT NULL CHECK(revision>0),PRIMARY KEY(scope,batch_key,operation_key),"
                        + "UNIQUE(scope,batch_key,revision),FOREIGN KEY(scope,batch_key) REFERENCES " + ledger(
                        schema) + "(scope,batch_key))");
                statement.execute("CREATE TABLE IF NOT EXISTS " + head(schema) + " ("
                        + "scope varchar(256) COLLATE \"C\" NOT NULL,batch_key varchar(256) COLLATE \"C\" NOT NULL,"
                        + "current_hash varchar(64) NOT NULL CHECK(current_hash ~ '^[a-f0-9]{64}$'),"
                        + "revision bigint NOT NULL CHECK(revision>=0),operation_key varchar(256) COLLATE \"C\","
                        + "PRIMARY KEY(scope,batch_key),FOREIGN KEY(scope,batch_key) REFERENCES " + ledger(
                        schema) + "(scope,batch_key),"
                        + "FOREIGN KEY(scope,batch_key,operation_key) REFERENCES " + operations(
                        schema) + "(scope,batch_key,operation_key),"
                        + "CHECK((revision=0 AND operation_key IS NULL) OR (revision>0 AND operation_key IS NOT NULL)))");
                statement.executeUpdate("INSERT INTO " + head(schema) + " (scope,batch_key,current_hash,revision) "
                        + "SELECT scope,batch_key,payload_hash,0 FROM " + ledger(
                        schema) + " ON CONFLICT(scope,batch_key) DO NOTHING");
            }
            requireStorage(connection, operations(schema));
            requireStorage(connection, head(schema));
            // 运行时账本的加法式升级，绝不为物化检查引入前签发的回执补造证据。
            try (var statement = connection.createStatement()) {
                for (String table : List.of(ledger(schema), operations(schema))) {
                    statement.execute("ALTER TABLE " + table
                            + " ADD COLUMN IF NOT EXISTS materialized_hash varchar(64) CHECK(materialized_hash ~ '^[a-f0-9]{64}$')");
                }
            }
            return null;
        });
    }

    /**
     * 检查产品、目标存储、列、技术类型及非部分的批次与行唯一性。
     */
    @Override
    public void preflight(AtomicBatchTarget target, List<String> fields) {
        Objects.requireNonNull(target);
        fields = AtomicBatchRequest.checkedFields(fields);
        List<String> frozenFields = fields;
        String invalid = JdbcBatchExecutor.execute(source, context -> {
            try {
                setup(context.connection());
                inspect(context.connection(), target, frozenFields);
                return null;
            } catch (InvalidTarget failure) {
                return failure.getMessage();
            }
        });
        if (invalid != null) {
            throw new IllegalArgumentException(invalid);
        }
    }

    /**
     * 返回当前提交。
     */
    @Override
    public AtomicBatchReceipt commit(AtomicBatchRequest request) {
        Objects.requireNonNull(request);
        Outcome outcome = JdbcBatchExecutor.execute(source, context -> {
            Connection connection = context.connection();
            setup(connection);
            lockData(connection, request.target(), true);
            int[] types;
            try {
                types = inspect(connection, request.target(), request.fields());
            } catch (InvalidTarget invalid) {
                return new Outcome(null, null, invalid.getMessage(), false);
            }
            requireStorage(connection, ledger(request.target().schema()));
            var witness = PostgresCommitWitness.begin(connection, request.target().schema(), request.target().table());
            if (witness == null || witness.receipt().relationFingerprint() == null) {
                throw new SQLException("Atomic batch transaction is not verifiable");
            }
            var existing = find(connection, request.target().schema(), request.key());
            if (existing.isPresent()) {
                return outcome(existing.get(), request, witness);
            }
            String sql = "INSERT INTO " + ledger(request.target().schema()) + " (" + RECEIPT_COLUMNS
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,pg_catalog.transaction_timestamp(),?,?,?,NULL) "
                    + "ON CONFLICT (scope,batch_key) DO NOTHING";
            int claimed;
            try (var statement = connection.prepareStatement(sql)) {
                bindClaim(statement, request, witness.receipt());
                context.markWriteAttempted();
                claimed = statement.executeUpdate();
            }
            var receipt = find(connection, request.target().schema(), request.key())
                    .orElseThrow(() -> new SQLException("Batch claim has no receipt"));
            if (claimed == 0) {
                return outcome(receipt, request, witness);
            }
            if (claimed != 1 || outcome(receipt, request, witness).conflict) {
                throw new SQLException("New batch receipt differs from its immutable request");
            }
            String insert = "INSERT INTO " + relation(request.target()) + " (" + columns(request.fields())
                    + ") VALUES (" + String.join(",", Collections.nCopies(request.fields().size(), "?")) + ")";
            if (request.rowCount() > 0) {
                JdbcBatchExecutor.executeBatch(context, insert, bindableRows(request.rows()), types);
            }
            // 键必须由此逻辑批次独占；账本缺失或已删除时，绝不向其行追加。
            try (var count = connection.prepareStatement("SELECT count(*) FROM " + relation(request.target())
                    + " WHERE " + q(request.target().batchColumn()) + "=?")) {
                count.setString(1, request.key().batchKey());
                try (var rows = count.executeQuery()) {
                    if (!rows.next() || rows.getLong(1) != request.rowCount()) {
                        throw new SQLException("Batch row count differs");
                    }
                }
            }
            witness.verifyTransaction(connection);
            String materialized = materializedHash(connection, request.target(), request.key(), request.rowCount());
            try (var statement = connection.prepareStatement("UPDATE " + ledger(request.target().schema())
                    + " SET materialized_hash=? WHERE scope=? AND batch_key=? AND materialized_hash IS NULL")) {
                statement.setString(1, materialized);
                statement.setString(2, request.key().scope());
                statement.setString(3, request.key().batchKey());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Batch materialization evidence was not persisted");
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO " + head(request.target().schema())
                    + " (scope,batch_key,current_hash,revision) VALUES(?,?,?,0)")) {
                statement.setString(1, request.key().scope());
                statement.setString(2, request.key().batchKey());
                statement.setString(3, request.payloadHash());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Batch head was not initialized");
                }
            }
            return new Outcome(find(connection, request.target().schema(), request.key()).orElseThrow(), witness, null,
                    false);
        }, (connection, result) -> {
            if (result.witness != null) {
                result.witness.verifyCommitted(connection);
            }
        });
        if (outcome.invalid != null) {
            throw new IllegalArgumentException(outcome.invalid);
        }
        if (outcome.conflict) {
            throw new AtomicBatchConflictException();
        }
        return outcome.receipt;
    }

    /**
     * 锁定当前批次头，检查预期哈希，原子替换该批次并追加历史。
     */
    @Override
    public AtomicBatchReceipt replace(AtomicBatchReplacement replacement) {
        Objects.requireNonNull(replacement);
        var request = replacement.replacement();
        ReplacementOutcome result = JdbcBatchExecutor.execute(source, context -> {
            Connection connection = context.connection();
            setup(connection);
            lockData(connection, request.target(), true);
            int[] types;
            try {
                types = inspect(connection, replacement.target(), request.fields());
            } catch (InvalidTarget invalid) {
                return new ReplacementOutcome(null, null, invalid.getMessage(), false, false);
            }
            var current = current(connection, replacement.target().schema(), replacement.key(), " FOR UPDATE");
            if (current.isEmpty()) {
                return new ReplacementOutcome(null, null, "A committed original batch is required", false, false);
            }
            var witness = PostgresCommitWitness.begin(connection, replacement.target().schema(),
                    replacement.target().table());
            if (witness == null) {
                throw new SQLException("Replacement transaction is not verifiable");
            }
            AtomicBatchReceipt currentReceipt = current.get().receipt;
            if (!currentReceipt.target().equals(replacement.target())
                    || !currentReceipt.transaction().relationFingerprint()
                    .equals(witness.receipt().relationFingerprint())
                    || !currentReceipt.transaction().routeFingerprint().equals(witness.receipt().routeFingerprint())) {
                return new ReplacementOutcome(null, witness, null, true, false);
            }
            var previous = operation(connection, replacement.target().schema(), replacement.key(),
                    replacement.operationKey());
            if (previous.isPresent()) {
                var prior = previous.get();
                boolean conflict = outcome(prior.receipt, request, witness).conflict
                        || !prior.expectedHash.equals(replacement.expectedCurrentPayloadHash());
                return new ReplacementOutcome(prior.receipt, witness, null, conflict, false);
            }
            if (!currentReceipt.payloadHash().equals(replacement.expectedCurrentPayloadHash())) {
                return new ReplacementOutcome(null, witness, null, false, true);
            }
            if (!currentReceipt.fields().equals(request.fields())) {
                return new ReplacementOutcome(null, witness, "Replacement must preserve the committed column layout",
                        false, false);
            }
            verifyMaterialization(connection, currentReceipt);
            try (var statement = connection.prepareStatement("DELETE FROM " + relation(replacement.target())
                    + " WHERE " + q(replacement.target().batchColumn()) + "=?")) {
                statement.setString(1, replacement.key().batchKey());
                context.markWriteAttempted();
                if (statement.executeLargeUpdate() != currentReceipt.rowCount()) {
                    throw new SQLException("Current batch row count no longer matches its receipt");
                }
            }
            String insert = "INSERT INTO " + relation(request.target()) + " (" + columns(
                    request.fields()) + ") VALUES ("
                    + String.join(",", Collections.nCopies(request.fields().size(), "?")) + ")";
            if (request.rowCount() > 0) {
                JdbcBatchExecutor.executeBatch(context, insert, bindableRows(request.rows()), types);
            }
            try (var statement = connection.prepareStatement("SELECT count(*) FROM " + relation(request.target())
                    + " WHERE " + q(request.target().batchColumn()) + "=?")) {
                statement.setString(1, request.key().batchKey());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getLong(1) != request.rowCount()) {
                        throw new SQLException("Replacement row count differs");
                    }
                }
            }
            long revision = Math.addExact(current.get().revision, 1);
            String materialized = materializedHash(connection, request.target(), request.key(), request.rowCount());
            String claim = "INSERT INTO " + operations(request.target().schema()) + " (" + RECEIPT_COLUMNS
                    + ",operation_key,expected_hash,revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,pg_catalog.transaction_timestamp(),?,?,?,?,?,?,?)";
            try (var statement = connection.prepareStatement(claim)) {
                bindClaim(statement, request, witness.receipt());
                statement.setString(15, materialized);
                statement.setString(16, replacement.operationKey());
                statement.setString(17, replacement.expectedCurrentPayloadHash());
                statement.setLong(18, revision);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Replacement receipt was not persisted");
                }
            }
            try (var statement = connection.prepareStatement("UPDATE " + head(request.target().schema())
                    + " SET current_hash=?,revision=?,operation_key=? WHERE scope=? AND batch_key=? AND current_hash=? AND revision=?")) {
                statement.setString(1, request.payloadHash());
                statement.setLong(2, revision);
                statement.setString(3, replacement.operationKey());
                statement.setString(4, request.key().scope());
                statement.setString(5, request.key().batchKey());
                statement.setString(6, replacement.expectedCurrentPayloadHash());
                statement.setLong(7, current.get().revision);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Replacement head CAS was lost");
                }
            }
            var receipt = operation(connection, request.target().schema(), request.key(), replacement.operationKey())
                    .orElseThrow(() -> new SQLException("Replacement has no receipt")).receipt;
            if (outcome(receipt, request, witness).conflict) {
                throw new SQLException("Replacement receipt differs from its request");
            }
            witness.verifyTransaction(connection);
            return new ReplacementOutcome(receipt, witness, null, false, false);
        }, (connection, pending) -> {
            if (pending.witness != null) {
                pending.witness.verifyCommitted(connection);
            }
        });
        if (result.invalid != null) {
            throw new IllegalArgumentException(result.invalid);
        }
        if (result.conflict) {
            throw new AtomicBatchConflictException();
        }
        if (result.stale) {
            throw new AtomicBatchStaleException();
        }
        return result.receipt;
    }

    /**
     * 当前修订；findReceipt 始终返回不可变原始提交。
     */
    @Override
    public Optional<AtomicBatchReceipt> findCurrentReceipt(AtomicBatchTarget target, AtomicBatchKey key) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(key);
        var result = JdbcBatchExecutor.execute(source, context -> {
            setup(context.connection());
            lockData(context.connection(), target, false);
            var found = current(context.connection(), target.schema(), key, "").map(Head::receipt);
            if (found.isPresent() && found.get().target().equals(target)) {
                verifyReceipt(context.connection(), found.get());
                verifyMaterialization(context.connection(), found.get());
            }
            return found;
        });
        if (result.isPresent() && !target.equals(result.get().target())) {
            throw new AtomicBatchConflictException();
        }
        return result;
    }

    /**
     * 按稳定操作键对可能已提交的替换进行对账。
     */
    @Override
    public Optional<AtomicBatchReceipt> findOperationReceipt(AtomicBatchTarget target, AtomicBatchKey key,
            String operationKey) {
        new AtomicBatchKey(key.scope(), operationKey);
        var result = JdbcBatchExecutor.execute(source, context -> {
            setup(context.connection());
            var found = operation(context.connection(), target.schema(), key, operationKey).map(Operation::receipt);
            if (found.isPresent() && found.get().target().equals(target)) {
                verifyReceipt(context.connection(), found.get());
            }
            return found;
        });
        if (result.isPresent() && !target.equals(result.get().target())) {
            throw new AtomicBatchConflictException();
        }
        return result;
    }

    /**
     * 查找提交回执。
     */
    @Override
    public Optional<AtomicBatchReceipt> findReceipt(AtomicBatchTarget target, AtomicBatchKey key) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(key);
        Optional<AtomicBatchReceipt> result = JdbcBatchExecutor.execute(source, context -> {
            setup(context.connection());
            requireStorage(context.connection(), ledger(target.schema()));
            var found = find(context.connection(), target.schema(), key);
            if (found.isPresent() && found.get().target().equals(target)) {
                String physical = requireStorage(context.connection(), relation(target));
                if (!physical.equals(found.get().transaction().relationFingerprint())) {
                    throw new SQLException("Batch target storage changed");
                }
                verifyRoute(context.connection(), found.get());
            }
            return found;
        });
        if (result.isPresent() && !result.get().target().equals(target)) {
            throw new AtomicBatchConflictException();
        }
        return result;
    }

    /**
     * 根据读取请求和产品能力规划有界数据批次。
     */
    @Override
    public AtomicBatchReadPlan planReadBatch(AtomicBatchTarget target, AtomicBatchKey key,
            List<String> fields, long afterRow, int limit) {
        return planReadBatch(target, key, fields, afterRow, limit, Set.of());
    }

    /**
     * 可选的固定状态白名单在数据库中、游标分页前应用。
     */
    @Override
    public AtomicBatchReadPlan planReadBatch(AtomicBatchTarget target, AtomicBatchKey key,
            List<String> fields, long afterRow, int limit, Set<String> allowedStatuses) {
        fields = AtomicBatchRequest.checkedFields(fields);
        if (afterRow < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid bounded batch page");
        }
        var statuses = Set.copyOf(allowedStatuses).stream().sorted().toList();
        if (!Set.of("PASS", "WARN", "REJECT").containsAll(statuses)) {
            throw new IllegalArgumentException("Unsupported batch status filter");
        }
        var receipt = findCurrentReceipt(target, key).orElseThrow(
                () -> new IllegalStateException("A committed batch receipt is required"));
        if (!new HashSet<>(receipt.fields()).containsAll(fields)) {
            throw new IllegalArgumentException("Only committed batch columns can be read");
        }
        var parameters = new ArrayList<Object>();
        parameters.add(key.batchKey());
        parameters.add(afterRow);
        parameters.addAll(statuses);
        return new AtomicBatchReadPlan(RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                selectSql(target, fields, limit, statuses.size()), target.schema()), parameters, receipt, fields,
                afterRow, limit, allowedStatuses);
    }

    /**
     * 读取已授权批次。
     */
    @Override
    public List<Map<String, Object>> readAuthorizedBatch(AtomicBatchReadPlan plan, String authorizedSql,
            String expectedPayloadHash) {
        Objects.requireNonNull(plan);
        var target = plan.receipt().target();
        if (!plan.receipt().payloadHash().equals(expectedPayloadHash)) {
            throw new AtomicBatchStaleException();
        }
        // 从类型化元数据重建必需边界，即使计划由调用方构造，也不信任其 SQL 文本。
        var mandatory = new AtomicBatchReadPlan(RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                selectSql(target, plan.fields(), plan.limit(), plan.allowedStatuses().size()), target.schema()),
                plan.parameters(), plan.receipt(), plan.fields(), plan.afterRow(), plan.limit(),
                plan.allowedStatuses());
        var base = queryBlock(mandatory.authorizationSql());
        var checked = RestrictedSelectGuard.restrictProjectionWithRowFilters(ProductTypeEnum.POSTGRESQL,
                authorizedSql, target.schema(), target.table(), plan.receipt().fields(),
                new LinkedHashSet<>(plan.fields()),
                List.of(base.getWhere().toString()));
        var block = queryBlock(checked.sql());
        block.setOrderBy(base.getOrderBy().clone());
        block.setLimit(base.getLimit().clone());
        String sql = com.alibaba.druid.sql.SQLUtils.toSQLString(block, com.alibaba.druid.DbType.postgresql);
        var finalCheck = RestrictedSelectGuard.restrictProjection(ProductTypeEnum.POSTGRESQL, sql, target.schema(),
                target.table(),
                plan.receipt().fields(), new LinkedHashSet<>(plan.fields()));
        return JdbcBatchExecutor.execute(source, context -> {
            var connection = context.connection();
            setup(connection);
            lockData(connection, target, false);
            var receipt = current(connection, target.schema(), plan.receipt().key(), " FOR SHARE").map(Head::receipt)
                    .orElseThrow(() -> new SQLException("Current batch receipt is missing"));
            if (!receipt.target().equals(target) || !receipt.receiptId().equals(plan.receipt().receiptId())
                    || !receipt.payloadHash().equals(expectedPayloadHash)) {
                throw new SQLException("Authorized batch revision changed");
            }
            verifyReceipt(connection, receipt);
            verifyMaterialization(connection, receipt);
            var result = new ArrayList<Map<String, Object>>();
            long bytes = 0;
            try (var statement = connection.prepareStatement(finalCheck.sql())) {
                statement.setMaxRows(plan.limit());
                try (var rows = statement.executeQuery()) {
                    var metadata = rows.getMetaData();
                    while (rows.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            Object value = cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.normalize(
                                    rows.getObject(i));
                            bytes = Math.addExact(bytes,
                                    cn.iocoder.yudao.framework.dbswitch.common.util.JdbcTypesUtils.getObjectSize(
                                            Types.OTHER, value));
                            if (bytes > 16L * 1024 * 1024) {
                                throw new SQLException("Authorized batch page exceeds byte budget");
                            }
                            row.put(metadata.getColumnLabel(i), value);
                        }
                        result.add(Collections.unmodifiableMap(row));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    /**
     * 将 SQL 解析为单一 SELECT 查询块。
     */
    private static com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock queryBlock(String sql) {
        var statement = (com.alibaba.druid.sql.ast.statement.SQLSelectStatement) com.alibaba.druid.sql.SQLUtils
                .parseSingleStatement(sql, com.alibaba.druid.DbType.postgresql);
        return (com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock) statement.getSelect().getQuery();
    }

    /**
     * 读取批次。
     */
    @Override
    public List<Map<String, Object>> readBatch(AtomicBatchTarget target, AtomicBatchKey key,
            List<String> fields, long afterRow, int limit) {
        fields = AtomicBatchRequest.checkedFields(fields);
        if (afterRow < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid bounded batch page");
        }
        List<String> selected = fields;
        String sql = selectSql(target, fields, limit);
        String checkedSql = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL, sql, target.schema())
                .getNormalizedSql();
        return JdbcBatchExecutor.execute(source, context -> {
            Connection connection = context.connection();
            setup(connection);
            lockData(connection, target, false);
            var receipt = current(connection, target.schema(), key, " FOR SHARE").map(Head::receipt)
                    .orElseThrow(() -> new IllegalStateException("A committed batch receipt is required"));
            if (!target.equals(receipt.target())) {
                throw new IllegalStateException("Batch target does not match its receipt");
            }
            if (!requireStorage(connection, relation(target)).equals(receipt.transaction().relationFingerprint())) {
                throw new IllegalStateException("Batch target storage changed");
            }
            verifyRoute(connection, receipt);
            verifyMaterialization(connection, receipt);
            if (!new HashSet<>(receipt.fields()).containsAll(selected)) {
                throw new IllegalArgumentException("Only committed batch columns can be read");
            }
            var result = new ArrayList<Map<String, Object>>();
            long bytes = 0;
            try (var statement = connection.prepareStatement(checkedSql)) {
                statement.setMaxRows(limit);
                statement.setString(1, key.batchKey());
                statement.setLong(2, afterRow);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 0; i < selected.size(); i++) {
                            Object value = cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.normalize(
                                    rows.getObject(i + 1));
                            bytes = Math.addExact(bytes,
                                    cn.iocoder.yudao.framework.dbswitch.common.util.JdbcTypesUtils.getObjectSize(
                                            Types.OTHER, value));
                            if (bytes > 16L * 1024 * 1024) {
                                throw new IllegalStateException("Batch page exceeds byte budget");
                            }
                            row.put(selected.get(i), value);
                        }
                        result.add(Collections.unmodifiableMap(row));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    /**
     * 根据当前输入构建并返回 {@code Outcome}。
     */
    private Outcome outcome(AtomicBatchReceipt receipt, AtomicBatchRequest request, PostgresCommitWitness witness) {
        boolean conflict = !receipt.target().equals(request.target()) || !receipt.payloadHash()
                .equals(request.payloadHash())
                || !receipt.fields().equals(request.fields()) || receipt.rowCount() != request.rowCount()
                || !receipt.transaction().relationFingerprint().equals(witness.receipt().relationFingerprint())
                || !receipt.transaction().routeFingerprint().equals(witness.receipt().routeFingerprint());
        return new Outcome(receipt, witness, null, conflict);
    }

    /**
     * 绑定抢占。
     */
    private void bindClaim(PreparedStatement statement, AtomicBatchRequest request,
            TransactionCommitReceipt witness) throws SQLException {
        AtomicBatchTarget target = request.target();
        statement.setString(1, request.key().scope());
        statement.setString(2, request.key().batchKey());
        statement.setString(3, target.schema());
        statement.setString(4, target.table());
        statement.setString(5, target.batchColumn());
        statement.setString(6, target.rowColumn());
        statement.setString(7, target.statusColumn());
        statement.setArray(8, statement.getConnection().createArrayOf("text", request.fields().toArray()));
        statement.setString(9, request.payloadHash());
        statement.setLong(10, request.rowCount());
        statement.setObject(11, UUID.randomUUID());
        statement.setString(12, witness.transactionId());
        statement.setString(13, witness.routeFingerprint());
        statement.setString(14, witness.relationFingerprint());
    }

    /**
     * 返回当前查找。
     */
    private Optional<AtomicBatchReceipt> find(Connection connection, String schema,
            AtomicBatchKey key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + RECEIPT_COLUMNS + " FROM " + ledger(schema)
                + " WHERE scope=? AND batch_key=?")) {
            statement.setString(1, key.scope());
            statement.setString(2, key.batchKey());
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                var receipt = receipt(rows, key);
                if (rows.next()) {
                    throw new SQLException("Batch receipt is ambiguous");
                }
                return Optional.of(receipt);
            }
        }
    }

    /**
     * 返回当前当前。
     */
    private Optional<Head> current(Connection connection, String schema, AtomicBatchKey key,
            String locking) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT current_hash,revision,operation_key FROM " + head(schema)
                        + " WHERE scope=? AND batch_key=?" + locking)) {
            statement.setString(1, key.scope());
            statement.setString(2, key.batchKey());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String hash = rows.getString(1), operationKey = rows.getString(3);
                long revision = rows.getLong(2);
                if (rows.next()) {
                    throw new SQLException("Batch head is ambiguous");
                }
                Optional<AtomicBatchReceipt> receipt;
                if (operationKey == null) {
                    receipt = find(connection, schema, key);
                } else {
                    var operation = operation(connection, schema, key, operationKey);
                    if (operation.isEmpty() || operation.get().revision != revision) {
                        throw new SQLException("Batch revision receipt differs");
                    }
                    receipt = operation.map(Operation::receipt);
                }
                if (receipt.isEmpty() || !hash.equals(receipt.get().payloadHash())) {
                    throw new SQLException("Batch head has no matching receipt");
                }
                return Optional.of(new Head(receipt.get(), revision));
            }
        }
    }

    /**
     * 返回当前操作。
     */
    private Optional<Operation> operation(Connection connection, String schema, AtomicBatchKey key,
            String operationKey) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT " + RECEIPT_COLUMNS + ",expected_hash,revision FROM " + operations(schema)
                        + " WHERE scope=? AND batch_key=? AND operation_key=?")) {
            statement.setString(1, key.scope());
            statement.setString(2, key.batchKey());
            statement.setString(3, operationKey);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                var result = new Operation(receipt(rows, key), rows.getString(17), rows.getLong(18));
                if (rows.next()) {
                    throw new SQLException("Replacement receipt is ambiguous");
                }
                return Optional.of(result);
            }
        }
    }

    /**
     * 返回提交回执。
     */
    private static AtomicBatchReceipt receipt(ResultSet rows, AtomicBatchKey key) throws SQLException {
        var target = new AtomicBatchTarget(rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
                rows.getString(7));
        java.sql.Array array = rows.getArray(8);
        List<String> fields;
        try {
            fields = List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
        return new AtomicBatchReceipt(target, key, fields, rows.getString(9), rows.getLong(10),
                rows.getObject(11, UUID.class), rows.getObject(12, OffsetDateTime.class).toInstant(),
                new TransactionCommitReceipt(1, "POSTGRESQL_XID8_V1", rows.getString(13), rows.getString(14),
                        rows.getString(15)), rows.getString(16));
    }

    /** 整表锁在证明期间同时阻止非受控 INSERT、DELETE 产生幻读及原地 UPDATE。 */
    private void lockData(Connection connection, AtomicBatchTarget target, boolean write) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(
                    "LOCK TABLE " + relation(target) + (write ? " IN SHARE ROW EXCLUSIVE MODE" : " IN SHARE MODE"));
        }
    }

    /**
     * 校验物化。
     */
    private void verifyMaterialization(Connection connection, AtomicBatchReceipt receipt) throws SQLException {
        if (receipt.materializedHash() == null || !receipt.materializedHash().equals(
                materializedHash(connection, receipt.target(), receipt.key(), receipt.rowCount()))) {
            throw new SQLException("Current batch materialization differs from its committed evidence");
        }
    }

    /** PG 二进制记录编码包含全部物理字段和类型，并保留精确存储值与空值。
     * 它有意区别于可移植输入载荷哈希，例如 timestamptz 会规范化时区偏移。
     * 在服务器端对每个二进制行生成哈希，避免 JDBC 驱动预取无界的非受控列。 */
    private String materializedHash(Connection connection, AtomicBatchTarget target, AtomicBatchKey key,
            long expectedCount) throws SQLException {
        if (expectedCount < 0 || expectedCount > 5000) {
            throw new SQLException("Materialized population exceeds row budget");
        }
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        digest.update("PG16_RECORD_SEND_SHA256_V1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        long count = 0, totalBytes = 0;
        try (var statement = connection.prepareStatement(
                "SELECT pg_catalog.sha256(pg_catalog.record_send(t)),pg_catalog.octet_length(pg_catalog.record_send(t)) FROM " + relation(
                        target)
                        + " t WHERE " + q(target.batchColumn()) + "=? ORDER BY " + q(target.rowColumn()))) {
            statement.setString(1, key.batchKey());
            statement.setMaxRows(5001);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (++count > expectedCount) {
                        throw new SQLException("Materialized batch row count differs");
                    }
                    long rowBytes = rows.getLong(2);
                    byte[] rowHash = rows.getBytes(1);
                    totalBytes = Math.addExact(totalBytes, rowBytes);
                    if (rowBytes < 0 || totalBytes > 32L * 1024 * 1024 || rowHash == null || rowHash.length != 32) {
                        throw new SQLException("Materialization exceeds byte budget or has invalid evidence");
                    }
                    digest.update(rowHash);
                    digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(rowBytes).array());
                }
            }
        }
        if (count != expectedCount) {
            throw new SQLException("Materialized batch row count differs");
        }
        digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(count).array());
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 校验提交回执。
     */
    private void verifyReceipt(Connection connection, AtomicBatchReceipt receipt) throws SQLException {
        if (!requireStorage(connection, relation(receipt.target())).equals(
                receipt.transaction().relationFingerprint())) {
            throw new SQLException("Batch target storage changed");
        }
        verifyRoute(connection, receipt);
    }

    /**
     * 与 PostgresUpsertWriter 使用同一 PG 原生绑定修正；JDBC 元数据会丢失时区语义。
     */
    private int[] inspect(Connection connection, AtomicBatchTarget target, List<String> fields) throws SQLException {
        requireStorage(connection, relation(target));
        if (!fields.containsAll(List.of(target.batchColumn(), target.rowColumn(), target.statusColumn()))) {
            throw new InvalidTarget("All three technical columns must be present");
        }
        Map<String, Column> available = new HashMap<>();
        String columnSql = "SELECT a.attname,t.typname,a.attnotnull,a.atttypmod,a.attgenerated,a.attidentity,coalesce(c.collisdeterministic,true) "
                + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid "
                + "LEFT JOIN pg_catalog.pg_collation c ON c.oid=a.attcollation "
                + "WHERE a.attrelid=pg_catalog.to_regclass(?) AND a.attnum>0 AND NOT a.attisdropped";
        try (var statement = connection.prepareStatement(columnSql)) {
            statement.setString(1, relation(target));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    available.put(rows.getString(1), new Column(rows.getString(2), rows.getBoolean(3),
                            rows.getInt(4), rows.getString(5), rows.getString(6), rows.getBoolean(7)));
                }
            }
        }
        for (String field : fields) {
            var column = available.get(field);
            if (column == null || !column.generated.isEmpty() || "a".equals(column.identity)) {
                throw new InvalidTarget("A payload column is missing, generated or identity-always");
            }
        }
        Column batch = available.get(target.batchColumn()), row = available.get(
                target.rowColumn()), status = available.get(target.statusColumn());
        if (!textColumn(batch, 256) || !row.notNull || !"int8".equals(row.type) || !textColumn(status, 64)
                || !batch.deterministic || !status.deterministic) {
            throw new InvalidTarget("Technical columns require NOT NULL text/varchar(256+), bigint, text/varchar(64+)");
        }
        String uniqueSql = "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_index i WHERE i.indrelid=pg_catalog.to_regclass(?) "
                + "AND i.indisunique AND i.indisvalid AND i.indisready AND i.indimmediate AND i.indpred IS NULL "
                + "AND i.indexprs IS NULL AND i.indnkeyatts=2 AND (SELECT count(*) FROM unnest(i.indkey::smallint[]) "
                + "WITH ORDINALITY k(num,pos) JOIN pg_catalog.pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.num "
                + "WHERE k.pos<=i.indnkeyatts AND a.attname IN (?,?))=2), "
                + "EXISTS (SELECT 1 FROM pg_catalog.pg_trigger WHERE tgrelid=pg_catalog.to_regclass(?) AND NOT tgisinternal AND tgenabled<>'D'), "
                + "EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite WHERE ev_class=pg_catalog.to_regclass(?))";
        try (var statement = connection.prepareStatement(uniqueSql)) {
            statement.setString(1, relation(target));
            statement.setString(2, target.batchColumn());
            statement.setString(3, target.rowColumn());
            statement.setString(4, relation(target));
            statement.setString(5, relation(target));
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) {
                    throw new InvalidTarget("A non-partial immediate UNIQUE(batchColumn,rowColumn) is required");
                }
                if (rows.getBoolean(2) || rows.getBoolean(3)) {
                    throw new InvalidTarget("Enabled user triggers and rewrite rules are not supported");
                }
            }
        }
        int[] types = new int[fields.size()];
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT " + columns(fields)
                + " FROM " + relation(target) + " WHERE false")) {
            var metadata = rows.getMetaData();
            for (int i = 0; i < fields.size(); i++) {
                // 与 PostgresUpsertWriter 使用同一 PG 原生绑定修正；JDBC 元数据会丢失时区语义。
                types[i] = switch (available.get(fields.get(i)).type) {
                    case "timestamptz" -> Types.TIMESTAMP_WITH_TIMEZONE;
                    case "timetz" -> Types.TIME_WITH_TIMEZONE;
                    default -> metadata.getColumnType(i + 1);
                };
            }
        }
        return types;
    }

    /**
     * 判断当前目标列是否为受支持的非空文本列。
     */
    private static boolean textColumn(Column column, int minimum) {
        return column != null && column.notNull && ("text".equals(column.type)
                || "varchar".equals(column.type) && (column.modifier == -1 || column.modifier >= minimum + 4));
    }

    /**
     * 校验 PostgreSQL 产品、版本和事务条件后准备批次写入。
     */
    private static void setup(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        if (!"PostgreSQL".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 16) {
            throw new InvalidTarget("Atomic batches are verified only for PostgreSQL 16");
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
            statement.execute("SET LOCAL statement_timeout='30s'");
            statement.execute("SET LOCAL lock_timeout='5s'");
            statement.execute("SET LOCAL synchronous_commit=on");
        }
    }

    /**
     * 读取并检查当前关系的存储结构指纹。
     */
    private static String requireStorage(Connection connection, String reference) throws SQLException {
        String value = PostgresRelationIdentity.storageFingerprint(connection, reference, true);
        if (value == null) {
            throw new InvalidTarget(
                    "Only ordinary permanent tables without RLS/inheritance/partitioning are supported");
        }
        return value;
    }

    /**
     * 检查数据行中各值能否按当前字段类型精确绑定。
     */
    private static List<Object[]> bindableRows(List<Object[]> rows) {
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] instanceof java.time.Instant instant) {
                    row[i] = OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
                }
            }
        }
        return rows;
    }

    /**
     * 校验连接路由。
     */
    private static void verifyRoute(Connection connection, AtomicBatchReceipt receipt) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT "
                + cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute.COLUMNS
                + cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute.FROM)) {
            if (!rows.next() || !receipt.transaction().routeFingerprint().equals(
                    cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute.fingerprint(rows,
                            1)) || rows.next()) {
                throw new SQLException("Batch receipt connection route changed");
            }
        }
    }

    /**
     * 返回查询 SQL。
     */
    private String selectSql(AtomicBatchTarget target, List<String> fields, int limit) {
        return selectSql(target, fields, limit, 0);
    }

    /**
     * 返回查询 SQL。
     */
    private String selectSql(AtomicBatchTarget target, List<String> fields, int limit, int statusCount) {
        return "SELECT " + columns(fields) + " FROM " + relation(target) + " WHERE "
                + q(target.batchColumn()) + "=? AND " + q(target.rowColumn()) + ">?"
                + (statusCount == 0 ? "" : " AND " + q(target.statusColumn()) + " IN (" + String.join(",",
                Collections.nCopies(statusCount, "?")) + ")")
                + " ORDER BY "
                + q(target.rowColumn()) + " ASC LIMIT " + limit;
    }

    /**
     * 按PostgreSQL方言转义并引用标识符。
     */
    private String q(String name) {
        return dialect.quoteIdentifier(name);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private String relation(AtomicBatchTarget target) {
        return q(target.schema()) + "." + q(target.table());
    }

    /**
     * 返回当前账本。
     */
    private String ledger(String schema) {
        return q(schema) + "." + q(LEDGER);
    }

    /**
     * 返回当前首部。
     */
    private String head(String schema) {
        return q(schema) + "." + q(HEAD);
    }

    /**
     * 返回当前操作集合。
     */
    private String operations(String schema) {
        return q(schema) + "." + q(OPERATIONS);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    private String columns(List<String> fields) {
        return fields.stream().map(this::q).collect(Collectors.joining(","));
    }

    private record Column(String type, boolean notNull, int modifier, String generated, String identity,
                          boolean deterministic) {

    }

    private record Outcome(AtomicBatchReceipt receipt, PostgresCommitWitness witness, String invalid,
                           boolean conflict) {

    }

    private record ReplacementOutcome(AtomicBatchReceipt receipt, PostgresCommitWitness witness, String invalid,
                                      boolean conflict, boolean stale) {

    }

    private record Head(AtomicBatchReceipt receipt, long revision) {

    }

    private record Operation(AtomicBatchReceipt receipt, String expectedHash, long revision) {

    }

    private static final class InvalidTarget extends IllegalArgumentException {

        /**
         * 初始化 {@code InvalidTarget}及其父类状态。
         */
        InvalidTarget(String message) {
            super(message);
        }
    }
}

package cn.iocoder.yudao.framework.dbswitch.data.recovery;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** 不可变的加密分页载荷与经认证的 CAS 状态；此处不访问目标、不接管租约，
 * 不安装模式，也不解决 UNKNOWN 状态；逻辑目标始终保持全局占用。 */
public final class PostgresTableRewriteStore implements TableRewriteStore {

    @FunctionalInterface
    public interface OwnershipGuard {

        /** 仅在扫描整个范围时 key 为 null；校验器仍检查当前归属和租约。 */
        void check(Connection connection, TableRewriteKey key) throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {

        /**
         * 在当前控制面事务连接上调用持久化工作回调。
         */
        T run(Connection connection) throws Exception;
    }

    private record Header(TableRewriteKey key, long revision, String owner, TableRewriteRecord.Phase phase,
                          TableRewriteIdentity identity, String hash) {

    }

    private final DataSource control;

    private final String relation, pageRelation, scope;

    private final RecoveryCrypto crypto;

    private final TableRewriteLimits limits;

    private final int timeout;

    private final OwnershipGuard guard;

    /**
     * 校验构造参数并初始化 {@code PostgresTableRewriteStore}。
     */
    public PostgresTableRewriteStore(DataSource control, String relation, String pageRelation, String scope,
            String currentKeyId,
            Map<String, ? extends SecretKey> keys, TableRewriteLimits limits, int timeout, OwnershipGuard guard) {
        this.control = Objects.requireNonNull(control);
        this.relation = relation(relation);
        this.pageRelation = relation(pageRelation);
        TableRewriteKey.requireFingerprint(scope);
        this.scope = scope;
        this.crypto = new RecoveryCrypto(currentKeyId, keys);
        this.limits = Objects.requireNonNull(limits);
        this.guard = Objects.requireNonNull(guard);
        if (timeout < 1 || timeout > 3600 || this.relation.equals(this.pageRelation)) {
            throw invalid();
        }
        this.timeout = timeout;
    }

    /**
     * 在短控制面事务中读取当前表重写记录。
     */
    @Override
    public Optional<TableRewriteRecord> read(TableRewriteKey key) {
        check(key);
        return transaction(key, c -> select(c, key, false));
    }

    /**
     * 返回 PREPARED 前，原子持久化完整清单及每个加密页面。
     */
    @Override
    public TableRewriteRecord prepare(TableRewriteKey key, String owner, TableRewriteMaterial material) {
        check(key);
        GroupRewriteRecord.owner(owner);
        Objects.requireNonNull(material);
        var verified = TableRewriteMaterial.decode(material.manifest(),
                material.pages().stream().map(TableRewriteMaterial.Page::encoded).toList(), limits);
        var sealed = crypto.seal(verified.manifest(), id -> aad(key, "manifest", verified.hash(), id));
        return transaction(key, c -> {
            var prior = select(c, key, true);
            if (prior.isPresent()) {
                var p = prior.get();
                if (p.revision() == 0 && p.phase() == TableRewriteRecord.Phase.PREPARED && p.ownerToken()
                        .equals(owner) && p.material().hash().equals(verified.hash())) {
                    return p;
                }
                throw conflict();
            }
            var record = new TableRewriteRecord(key, 0, owner, TableRewriteRecord.Phase.PREPARED, null, verified);
            String sql = "INSERT INTO " + relation + "(scope,logical_resource,operation,revision,owner_token,phase,material_hash,key_id,nonce,manifest,state_key_id,state_mac) VALUES(?,?,?,0,?,'PREPARED',?,?,?,?,?,?)";
            try (var s = statement(c, sql)) {
                bind(s, key, 1);
                s.setString(4, owner);
                s.setString(5, verified.hash());
                s.setString(6, sealed.keyId());
                s.setBytes(7, sealed.nonce());
                s.setBytes(8, sealed.payload());
                s.setString(9, crypto.currentKeyId());
                s.setBytes(10, stateMac(record, crypto.currentKeyId()));
                if (s.executeUpdate() != 1) {
                    throw conflict();
                }
            }
            for (var page : verified.pages()) {
                String pageId = page.image().name() + ":" + page.ordinal() + ":" + page.rows() + ":" + page.bytes();
                var encrypted = crypto.seal(page.encoded(), id -> aad(key, pageId, page.hash(), id));
                try (var s = statement(c,
                        "INSERT INTO " + pageRelation + "(scope,logical_resource,operation,image,ordinal,row_count,plain_bytes,page_hash,key_id,nonce,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    bind(s, key, 1);
                    s.setString(4, page.image().name());
                    s.setInt(5, page.ordinal());
                    s.setInt(6, page.rows());
                    s.setInt(7, page.bytes());
                    s.setString(8, page.hash());
                    s.setString(9, encrypted.keyId());
                    s.setBytes(10, encrypted.nonce());
                    s.setBytes(11, encrypted.payload());
                    if (s.executeUpdate() != 1) {
                        throw conflict();
                    }
                }
            }
            return record;
        });
    }

    /**
     * 返回当前迁移。
     */
    @Override
    public Optional<TableRewriteRecord> transition(TableRewriteKey key, String owner, long revision,
            TableRewriteRecord.Phase expected, TableRewriteRecord.Phase next, TableRewriteIdentity identity) {
        check(key);
        GroupRewriteRecord.owner(owner);
        if (revision < 0 || !TableRewriteRecord.permits(expected, next)) {
            throw invalid();
        }
        return transaction(key, c -> {
            var prior = select(c, key, true);
            if (prior.isEmpty() || prior.get().revision() != revision || prior.get().phase() != expected || !prior.get()
                    .ownerToken().equals(owner)) {
                return Optional.empty();
            }
            var changed = prior.get().advance(next, identity);
            update(c, prior.get(), changed);
            return Optional.of(changed);
        });
    }

    /**
     * 要求旧执行器已排空的可信证明；UNKNOWN 或有歧义的 INTENT 绝不自动认领。
     */
    @Override
    public Optional<TableRewriteRecord> claimRecovery(TableRewriteKey key, long revision, String newOwner) {
        check(key);
        GroupRewriteRecord.owner(newOwner);
        if (revision < 0) {
            throw invalid();
        }
        return transaction(key, c -> {
            var prior = select(c, key, true);
            if (prior.isEmpty() || prior.get().revision() != revision || !prior.get().permitsAutomaticReconciliation()
                    || prior.get().ownerToken().equals(newOwner)) {
                return Optional.empty();
            }
            var changed = prior.get().claimed(newOwner);
            update(c, prior.get(), changed);
            return Optional.of(changed);
        });
    }

    /**
     * 认证扫描到的所有记录头，包括 APPLIED。空页面仍可能含 nextAfter。
     */
    @Override
    public PendingPage pending(String requestedScope, String logicalResource, String afterOperation, int pageSize) {
        if (!scope.equals(requestedScope) || pageSize < 1 || pageSize > 256) {
            throw invalid();
        }
        TableRewriteKey.requireFingerprint(logicalResource);
        if (afterOperation != null) {
            TableRewriteKey.requireFingerprint(afterOperation);
        }
        return transaction(null, c -> {
            String sql = "SELECT operation,revision,owner_token,phase,new_identity_format,new_identity_document,material_hash,state_key_id,state_mac FROM " + relation
                    + " WHERE scope=? AND logical_resource=? AND operation>? ORDER BY operation LIMIT ?";
            var pending = new ArrayList<TableRewriteKey>();
            String last = null;
            int count = 0;
            try (var s = statement(c, sql)) {
                s.setString(1, scope);
                s.setString(2, logicalResource);
                s.setString(3, afterOperation == null ? "" : afterOperation);
                s.setInt(4, pageSize);
                s.setMaxRows(pageSize);
                try (var rows = s.executeQuery()) {
                    while (rows.next()) {
                        var key = new TableRewriteKey(scope, logicalResource, rows.getString(1));
                        guard.check(c, key);
                        var header = header(key, rows, 2);
                        verify(header, rows.getString(8), rows.getBytes(9));
                        if (header.phase() != TableRewriteRecord.Phase.APPLIED) {
                            pending.add(key);
                        }
                        last = key.operation();
                        count++;
                    }
                }
            }
            return new PendingPage(pending, count == pageSize ? last : null);
        });
    }

    /**
     * 按当前键和预期状态更新持久化记录。
     */
    private void update(Connection c, TableRewriteRecord before, TableRewriteRecord after) throws SQLException {
        String sql = "UPDATE " + relation + " SET revision=?,owner_token=?,phase=?,new_identity_format=?,new_identity_document=?,state_key_id=?,state_mac=?,updated_at=clock_timestamp() WHERE scope=? AND logical_resource=? AND operation=? AND revision=? AND owner_token=? AND phase=?";
        try (var s = statement(c, sql)) {
            s.setLong(1, after.revision());
            s.setString(2, after.ownerToken());
            s.setString(3, after.phase().name());
            s.setString(4, after.newIdentity() == null ? null : after.newIdentity().format());
            s.setString(5, after.newIdentity() == null ? null : after.newIdentity().document());
            s.setString(6, crypto.currentKeyId());
            s.setBytes(7, stateMac(after, crypto.currentKeyId()));
            bind(s, after.key(), 8);
            s.setLong(11, before.revision());
            s.setString(12, before.ownerToken());
            s.setString(13, before.phase().name());
            if (s.executeUpdate() != 1) {
                throw conflict();
            }
        }
    }

    /**
     * 按当前查询条件读取目标记录或数据行。
     */
    private Optional<TableRewriteRecord> select(Connection c, TableRewriteKey key, boolean lock) throws Exception {
        String sql = "SELECT revision,owner_token,phase,new_identity_format,new_identity_document,material_hash,state_key_id,state_mac,key_id,nonce,octet_length(manifest),CASE WHEN octet_length(manifest)<=? THEN manifest ELSE NULL END FROM " + relation
                + " WHERE scope=? AND logical_resource=? AND operation=?" + (lock ? " FOR UPDATE" : "");
        try (var s = statement(c, sql)) {
            s.setInt(1, TableRewriteMaterial.MAX_MANIFEST_BYTES + 16);
            bind(s, key, 2);
            s.setMaxRows(2);
            try (var rows = s.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                var header = header(key, rows, 1);
                verify(header, rows.getString(7), rows.getBytes(8));
                int size = rows.getInt(11);
                byte[] encrypted = rows.getBytes(12);
                if (size < 16 || size > TableRewriteMaterial.MAX_MANIFEST_BYTES + 16 || encrypted == null || encrypted.length != size) {
                    throw invalid();
                }
                String keyId = rows.getString(9);
                byte[] manifest = crypto.crypt(Cipher.DECRYPT_MODE, keyId, rows.getBytes(10),
                        aad(key, "manifest", header.hash(), keyId), encrypted);
                if (!header.hash().equals(GroupRewriteMaterial.sha256(manifest)) || rows.next()) {
                    throw invalid();
                }
                var material = TableRewriteMaterial.decode(manifest, pages(c, key, manifest.length), limits);
                return Optional.of(new TableRewriteRecord(key, header.revision(), header.owner(), header.phase(),
                        header.identity(), material));
            }
        }
    }

    /**
     * 将当前数据按受限大小划分为分页载荷。
     */
    private List<byte[]> pages(Connection c, TableRewriteKey key, long bytes) throws Exception {
        String sql = "SELECT image,ordinal,row_count,plain_bytes,page_hash,key_id,nonce,octet_length(payload),CASE WHEN octet_length(payload)<=? THEN payload ELSE NULL END FROM " + pageRelation
                + " WHERE scope=? AND logical_resource=? AND operation=? ORDER BY CASE image WHEN 'BEFORE' THEN 0 ELSE 1 END,ordinal LIMIT ?";
        var result = new ArrayList<byte[]>();
        try (var s = statement(c, sql)) {
            s.setLong(1, limits.page().maxBatchBytes() + 16);
            bind(s, key, 2);
            s.setInt(5, limits.maxPages() + 1);
            s.setMaxRows(limits.maxPages() + 1);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    if (result.size() >= limits.maxPages()) {
                        throw invalid();
                    }
                    String image = rows.getString(1);
                    int ordinal = rows.getInt(2), count = rows.getInt(3), plain = rows.getInt(4);
                    String hash = rows.getString(5), keyId = rows.getString(6);
                    int length = rows.getInt(8);
                    byte[] encrypted = rows.getBytes(9);
                    if (!Set.of("BEFORE", "AFTER").contains(image) || ordinal < 0 || count < 1 || count > limits.page()
                            .maxRows() || plain < 8 || plain > limits.page().maxBatchBytes()
                            || length != plain + 16 || encrypted == null || encrypted.length != length) {
                        throw invalid();
                    }
                    bytes = Math.addExact(bytes, plain);
                    if (bytes > limits.maxMaterialBytes()) {
                        throw invalid();
                    }
                    byte[] page = crypto.crypt(Cipher.DECRYPT_MODE, keyId, rows.getBytes(7),
                            aad(key, image + ":" + ordinal + ":" + count + ":" + plain, hash, keyId), encrypted);
                    if (!GroupRewriteMaterial.sha256(page).equals(hash)) {
                        throw invalid();
                    }
                    result.add(page);
                }
            }
        }
        return result;
    }

    /**
     * 返回表头。
     */
    private static Header header(TableRewriteKey key, ResultSet rows, int start) throws SQLException {
        long revision = rows.getLong(start);
        String owner = rows.getString(start + 1);
        var phase = TableRewriteRecord.Phase.valueOf(rows.getString(start + 2));
        String format = rows.getString(start + 3), document = rows.getString(start + 4), hash = rows.getString(
                start + 5);
        if ((format == null) != (document == null) || revision < 0) {
            throw invalid();
        }
        GroupRewriteRecord.owner(owner);
        TableRewriteKey.requireFingerprint(hash);
        var identity = format == null ? null : new TableRewriteIdentity(format, document);
        if (TableRewriteRecord.requiresNewIdentity(phase) != (identity != null)) {
            throw invalid();
        }
        return new Header(key, revision, owner, phase, identity, hash);
    }

    /**
     * 使用指定密钥计算持久化状态的消息认证码。
     */
    private byte[] stateMac(TableRewriteRecord value, String keyId) {
        return stateMac(
                new Header(value.key(), value.revision(), value.ownerToken(), value.phase(), value.newIdentity(),
                        value.material().hash()), keyId);
    }

    /**
     * 使用指定密钥计算持久化状态的消息认证码。
     */
    private byte[] stateMac(Header h, String keyId) {
        String identity = h.identity() == null ? "" : h.identity().fingerprint();
        return crypto.stateMac(keyId, "DBSWITCH_TABLE_STATE_KEY_V1",
                String.join("\0", "DBSWITCH_TABLE_STATE_V1", h.key().scope(), h.key().logicalResource(),
                        h.key().operation(), Long.toString(h.revision()), h.owner(), h.phase().name(), h.hash(),
                        identity, keyId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 检查时、键编号、实际是否满足当前操作的约定。
     */
    private void verify(Header h, String keyId, byte[] actual) {
        if (actual == null || !MessageDigest.isEqual(stateMac(h, keyId), actual)) {
            throw new IllegalStateException("Recovery state authentication failed");
        }
    }

    /**
     * 生成加密载荷使用的附加认证数据。
     */
    private static byte[] aad(TableRewriteKey key, String part, String hash, String keyId) {
        TableRewriteKey.requireFingerprint(hash);
        return String.join("\0", "DBSWITCH_TABLE_PAGE_V1", key.scope(), key.logicalResource(), key.operation(), part,
                hash, keyId).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 返回事务。
     */
    private <T> T transaction(TableRewriteKey key, Work<T> work) {
        try (var c = control.getConnection()) {
            if (!c.getAutoCommit()) {
                throw invalid();
            }
            c.setAutoCommit(false);
            boolean committed = false;
            try {
                try (var s = c.createStatement()) {
                    s.setQueryTimeout(timeout);
                    s.execute("SET LOCAL statement_timeout=" + Math.multiplyExact(timeout, 1000));
                    s.execute("SET LOCAL lock_timeout=" + Math.multiplyExact(Math.min(timeout, 5), 1000));
                }
                guard.check(c, key);
                T result = work.run(c);
                c.commit();
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {/* 尚未证明记录不存在 */}
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Table recovery store result is unavailable; reconcile durable state before target writes");
        }
    }

    /**
     * 返回语句。
     */
    private PreparedStatement statement(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        try {
            s.setQueryTimeout(timeout);
            return s;
        } catch (SQLException failure) {
            try {
                s.close();
            } catch (SQLException ignored) {
            }
            throw failure;
        }
    }

    /**
     * 检查键是否满足当前操作的约定。
     */
    private void check(TableRewriteKey key) {
        if (key == null || !scope.equals(key.scope())) {
            throw invalid();
        }
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    private static void bind(PreparedStatement s, TableRewriteKey key, int offset) throws SQLException {
        s.setString(offset, key.scope());
        s.setString(offset + 1, key.logicalResource());
        s.setString(offset + 2, key.operation());
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private static String relation(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,62}(\\.[a-z][a-z0-9_-]{0,62})?")) {
            throw invalid();
        }
        return Arrays.stream(value.split("\\.")).map(p -> "\"" + p + "\"")
                .collect(java.util.stream.Collectors.joining("."));
    }

    /** 仅提供候选 DDL；平台部署须提供自己的编号迁移及固定标识外键。 */
    public static List<String> schemaStatements(String headerName, String pageName) {
        String header = relation(headerName), pages = relation(pageName);
        if (header.equals(pages)) {
            throw invalid();
        }
        String index = "dbswitch_table_pending_" + GroupRewriteMaterial.sha256(
                headerName.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        return List.of(
                "CREATE TABLE " + header + " (scope varchar(64) NOT NULL,logical_resource varchar(64) NOT NULL,operation varchar(64) NOT NULL,revision bigint NOT NULL CHECK(revision>=0),owner_token varchar(128) NOT NULL,phase varchar(32) NOT NULL CHECK(phase IN ('PREPARED','DROP_INTENT','DROP_CONFIRMED','CREATE_INTENT','CREATE_CONFIRMED','RESTORE_INTENT','APPLIED','DROP_UNKNOWN','CREATE_UNKNOWN')),material_hash varchar(64) NOT NULL,new_identity_format varchar(64),new_identity_document text CHECK(octet_length(new_identity_document)<=16384),key_id varchar(64) NOT NULL,nonce bytea NOT NULL CHECK(octet_length(nonce)=12),manifest bytea NOT NULL CHECK(octet_length(manifest) BETWEEN 16 AND 2097168),state_key_id varchar(64) NOT NULL,state_mac bytea NOT NULL CHECK(octet_length(state_mac)=32),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),CHECK((new_identity_format IS NULL)=(new_identity_document IS NULL)),PRIMARY KEY(scope,logical_resource,operation))",
                "CREATE UNIQUE INDEX \"" + index + "\" ON " + header + "(logical_resource) WHERE phase<>'APPLIED'",
                "CREATE TABLE " + pages + " (scope varchar(64) NOT NULL,logical_resource varchar(64) NOT NULL,operation varchar(64) NOT NULL,image varchar(8) NOT NULL CHECK(image IN ('BEFORE','AFTER')),ordinal integer NOT NULL CHECK(ordinal>=0),row_count integer NOT NULL CHECK(row_count>0),plain_bytes integer NOT NULL CHECK(plain_bytes BETWEEN 8 AND 67108864),page_hash varchar(64) NOT NULL,key_id varchar(64) NOT NULL,nonce bytea NOT NULL CHECK(octet_length(nonce)=12),payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 24 AND 67108880),PRIMARY KEY(scope,logical_resource,operation,image,ordinal),FOREIGN KEY(scope,logical_resource,operation) REFERENCES " + header + "(scope,logical_resource,operation))");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid table recovery store input or capacity");
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalStateException}。
     */
    private static IllegalStateException conflict() {
        return new IllegalStateException("Table recovery generation already requires reconciliation");
    }
}

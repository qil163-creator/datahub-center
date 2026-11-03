package cn.iocoder.yudao.framework.dbswitch.data.recovery;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** PostgreSQL 控制面持久化，仅管理短时本地事务，绝不调用目标数据库。
 * 调用方提供表关系、租户和任务范围、部署密钥环，以及同一事务内的归属校验。
 * 此处不安装模式、不自动接管租约、不使待处理记录过期，也不重放恢复操作。 */
public final class PostgresGroupRewriteStore implements GroupRewriteStore {

    @FunctionalInterface
    public interface OwnershipGuard {

        /**
         * 检查数据库连接、键是否满足当前操作的约定。
         */
        void check(Connection connection, GroupRewriteKey key) throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {

        /**
         * 在当前控制面事务连接上调用持久化工作回调。
         */
        T run(Connection connection) throws Exception;
    }

    private record Ciphertext(String keyId, byte[] nonce, byte[] payload) {

    }

    private final DataSource control;

    private final String relation, scope, currentKeyId;

    private final RecoveryCrypto cryptography;

    private final BatchLimits limits;

    private final int timeout;

    private final OwnershipGuard guard;

    /**
     * 校验构造参数并初始化 {@code PostgresGroupRewriteStore}。
     */
    public PostgresGroupRewriteStore(DataSource control, String relation, String scope, String currentKeyId,
            Map<String, ? extends SecretKey> keyring, BatchLimits limits, int timeout, OwnershipGuard guard) {
        this.control = Objects.requireNonNull(control);
        this.relation = relation(relation);
        if (scope == null || !scope.matches("[0-9a-f]{64}") || timeout < 1 || timeout > 3600) {
            throw invalid();
        }
        this.scope = scope;
        this.timeout = timeout;
        this.limits = Objects.requireNonNull(limits);
        this.guard = Objects.requireNonNull(guard);
        if (limits.maxBatchBytes() > GroupRewriteMaterial.MAX_BYTES) {
            throw invalid();
        }
        this.cryptography = new RecoveryCrypto(currentKeyId, keyring);
        this.currentKeyId = currentKeyId;
    }

    /**
     * 在短控制面事务中读取当前分组重写记录。
     */
    @Override
    public Optional<GroupRewriteRecord> read(GroupRewriteKey key) {
        check(key);
        return transaction(key, connection -> select(connection, key));
    }

    /**
     * 返回当前尚未处理完成的键集合。
     */
    @Override
    public PendingPage pendingKeys(String requestedScope, String resource, String afterGroup, int pageSize) {
        if (!scope.equals(requestedScope) || resource == null || !resource.matches("[0-9a-f]{64}")
                || afterGroup != null && !afterGroup.matches("[0-9a-f]{64}") || pageSize < 1 || pageSize > 256) {
            throw invalid();
        }
        return transaction(null, connection -> {
            // 扫描全部记录头，即使 APPLIED 状态也须认证；否则损坏的阶段信息可能掩盖待处理工作。
            String sql = "SELECT group_key,revision,owner_token,phase,material_hash,state_key_id,state_mac FROM " + relation
                    + " WHERE scope=? AND resource=? AND group_key>? ORDER BY group_key LIMIT ?";
            var pending = new ArrayList<GroupRewriteKey>();
            String last = null;
            int count = 0;
            try (var statement = statement(connection, sql)) {
                statement.setString(1, scope);
                statement.setString(2, resource);
                statement.setString(3, afterGroup == null ? "" : afterGroup);
                statement.setInt(4, pageSize);
                statement.setMaxRows(pageSize);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var key = new GroupRewriteKey(scope, resource, rows.getString(1));
                        guard.check(connection, key);
                        var phase = GroupRewriteRecord.Phase.valueOf(rows.getString(4));
                        verifyState(key, rows.getLong(2), rows.getString(3), phase, rows.getString(5),
                                rows.getString(6), rows.getBytes(7));
                        if (phase != GroupRewriteRecord.Phase.APPLIED) {
                            pending.add(key);
                        }
                        last = key.group();
                        count++;
                    }
                }
            }
            return new PendingPage(pending, count == pageSize ? last : null);
        });
    }

    /**
     * 校验恢复材料并持久化当前操作的准备状态。
     */
    @Override
    public GroupRewriteRecord prepare(GroupRewriteKey key, String owner, GroupRewriteMaterial material,
            long completedRevision) {
        check(key);
        GroupRewriteRecord.owner(owner);
        Objects.requireNonNull(material);
        if (completedRevision < -1 || !key.resource().equals(material.physicalFingerprint())) {
            throw invalid();
        }
        // 建立连接或执行持久化操作前，按此存储的实际容量重新验证。
        GroupRewriteMaterial.decode(material.encoded(), limits);
        var encrypted = encrypt(key, material);
        return transaction(key, connection -> {
            var prior = select(connection, key, true);
            long revision;
            int changed;
            if (completedRevision == -1) {
                String sql = "INSERT INTO " + relation + "(scope,resource,group_key,revision,owner_token,phase,material_hash,key_id,nonce,payload,state_key_id,state_mac)"
                        + " VALUES(?,?,?,0,?,'PREPARED',?,?,?,?,?,?) ON CONFLICT(scope,resource,group_key) DO NOTHING";
                try (var statement = statement(connection, sql)) {
                    bindKey(statement, key);
                    statement.setString(4, owner);
                    statement.setString(5, material.hash());
                    statement.setString(6, encrypted.keyId());
                    statement.setBytes(7, encrypted.nonce());
                    statement.setBytes(8, encrypted.payload());
                    statement.setString(9, currentKeyId);
                    statement.setBytes(10,
                            stateMac(key, 0, owner, GroupRewriteRecord.Phase.PREPARED, material.hash(), currentKeyId));
                    changed = statement.executeUpdate();
                    revision = 0;
                }
            } else {
                revision = Math.incrementExact(completedRevision);
                if (prior.isPresent() && prior.get().phase() == GroupRewriteRecord.Phase.PREPARED && prior.get()
                        .revision() == revision
                        && prior.get().ownerToken().equals(owner) && prior.get().material().hash()
                        .equals(material.hash())) {
                    return prior.get();
                }
                if (prior.isEmpty() || prior.get().phase() != GroupRewriteRecord.Phase.APPLIED || prior.get()
                        .revision() != completedRevision) {
                    throw conflict();
                }
                String sql = "UPDATE " + relation + " SET revision=revision+1,owner_token=?,phase='PREPARED',material_hash=?,key_id=?,nonce=?,payload=?,updated_at=clock_timestamp()"
                        + ",state_key_id=?,state_mac=?"
                        + " WHERE scope=? AND resource=? AND group_key=? AND revision=? AND phase='APPLIED'";
                try (var statement = statement(connection, sql)) {
                    statement.setString(1, owner);
                    statement.setString(2, material.hash());
                    statement.setString(3, encrypted.keyId());
                    statement.setBytes(4, encrypted.nonce());
                    statement.setBytes(5, encrypted.payload());
                    statement.setString(6, currentKeyId);
                    statement.setBytes(7,
                            stateMac(key, revision, owner, GroupRewriteRecord.Phase.PREPARED, material.hash(),
                                    currentKeyId));
                    bindKey(statement, key, 8);
                    statement.setLong(11, completedRevision);
                    changed = statement.executeUpdate();
                }
            }
            if (changed == 1) {
                return new GroupRewriteRecord(key, revision, owner, GroupRewriteRecord.Phase.PREPARED, material);
            }
            var existing = select(connection, key).orElseThrow(PostgresGroupRewriteStore::conflict);
            if (existing.revision() != revision || !existing.ownerToken().equals(owner)
                    || existing.phase() != GroupRewriteRecord.Phase.PREPARED || !existing.material().hash()
                    .equals(material.hash())) {
                throw conflict();
            }
            return existing;
        });
    }

    /**
     * 返回当前迁移。
     */
    @Override
    public Optional<GroupRewriteRecord> transition(GroupRewriteKey key, String owner, long revision,
            GroupRewriteRecord.Phase expected, GroupRewriteRecord.Phase next) {
        check(key);
        GroupRewriteRecord.owner(owner);
        if (revision < 0 || !GroupRewriteRecord.permits(expected, next)) {
            throw invalid();
        }
        return transaction(key, connection -> {
            var prior = select(connection, key, true);
            if (prior.isEmpty() || prior.get().revision() != revision || !prior.get().ownerToken()
                    .equals(owner) || prior.get().phase() != expected) {
                return Optional.empty();
            }
            String sql = "UPDATE " + relation + " SET phase=?,revision=revision+1,updated_at=clock_timestamp(),state_key_id=?,state_mac=?"
                    + " WHERE scope=? AND resource=? AND group_key=? AND owner_token=? AND revision=? AND phase=?";
            try (var statement = statement(connection, sql)) {
                statement.setString(1, next.name());
                statement.setString(2, currentKeyId);
                statement.setBytes(3,
                        stateMac(key, Math.incrementExact(revision), owner, next, prior.get().material().hash(),
                                currentKeyId));
                bindKey(statement, key, 4);
                statement.setString(7, owner);
                statement.setLong(8, revision);
                statement.setString(9, expected.name());
                if (statement.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            return select(connection, key);
        });
    }

    /**
     * 锁定恢复记录并按预期修订号申请新的恢复归属。
     */
    @Override
    public Optional<GroupRewriteRecord> claimRecovery(GroupRewriteKey key, long revision, String newOwner) {
        check(key);
        GroupRewriteRecord.owner(newOwner);
        if (revision < 0) {
            throw invalid();
        }
        return transaction(key, connection -> {
            var prior = select(connection, key, true);
            if (prior.isEmpty() || prior.get().revision() != revision || prior.get()
                    .phase() == GroupRewriteRecord.Phase.APPLIED || prior.get().ownerToken().equals(newOwner)) {
                return Optional.empty();
            }
            String sql = "UPDATE " + relation + " SET owner_token=?,revision=revision+1,updated_at=clock_timestamp(),state_key_id=?,state_mac=?"
                    + " WHERE scope=? AND resource=? AND group_key=? AND revision=? AND phase<>'APPLIED' AND owner_token<>?";
            try (var statement = statement(connection, sql)) {
                statement.setString(1, newOwner);
                statement.setString(2, currentKeyId);
                statement.setBytes(3, stateMac(key, Math.incrementExact(revision), newOwner, prior.get().phase(),
                        prior.get().material().hash(), currentKeyId));
                bindKey(statement, key, 4);
                statement.setLong(7, revision);
                statement.setString(8, newOwner);
                if (statement.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            return select(connection, key);
        });
    }

    /**
     * 按当前查询条件读取目标记录或数据行。
     */
    private Optional<GroupRewriteRecord> select(Connection connection, GroupRewriteKey key) throws Exception {
        return select(connection, key, false);
    }

    /**
     * 按当前查询条件读取目标记录或数据行。
     */
    private Optional<GroupRewriteRecord> select(Connection connection, GroupRewriteKey key,
            boolean lock) throws Exception {
        String sql = "SELECT revision,owner_token,phase,material_hash,key_id,nonce,octet_length(payload),"
                + "CASE WHEN octet_length(payload)<=? THEN payload ELSE NULL END,state_key_id,state_mac FROM " + relation
                + " WHERE scope=? AND resource=? AND group_key=?" + (lock ? " FOR UPDATE" : "");
        try (var statement = statement(connection, sql)) {
            statement.setLong(1, limits.maxBatchBytes() + 16);
            bindKey(statement, key, 2);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                long revision = rows.getLong(1);
                String owner = rows.getString(2);
                var phase = GroupRewriteRecord.Phase.valueOf(rows.getString(3));
                String hash = rows.getString(4), keyId = rows.getString(5);
                byte[] nonce = rows.getBytes(6);
                long size = rows.getLong(7);
                verifyState(key, revision, owner, phase, hash, rows.getString(9), rows.getBytes(10));
                if (size < 16 || size > limits.maxBatchBytes() + 16 || nonce == null || nonce.length != 12 || hash == null || !hash.matches(
                        "[0-9a-f]{64}")) {
                    throw invalid();
                }
                byte[] payload = rows.getBytes(8);
                if (payload == null || payload.length != size || rows.next()) {
                    throw invalid();
                }
                byte[] plaintext = crypt(Cipher.DECRYPT_MODE, keyId, nonce, aad(key, hash, keyId), payload);
                if (!hash.equals(GroupRewriteMaterial.sha256(plaintext))) {
                    throw invalid();
                }
                var material = GroupRewriteMaterial.decode(plaintext, limits);
                return Optional.of(new GroupRewriteRecord(key, revision, owner, phase, material));
            }
        }
    }

    /**
     * 按指定算法加密当前明文值。
     */
    private Ciphertext encrypt(GroupRewriteKey key, GroupRewriteMaterial material) {
        var sealed = cryptography.seal(material.encoded(), id -> aad(key, material.hash(), id));
        return new Ciphertext(sealed.keyId(), sealed.nonce(), sealed.payload());
    }

    /**
     * 使用指定密钥计算持久化状态的消息认证码。
     */
    private byte[] stateMac(GroupRewriteKey key, long revision, String owner, GroupRewriteRecord.Phase phase,
            String hash, String keyId) {
        if (revision < 0 || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        GroupRewriteRecord.owner(owner);
        return cryptography.stateMac(keyId, "DBSWITCH_GROUP_STATE_KEY_V1",
                String.join("\0", "DBSWITCH_GROUP_STATE_V1", key.scope(), key.resource(), key.group(),
                        Long.toString(revision), owner, phase.name(), hash, keyId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验状态。
     */
    private void verifyState(GroupRewriteKey key, long revision, String owner, GroupRewriteRecord.Phase phase,
            String hash, String keyId, byte[] actual) {
        if (actual == null || !MessageDigest.isEqual(stateMac(key, revision, owner, phase, hash, keyId), actual)) {
            throw new IllegalStateException("Recovery state authentication failed");
        }
    }

    /**
     * 使用当前密码协议加密或解密载荷。
     */
    private byte[] crypt(int mode, String keyId, byte[] nonce, byte[] aad, byte[] bytes) {
        return cryptography.crypt(mode, keyId, nonce, aad, bytes);
    }

    /**
     * 生成加密载荷使用的附加认证数据。
     */
    private static byte[] aad(GroupRewriteKey key, String hash, String keyId) {
        return String.join("\0", "DBSWITCH_GROUP_MATERIAL_V1", key.scope(), key.resource(), key.group(), hash, keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 返回事务。
     */
    private <T> T transaction(GroupRewriteKey key, Work<T> work) {
        try (var connection = control.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Recovery store requires an independently owned connection");
            }
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                try (var settings = connection.createStatement()) {
                    settings.setQueryTimeout(timeout);
                    settings.execute("SET LOCAL statement_timeout=" + Math.multiplyExact(timeout, 1000));
                    settings.execute("SET LOCAL lock_timeout=" + Math.multiplyExact(Math.min(timeout, 5), 1000));
                }
                if (key != null) {
                    guard.check(connection, key);
                }
                T result = work.run(connection);
                connection.commit();
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) { /* 绝不能声称回滚失败可以证明记录不存在。 */ }
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Recovery store result is unavailable; reconcile durable state before target writes");
        }
    }

    /**
     * 返回语句。
     */
    private PreparedStatement statement(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(timeout);
            return statement;
        } catch (SQLException failure) {
            try {
                statement.close();
            } catch (SQLException ignored) {
            }
            throw failure;
        }
    }

    /**
     * 将游标键值绑定到查询或写入语句参数。
     */
    private static void bindKey(PreparedStatement statement, GroupRewriteKey key) throws SQLException {
        bindKey(statement, key, 1);
    }

    /**
     * 将游标键值绑定到查询或写入语句参数。
     */
    private static void bindKey(PreparedStatement statement, GroupRewriteKey key, int offset) throws SQLException {
        statement.setString(offset, key.scope());
        statement.setString(offset + 1, key.resource());
        statement.setString(offset + 2, key.group());
    }

    /**
     * 检查键是否满足当前操作的约定。
     */
    private void check(GroupRewriteKey key) {
        if (key == null || !scope.equals(key.scope())) {
            throw invalid();
        }
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private static String relation(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,62}(\\.[a-z][a-z0-9_-]{0,62})?")) {
            throw invalid();
        }
        return Arrays.stream(value.split("\\.")).map(part -> "\"" + part + "\"")
                .collect(java.util.stream.Collectors.joining("."));
    }

    /** 供部署迁移或测试参考的控制面 DDL；存储自身绝不执行它。 */
    public static List<String> schemaStatements(String name) {
        String table = relation(name);
        String index = "dbswitch_group_pending_" + GroupRewriteMaterial.sha256(name.getBytes(StandardCharsets.UTF_8))
                .substring(0, 12);
        return List.of(
                "CREATE TABLE " + table + " (scope varchar(64) NOT NULL,resource varchar(64) NOT NULL,group_key varchar(64) NOT NULL,"
                        + "revision bigint NOT NULL CHECK(revision>=0),owner_token varchar(128) NOT NULL,phase varchar(32) NOT NULL CHECK(phase IN ('PREPARED','DELETE_INTENT','RESTORE_INTENT','APPLIED')),"
                        + "material_hash varchar(64) NOT NULL,key_id varchar(64) NOT NULL,nonce bytea NOT NULL CHECK(octet_length(nonce)=12),"
                        + "payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 16 AND 67108880),state_key_id varchar(64) NOT NULL,state_mac bytea NOT NULL CHECK(octet_length(state_mac)=32),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),"
                        + "updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(scope,resource,group_key))",
                "CREATE UNIQUE INDEX \"" + index + "\" ON " + table + "(resource,group_key) WHERE phase<>'APPLIED'");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid recovery store input or capacity");
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalStateException}。
     */
    private static IllegalStateException conflict() {
        return new IllegalStateException("Recovery generation already requires reconciliation");
    }
}

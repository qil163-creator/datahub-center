package cn.iocoder.yudao.framework.dbswitch.dbcommon.database;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 为已验证表锁可排除并发写入的产品提供自有 SERIALIZABLE 键集读取器。
 * 产品配置负责原生结构、排序、物理标识及部署代际依据。
 * 现有 MVCC 读取器不通过此有意阻塞的协议路由。
 */
public final class LockedKeysetCursor implements CursorReadSession {

    public record Shape(String fingerprint, CursorKeyContract keys) {

        /**
         * 校验构造参数并初始化 {@code Shape}。
         */
        public Shape {
            Objects.requireNonNull(fingerprint);
            Objects.requireNonNull(keys);
        }
    }

    public interface Profile {

        /** 改变连接状态前调用；拒绝未验证产品及缺少部署固定信息的情况。 */
        void admit(Connection connection, CursorReadRequest request, int timeout) throws SQLException;

        /** 观测结构或行前，获取事务作用域的源表锁。 */
        Shape lockAndInspect(Connection connection, CursorReadRequest request, int timeout) throws SQLException;

        /** 重新检查保留的部署固定信息及物理表标识，不改变事务。 */
        void verify(Connection connection, CursorReadRequest request, int timeout) throws SQLException;

        /**
         * 按当前数据库方言转义并引用标识符。
         */
        String quote(String name);

        /**
         * 按当前查询条件读取目标记录或数据行。
         */
        String select(int maximumRows, String projection, String relation, String predicate, String order);

        /**
         * 返回参数。
         */
        String parameter(String field) throws SQLException;

        /**
         * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
         */
        void validate(String field, TypedCursorValue value) throws SQLException;

        /**
         * 按当前游标位置读取受限数量的数据行。
         */
        Object read(ResultSet result, int index, String field, long maximumCellBytes) throws SQLException;

        /**
         * 返回游标值。
         */
        TypedCursorValue cursorValue(String field, Object value) throws SQLException;

        /**
         * 将当前值绑定到预编译语句参数。
         */
        default void bind(PreparedStatement statement, int index, TypedCursorValue value) throws SQLException {
            statement.setObject(index, value.jdbcValue(), value.jdbcType());
        }
    }

    private final Connection connection;

    private final CursorReadRequest request;

    private final Profile profile;

    private final List<String> order;

    private final int timeout, pageSize;

    private Shape shape;

    private String fingerprint;

    private TypedCursor upper, position, pageAfter;

    private PreparedStatement statement;

    private ResultSet result;

    private Object[] row;

    private int pageRows, previousIsolation;

    private boolean closed, ownsTransaction;

    /**
     * 打开游标读取会话并准备当前读取范围。
     */
    public static CursorReadSession open(DataSource source, CursorReadRequest request,
            Profile profile, int timeout, int fetchSize) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(request);
        Objects.requireNonNull(profile);
        if (request.sourceGenerationPin() == null) {
            throw new IllegalArgumentException("Locked cursor requires a retained deployment generation pin");
        }
        if (timeout < 1 || timeout > 120 || fetchSize < 1) {
            throw new IllegalArgumentException("Invalid locked cursor query budget");
        }
        LockedKeysetCursor reader = null;
        try {
            reader = new LockedKeysetCursor(source.getConnection(), request, profile, timeout, fetchSize);
            reader.prepare();
            return reader;
        } catch (Throwable failure) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            // 驱动异常可能包含凭据或用户数据；不要在此边界暴露其文本或原因。
            throw new IllegalArgumentException("Locked cursor product, generation, schema or ordering is not verified");
        }
    }

    /**
     * 初始化 {@code LockedKeysetCursor}。
     */
    private LockedKeysetCursor(Connection connection, CursorReadRequest request, Profile profile, int timeout,
            int fetchSize) {
        this.connection = connection;
        this.request = request;
        this.profile = profile;
        this.timeout = timeout;
        order = request.orderFields();
        pageSize = Math.min(fetchSize, request.limits().maxRows());
    }

    /**
     * 核对源代次、目标关系和键列事实，固定读取上界并准备当前游标分页状态。
     */
    private void prepare() throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Locked cursor requires an idle owned connection");
        }
        profile.admit(connection, request, timeout);
        request.limits()
                .requireStatementParameters(Math.addExact(Math.multiplyExact(order.size(), order.size() + 1), 1));
        previousIsolation = connection.getTransactionIsolation();
        ownsTransaction = true;
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setAutoCommit(false);
        if (connection.getTransactionIsolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw new SQLException("SERIALIZABLE isolation is inactive");
        }
        shape = profile.lockAndInspect(connection, request, timeout);
        fingerprint = DigestUtil.sha256Hex("LOCKED_KEYSET_V1\0" + shape.fingerprint() + '\0'
                + request.fields() + '\0' + order + '\0' + request.sourceGenerationPin().fingerprint());
        if (request.expectedSchemaFingerprint() != null && !fingerprint.equals(request.expectedSchemaFingerprint())) {
            throw new SQLException("Locked cursor source identity, generation or schema changed");
        }
        validate(request.lower());
        validate(request.fixedUpper());
        if (request.lower() != null) {
            try (var p = connection.prepareStatement(select(1, "1", profile.quote(request.watermark()) + ">="
                    + profile.parameter(request.watermark()), ""))) {
                p.setQueryTimeout(timeout);
                profile.bind(p, 1, request.lower().values().getFirst());
                try (var r = p.executeQuery()) {
                    if (!r.next()) {
                        throw new SQLException("Source watermark moved backwards");
                    }
                }
            }
        }
        upper = request.fixedUpper();
        if (upper == null && !request.upperBoundFrozen()) {
            try (var p = connection.prepareStatement(select(1, fields(order), "", ordered("DESC")))) {
                p.setQueryTimeout(timeout);
                try (var r = p.executeQuery()) {
                    if (r.next()) {
                        upper = cursor(r, true);
                    }
                }
            }
        }
        if (upper != null) {
            openPage();
        }
    }

    /**
     * 检查游标元组分量数与键列一致，并校验各键值符合声明的类型和取值范围。
     */
    private void validate(TypedCursor cursor) throws SQLException {
        if (cursor == null) {
            return;
        }
        if (cursor.values().size() != order.size()) {
            throw new SQLException("Cursor tuple shape changed");
        }
        for (int i = 0; i < order.size(); i++) {
            profile.validate(order.get(i), cursor.values().get(i));
        }
    }

    /** 字典序 OR 形式不需要行构造器，同样适用于 SQL Server 2000。 */
    private String bound(boolean upperBound) throws SQLException {
        var alternatives = new ArrayList<String>();
        for (int i = 0; i < order.size(); i++) {
            var terms = new ArrayList<String>();
            for (int j = 0; j < i; j++) {
                terms.add(profile.quote(order.get(j)) + "=" + profile.parameter(order.get(j)));
            }
            terms.add(profile.quote(order.get(i)) + (upperBound ? (i == order.size() - 1 ? "<=" : "<") : ">")
                    + profile.parameter(order.get(i)));
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }

    /**
     * 将读取边界值绑定到预编译查询参数。
     */
    private int bindBound(PreparedStatement p, int index, TypedCursor value) throws SQLException {
        for (int i = 0; i < order.size(); i++) {
            for (int j = 0; j <= i; j++) {
                profile.bind(p, index++, value.values().get(j));
            }
        }
        return index;
    }

    /**
     * 按当前游标边界打开下一页结果集。
     */
    private void openPage() throws SQLException {
        closePage();
        profile.verify(connection, request, timeout);
        String predicate = bound(true);
        if (request.lower() != null) {
            predicate += " AND " + profile.quote(request.watermark()) + ">=" + profile.parameter(request.watermark());
        }
        if (pageAfter != null) {
            predicate += " AND " + bound(false);
        }
        statement = connection.prepareStatement(select(pageSize, fields(request.fields()), predicate, ordered("ASC")));
        statement.setQueryTimeout(timeout);
        int index = bindBound(statement, 1, upper);
        if (request.lower() != null) {
            profile.bind(statement, index++, request.lower().values().getFirst());
        }
        if (pageAfter != null) {
            bindBound(statement, index, pageAfter);
        }
        result = statement.executeQuery();
        pageRows = 0;
    }

    /**
     * 返回游标。
     */
    private TypedCursor cursor(ResultSet result, boolean projected) throws SQLException {
        var values = new ArrayList<TypedCursorValue>();
        for (int i = 0; i < order.size(); i++) {
            String name = order.get(i);
            int index = projected ? i + 1 : request.fields().indexOf(name) + 1;
            values.add(profile.cursorValue(name, profile.read(result, index, name, request.limits().maxCellBytes())));
        }
        return new TypedCursor(1, values);
    }

    /**
     * 推进游标并返回下一条数据或后续读取状态。
     */
    @Override
    public boolean next() throws SQLException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Locked cursor interrupted or closed");
        }
        if (result == null) {
            profile.verify(connection, request, timeout);
            return false;
        }
        if (!result.next()) {
            profile.verify(connection, request, timeout);
            if (pageRows < pageSize) {
                row = null;
                position = null;
                return false;
            }
            pageAfter = position;
            openPage();
            if (!result.next()) {
                row = null;
                position = null;
                return false;
            }
        }
        var values = new Object[request.fields().size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = profile.read(result, i + 1, request.fields().get(i), request.limits().maxCellBytes());
        }
        position = cursor(result, false);
        pageRows++;
        row = BatchValueConverter.convert(List.<Object[]>of(values), values.length, request.limits()).getFirst();
        return true;
    }

    /**
     * 按当前查询条件读取目标记录或数据行。
     */
    private String select(int maximumRows, String projection, String predicate, String ordering) {
        return profile.select(maximumRows, projection,
                profile.quote(request.schema()) + "." + profile.quote(request.table()), predicate, ordering);
    }

    /**
     * 返回字段集合。
     */
    private String fields(List<String> fields) {
        return String.join(",", fields.stream().map(profile::quote).toList());
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    private String ordered(String direction) {
        return String.join(",", order.stream().map(name -> profile.quote(name) + " " + direction).toList());
    }

    /**
     * 返回指纹。
     */
    @Override
    public String schemaFingerprint() {
        return fingerprint;
    }

    /**
     * 返回当前游标键契约的原生指纹。
     */
    @Override
    public String keyContractFingerprint() {
        return shape.keys().nativeFingerprint();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        return shape.keys();
    }

    /**
     * 返回上界。
     */
    @Override
    public TypedCursor upperBound() {
        return upper;
    }

    /**
     * 返回位置。
     */
    @Override
    public TypedCursor position() {
        return position;
    }

    /**
     * 返回数据行。
     */
    @Override
    public Object[] row() {
        if (row == null) {
            throw new IllegalStateException("Cursor has no row");
        }
        return row.clone();
    }

    /**
     * 关闭分页。
     */
    private void closePage() throws SQLException {
        try {
            if (result != null) {
                result.close();
            }
        } finally {
            result = null;
            if (statement != null) {
                statement.close();
            }
            statement = null;
        }
    }

    /**
     * 关闭当前页；会话自行开启事务时回滚并恢复连接配置，最后释放连接。
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        boolean failed = false;
        try {
            closePage();
        } catch (Throwable failure) {
            failed = true;
        }
        try {
            if (ownsTransaction) {
                connection.rollback();
                connection.setAutoCommit(true);
                connection.setTransactionIsolation(previousIsolation);
            }
        } catch (Throwable failure) {
            failed = true;
            try {
                connection.abort(Runnable::run);
            } catch (Throwable ignored) {
            }
        }
        try {
            connection.close();
        } catch (Throwable failure) {
            failed = true;
        }
        if (failed) {
            throw new SQLException("Locked cursor physical cleanup was not confirmed");
        }
    }
}

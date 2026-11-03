package cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServer2000SourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.LockedKeysetCursor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;

import java.sql.*;

/** 产品自身提供的元数据和锁适配器，供通用旧版键集引擎使用。 */
public final class SqlServer2000LockedCursorProfile implements LockedKeysetCursor.Profile {

    private SqlServer2000ReplayMetadata.Shape shape;

    /**
     * 核对数据库产品与源端代次满足当前游标读取准入条件。
     */
    public void admit(Connection c, CursorReadRequest request, int timeout) throws SQLException {
        SqlServer2000ReplayMetadata.product(c);
        SqlServer2000SourceGeneration.verifyCurrent(c, request.sourceGenerationPin(), timeout);
    }

    /**
     * 在持有目标锁后读取并检查当前对象定义。
     */
    public LockedKeysetCursor.Shape lockAndInspect(Connection c, CursorReadRequest request,
            int timeout) throws SQLException {
        SqlServer2000ReplayMetadata.lock(c, SqlServer2000ReplayMetadata.relation(request.schema(), request.table()),
                false, timeout);
        shape = SqlServer2000ReplayMetadata.read(c, request.schema(), request.table(), timeout);
        if (!shape.columns().keySet().containsAll(request.fields())) {
            throw new SQLException("SQL Server 2000 cursor projection is incomplete");
        }
        for (String name : request.orderFields()) {
            shape.columns().get(name).requireOrdered();
        }
        return new LockedKeysetCursor.Shape(shape.physical() + '\0' + shape.fingerprint(),
                SqlServer2000ReplayMetadata.keys(shape, request.primaryKeys()));
    }

    /**
     * 检查连接、请求、超时设置是否满足当前操作的约定。
     */
    public void verify(Connection c, CursorReadRequest request, int timeout) throws SQLException {
        SqlServer2000SourceGeneration.verifyCurrent(c, request.sourceGenerationPin(), timeout);
        if (!shape.physical()
                .equals(SqlServer2000ReplayMetadata.physical(c, request.schema(), request.table(), timeout))) {
            throw new SQLException("SQL Server 2000 source incarnation changed");
        }
    }

    /**
     * 按SQL Server 2000方言转义并引用标识符。
     */
    public String quote(String name) {
        return SqlServer2000ReplayMetadata.quote(name);
    }

    /**
     * 按当前查询条件读取目标记录或数据行。
     */
    public String select(int limit, String projection, String relation, String predicate, String order) {
        return "SELECT TOP " + limit + " " + projection + " FROM " + relation
                + (predicate.isEmpty() ? "" : " WHERE " + predicate) + (order.isEmpty() ? "" : " ORDER BY " + order);
    }

    /**
     * 返回参数。
     */
    public String parameter(String field) {
        return "CAST(? AS " + shape.columns().get(field).nativeType() + ")";
    }

    /**
     * 根据当前列事实校验游标键值处于精确重放值域。
     */
    public void validate(String field, TypedCursorValue value) throws SQLException {
        shape.columns().get(field).validate(value);
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public Object read(ResultSet r, int index, String field, long maximum) throws SQLException {
        return shape.columns().get(field).read(r, index, maximum);
    }

    /**
     * 返回游标值。
     */
    public TypedCursorValue cursorValue(String field, Object value) throws SQLException {
        return shape.columns().get(field).cursor(value);
    }
}

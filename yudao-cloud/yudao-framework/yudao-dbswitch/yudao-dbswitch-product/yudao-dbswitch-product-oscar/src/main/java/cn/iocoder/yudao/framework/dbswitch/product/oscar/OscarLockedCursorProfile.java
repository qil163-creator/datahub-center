package cn.iocoder.yudao.framework.dbswitch.product.oscar;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.core.util.OscarSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.LockedKeysetCursor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;

import java.sql.*;

/** ShenTong 表 SHARE 锁及原生标量适配器，不继承 Oracle 游标。 */
public final class OscarLockedCursorProfile implements LockedKeysetCursor.Profile {

    private OscarReplayMetadata.Shape shape;

    /**
     * 核对数据库产品与源端代次满足当前游标读取准入条件。
     */
    public void admit(Connection c, CursorReadRequest request, int timeout) throws SQLException {
        OscarReplayMetadata.product(c);
        OscarSourceGeneration.verifyCurrent(c, request.sourceGenerationPin(), timeout);
    }

    /**
     * 在持有目标锁后读取并检查当前对象定义。
     */
    public LockedKeysetCursor.Shape lockAndInspect(Connection c, CursorReadRequest request,
            int timeout) throws SQLException {
        OscarReplayMetadata.lock(c, OscarReplayMetadata.relation(request.schema(), request.table()), false, timeout);
        shape = OscarReplayMetadata.read(c, request.schema(), request.table(), timeout);
        if (!shape.columns().keySet().containsAll(request.fields())) {
            throw new SQLException("Oscar cursor projection is incomplete");
        }
        for (String name : request.orderFields()) {
            shape.columns().get(name).requireOrdered();
        }
        return new LockedKeysetCursor.Shape(shape.physical() + '\0' + shape.fingerprint(),
                OscarReplayMetadata.keys(shape, request.primaryKeys()));
    }

    /**
     * 检查连接、请求、超时设置是否满足当前操作的约定。
     */
    public void verify(Connection c, CursorReadRequest request, int timeout) throws SQLException {
        OscarSourceGeneration.verifyCurrent(c, request.sourceGenerationPin(), timeout);
        if (!shape.physical().equals(OscarReplayMetadata.physical(c, request.schema(), request.table(), timeout))) {
            throw new SQLException("Oscar source incarnation changed");
        }
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    public String quote(String name) {
        return OscarReplayMetadata.quote(name);
    }

    /**
     * 按当前查询条件读取目标记录或数据行。
     */
    public String select(int limit, String projection, String relation, String predicate, String order) {
        return "SELECT " + projection + " FROM " + relation + (predicate.isEmpty() ? "" : " WHERE " + predicate)
                + (order.isEmpty() ? "" : " ORDER BY " + order) + " LIMIT " + limit;
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

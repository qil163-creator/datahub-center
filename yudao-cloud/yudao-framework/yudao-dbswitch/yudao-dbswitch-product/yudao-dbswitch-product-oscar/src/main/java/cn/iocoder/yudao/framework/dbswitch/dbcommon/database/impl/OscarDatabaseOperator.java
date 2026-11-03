package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarSqlNames;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarLockedCursorProfile;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.LockedKeysetCursor;

import javax.sql.DataSource;

/**
 * 神通(Oscar)数据库操作器,复用 Oracle 操作器逻辑并覆写级联删表语句。
 */
public class OscarDatabaseOperator extends OracleDatabaseOperator {

    /**
     * 初始化 {@code OscarDatabaseOperator}及其父类状态。
     */
    public OscarDatabaseOperator(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return OscarSqlNames.quote(name);
    }

    /**
     * 每个游标使用一个 SERIALIZABLE 快照；读取器在自己的连接上重新证明版本、驱动、NLS 及代次。
     */
    @Override
    public CursorReadSession openCursor(CursorReadRequest request) {
        return LockedKeysetCursor.open(dataSource, request, new OscarLockedCursorProfile(),
                Math.min(120, runtimeOptions.queryTimeoutSeconds()), fetchSize);
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.OSCAR;
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schemaName, String tableName) {
        String sql = "DROP TABLE " + OscarSqlNames.relation(schemaName, tableName) + " CASCADE";
        this.executeSql(sql);
    }
}

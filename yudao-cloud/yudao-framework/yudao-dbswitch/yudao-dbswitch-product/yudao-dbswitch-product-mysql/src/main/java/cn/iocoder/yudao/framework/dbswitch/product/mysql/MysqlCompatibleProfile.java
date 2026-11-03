package cn.iocoder.yudao.framework.dbswitch.product.mysql;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.MysqlCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/**
 * 共享 MySQL 协议读取器、游标目录凭据及原生 UPSERT 中，由具体产品负责的差异。
 * <p>
 * MySQL 直接使用本类；兼容产品通过其提供方传入自己的子类，因此共享
 * 类无需了解任何其他产品。
 */
public class MysqlCompatibleProfile {

    public static final MysqlCompatibleProfile MYSQL = new MysqlCompatibleProfile(ProductTypeEnum.MYSQL, "MySQL");

    private final ProductTypeEnum product;

    private final String productName;

    /**
     * 校验构造参数并初始化 {@code MysqlCompatibleProfile}。
     */
    protected MysqlCompatibleProfile(ProductTypeEnum product, String productName) {
        this.product = Objects.requireNonNull(product, "product");
        this.productName = Objects.requireNonNull(productName, "productName");
    }

    /**
     * 返回数据库产品。
     */
    public final ProductTypeEnum product() {
        return product;
    }

    /**
     * 返回产品名称。
     */
    public final String productName() {
        return productName;
    }

    /** Connector/J 仅在 {@code Integer.MIN_VALUE} 取数大小下逐行流式读取，操作器默认提供该值。 */
    public int convertFetchSize(int fetchSize, IntUnaryOperator operatorDefault) {
        return operatorDefault.applyAsInt(fetchSize);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    public void verifyProduct(Connection connection, int timeout) throws SQLException {
        var metadata = connection.getMetaData();
        if (!"MySQL".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 8) {
            throw new SQLException("Cursor requires the configured MySQL or MariaDB server product");
        }
    }

    /** 将 {@code schema/table} 映射到物理表编号的 InnoDB 字典视图。 */
    protected String innodbTablesView() {
        return "INNODB_TABLES";
    }

    /**
     * 读取当前表的原生物理身份。
     */
    public String physicalIdentity(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        verifyProduct(connection, timeout);
        return MysqlCursorMetadata.innodbTableIdentity(connection, product, innodbTablesView(), schema, table, timeout);
    }

    /** 绑定到游标键契约中的额外键比较事实；null 表示不添加。 */
    public String keyComparisonProfile(Connection connection, int timeout) throws SQLException {
        return null;
    }

    /** 函数键组成部分在 {@code information_schema.STATISTICS} 中的表达式，或 SQL 字面量 NULL。 */
    public String statisticsExpressionSql() {
        return "s.EXPRESSION";
    }

    /** 在 UPSERT 写事务内、获取关系元数据锁之前执行。 */
    public void beforeUpsertLock(Connection connection, int timeout) throws SQLException {
    }

    /**
     * 根据唯一键及绑定类型执行当前更新或插入批次。
     */
    public void executeUpsertBatch(JdbcBatchExecutor.WriteContext context, String sql, List<Object[]> rows,
            int[] types, int timeout) throws SQLException {
        JdbcBatchExecutor.executeBatch(context, sql, rows, types, timeout);
    }

    /** 在借用源端连接之前创建，可拒绝读取。 */
    public CursorReadHooks cursorReadHooks(DataSource source, CursorReadRequest request,
            int timeout) throws SQLException {
        return CursorReadHooks.NONE;
    }

    /** 一次游标读取的产品步骤，由共享读取器按此声明顺序调用。 */
    public interface CursorReadHooks {

        CursorReadHooks NONE = new CursorReadHooks() {
        };

        /** 在服务端产品验证后、会话修改前执行。 */
        default void afterProductVerified(Connection connection) throws SQLException {
        }

        /** 是否允许为快照事务将自有连接标记为只读。 */
        default boolean readOnlyTransaction() {
            return true;
        }

        /** 在 REPEATABLE READ 及手动提交生效后执行。 */
        default void afterTransactionStarted(Connection connection) throws SQLException {
        }

        /** 额外模式指纹材料；null 表示不添加。 */
        default String schemaMaterial() {
            return null;
        }

        /** 每次分页查询之前执行。 */
        default void beforePage(Connection connection) throws SQLException {
        }

        /** 在自有事务回滚且自动提交恢复后执行。 */
        default void afterTransactionReleased(Connection connection) throws SQLException {
        }
    }
}

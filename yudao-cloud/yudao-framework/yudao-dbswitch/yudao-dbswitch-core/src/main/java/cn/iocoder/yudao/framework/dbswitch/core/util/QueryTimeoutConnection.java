package cn.iocoder.yudao.framework.dbswitch.core.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Set;

/**
 * 为一个连接上创建的全部语句统一设置 JDBC 查询超时（{@link Statement#setQueryTimeout}）。
 *
 * <p>由驱动在超时后取消语句（PostgreSQL 等发送取消请求），不依赖线程中断。只包装语句创建方法，
 * 其余调用（含 {@code unwrap}/{@code isWrapperFor}）原样委托给原连接；连接的关闭仍由调用方负责。</p>
 */
public final class QueryTimeoutConnection {

    /** 驱动以 SQLState 57014 报告语句被取消（PostgreSQL/openGauss/Kingbase 等） */
    private static final String SQLSTATE_QUERY_CANCELED = "57014";

    private static final Set<String> STATEMENT_FACTORIES = Set.of("createStatement", "prepareStatement", "prepareCall");

    private QueryTimeoutConnection() {
    }

    /**
     * 包装连接；超时秒数小于 1 时原样返回，不做限制。
     *
     * @param connection 原连接
     * @param queryTimeoutSeconds 每条语句的超时秒数
     * @return 包装后的连接
     */
    public static Connection wrap(Connection connection, int queryTimeoutSeconds) {
        if (connection == null || queryTimeoutSeconds < 1) {
            return connection;
        }
        InvocationHandler handler = (proxy, method, args) -> invoke(proxy, connection, queryTimeoutSeconds, method, args);
        return (Connection) Proxy.newProxyInstance(QueryTimeoutConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /**
     * 判断异常链中是否存在查询超时（JDBC 标准超时异常，或 SQLState 57014 的语句取消）。
     *
     * @param failure 异常
     * @return 是否为查询超时
     */
    public static boolean isQueryTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLTimeoutException) {
                return true;
            }
            if (current instanceof SQLException sql && SQLSTATE_QUERY_CANCELED.equals(sql.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static Object invoke(Object proxy, Connection target, int seconds, Method method, Object[] args)
            throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(target);
                case "toString" -> "QueryTimeoutConnection[" + seconds + "s]:" + target;
                default -> method.invoke(target, args);
            };
        }
        Object result;
        try {
            result = method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
        if (result instanceof Statement statement && STATEMENT_FACTORIES.contains(method.getName())) {
            statement.setQueryTimeout(seconds);
        }
        return result;
    }
}

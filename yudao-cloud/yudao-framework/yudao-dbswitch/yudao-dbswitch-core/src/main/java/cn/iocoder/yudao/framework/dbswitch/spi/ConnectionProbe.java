package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 独立于元数据、读写和同步的只读协议探测；每次调用独占并关闭资源。 */
@FunctionalInterface
public interface ConnectionProbe {

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception;

    /**
     * 产品可返回分项证据，保留认证成功后资源检查失败等中间结果。
     * 默认适配旧探针，只认可其整体测连范围，不推断元数据、读写或同步能力。
     */
    default ConnectionDiagnosticResult diagnose(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
        long started = System.nanoTime();
        probe(request, opener);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        var check = new ConnectionDiagnosticResult.Check("BASIC", "connection", "连接与目标资源", "PASS", elapsed,
                "产品协议、认证及所配置的目标资源探测成功", null, null);
        return new ConnectionDiagnosticResult("PASS", true, elapsed, List.of(check));
    }

    @FunctionalInterface
    interface JdbcOpener {

        /**
         * 根据当前驱动及连接配置建立数据库连接。
         */
        Connection open() throws Exception;
    }
}

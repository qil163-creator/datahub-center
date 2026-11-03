package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbe;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 有界独占测连；底层超时失效时占用固定工作槽，不无限创建线程。 */
public final class ConnectionProbes implements AutoCloseable {

    private final ThreadPoolExecutor workers;

    /**
     * 初始化 {@code ConnectionProbes}。
     */
    public ConnectionProbes() {
        var sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            var thread = new Thread(task, "dbswitch-connect-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    /**
     * 探测目标连接或接口并返回可用性结果。
     */
    public ConnectionDiagnosticResult probe(ConnectionProbeRequest request, ConnectionProbe strategy,
            ConnectionProbe.JdbcOpener opener) {
        long started = System.nanoTime();
        Future<ConnectionDiagnosticResult> pending = null;
        try {
            pending = workers.submit(() -> {
                try {
                    return strategy.diagnose(request, opener);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            var observed = pending.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (observed == null) {
                return result("SKIPPED", started, "PROBE_RESULT_INVALID：产品未返回可验证的诊断证据");
            }
            return new ConnectionDiagnosticResult(observed.status(), observed.connectionSuccessful(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), observed.checks());
        } catch (TimeoutException failure) {
            if (pending != null) {
                pending.cancel(true);
            }
            return result(false, started, "CONNECTION_TIMEOUT：连接探测超时");
        } catch (RejectedExecutionException failure) {
            return result("SKIPPED", started, "CONNECTION_BUSY：连接探测资源繁忙");
        } catch (InterruptedException failure) {
            if (pending != null) {
                pending.cancel(true);
            }
            Thread.currentThread().interrupt();
            return result("SKIPPED", started, "CONNECTION_INTERRUPTED：连接探测已中断");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            Throwable current = cause;
            for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
                if (current instanceof cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException) {
                    return result("SKIPPED", started, "此项检查不适用于当前数据库产品的原生语义", "NOT_APPLICABLE");
                }
                if (current instanceof java.sql.SQLFeatureNotSupportedException
                        || current instanceof SQLException sql && "0A000".equals(sql.getSQLState())) {
                    return result("SKIPPED", started, "DIAGNOSTIC_NOT_IMPLEMENTED：此产品或驱动尚未实现对应诊断",
                            "NOT_IMPLEMENTED");
                }
                if (current.getCause() == current || current != cause && current.getCause() == cause) {
                    break;
                }
            }
            String code = diagnosticMessage(cause);
            if (cause instanceof ClassNotFoundException || cause instanceof NoClassDefFoundError
                    || cause instanceof LinkageError || cause instanceof IllegalArgumentException
                    && cause.getMessage() != null && cause.getMessage().contains("driver is unavailable")) {
                code = "MISSING_DEPENDENCY：缺少目标驱动或 SDK，请配置已验证的依赖";
            } else if (cause instanceof IllegalArgumentException) {
                code = "INVALID_CONFIGURATION：连接配置不完整或不合法";
            }
            return result(code.startsWith("MISSING_DEPENDENCY") ? "SKIPPED" : "FAIL", started, code);
        }
    }

    /** 只根据异常类型、标准 SQLState 或产品显式分类诊断，不解析或回显厂商错误文本。 */
    private static String diagnosticMessage(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof ConnectionProbeException classified) {
                return classified.reason().diagnosticMessage();
            }
            if (current instanceof SSLException || current instanceof CertificateException) {
                return ConnectionProbeException.Reason.TLS.diagnosticMessage();
            }
            if (current instanceof SQLTimeoutException || current instanceof SocketTimeoutException) {
                return "CONNECTION_TIMEOUT：连接探测超时，请检查网络和服务响应";
            }
            if (current instanceof UnknownHostException) {
                return "HOST_NOT_FOUND：无法解析服务主机名，请检查地址和 DNS";
            }
            if (current instanceof ConnectException) {
                return "CONNECTION_REFUSED：无法建立连接，请检查服务监听端口和网络访问";
            }
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (state.startsWith("28")) {
                    return ConnectionProbeException.Reason.AUTHENTICATION.diagnosticMessage();
                }
                if ("42501".equals(state)) {
                    return ConnectionProbeException.Reason.AUTHORIZATION.diagnosticMessage();
                }
                if ("3D000".equals(state) || "3F000".equals(state)) {
                    return ConnectionProbeException.Reason.TARGET_NOT_FOUND.diagnosticMessage();
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return "CONNECTION_FAILED：连接、认证或目标授权失败";
    }

    /**
     * 返回结果。
     */
    private static ConnectionDiagnosticResult result(boolean success, long started, String message) {
        return result(success ? "PASS" : "FAIL", started, message);
    }

    /**
     * 返回结果。
     */
    private static ConnectionDiagnosticResult result(String status, long started, String message) {
        return result(status, started, message, "SKIPPED".equals(status) ? "UNVERIFIED" : null);
    }

    private static ConnectionDiagnosticResult result(String status, long started, String message, String reasonCode) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new ConnectionDiagnosticResult(status, "PASS".equals(status), elapsed,
                List.of(new ConnectionDiagnosticResult.Check("BASIC", "connection", "连接与认证",
                        status, elapsed, message, null, null, reasonCode)));
    }

    /**
     * 关闭 {@code ConnectionProbes} 当前持有的资源。
     */
    @Override
    public void close() {
        workers.shutdownNow();
    }
}

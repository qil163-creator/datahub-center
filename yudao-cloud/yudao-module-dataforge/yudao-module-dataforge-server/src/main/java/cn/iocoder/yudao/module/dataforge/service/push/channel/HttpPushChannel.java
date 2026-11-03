package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushChannel;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushSession;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushSslContexts;
import cn.iocoder.yudao.module.dataforge.service.push.HttpPushBodySupport;
import cn.iocoder.yudao.module.dataforge.service.push.HttpPushRetrySupport;
import cn.iocoder.yudao.module.dataforge.service.push.HttpPushUrlBuilder;
import cn.iocoder.yudao.module.dataforge.service.push.HttpPushUrlGuard;
import cn.iocoder.yudao.module.dataforge.service.push.PushPayload;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP POST：body 为 { objectName, dataList }，2xx 成功。
 * 超时可配；旧重试参数保留兼容但不重投未知请求；停止时取消进行中的请求。
 *
 * @author Li
 */
@Component
public class HttpPushChannel implements DataPushChannel {

    private static final ThreadLocal<HttpSession> SESSION = new ThreadLocal<>();

    @Resource
    private HttpPushUrlGuard urlGuard;

    @Resource
    private DataPushProperties pushProperties;

    /**
     * 返回当前实现对应的类型标识。
     *
     * @return 类型
     */
    @Override
    public Integer getType() {
        return PushChannelTypeEnum.HTTP.getType();
    }

    /**
     * 打开会话。
     *
     * @param channelJson 通道JSON
     * @return 数据推送会话
     */
    @Override
    public DataPushSession openSession(String channelJson) {
        DataPushChannelConfigVO config = JsonUtils.parseObject(channelJson, DataPushChannelConfigVO.class);
        HttpSession session = new HttpSession(sslFrom(config == null ? null : config.getHttp()));
        SESSION.set(session);
        return session;
    }

    /**
     * 推送HTTP推送通道。
     *
     * @param payload 载荷
     * @param channelJson 通道JSON
     * @param log 日志
     */
    @Override
    public void push(PushPayload payload, String channelJson, StringBuilder log) {
        DataPushChannelConfigVO config = JsonUtils.parseObject(channelJson, DataPushChannelConfigVO.class);
        DataPushChannelConfigVO.HttpConfig http = config == null ? null : config.getHttp();
        String url = HttpPushUrlBuilder.build(http);
        URI uri = urlGuard.validate(url);
        String body = JsonUtils.toJsonString(payload);
        boolean gzip = http != null && Boolean.TRUE.equals(http.getGzip());
        byte[] bodyBytes = HttpPushBodySupport.encode(body, gzip);
        DataPushProperties.Http defaults = pushProperties == null ? null : pushProperties.getHttp();
        int timeoutMs = HttpPushRetrySupport.resolveTimeoutMs(http, defaults);
        int retryTimes = HttpPushRetrySupport.resolveRetryTimes(http, defaults);
        int retryIntervalMs = HttpPushRetrySupport.resolveRetryIntervalMs(http, defaults);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        if (gzip) {
            builder.header("Content-Encoding", "gzip");
        }
        if (http != null && http.getHeaders() != null) {
            for (DataPushChannelConfigVO.KeyValue header : http.getHeaders()) {
                if (StrUtil.isNotBlank(header.getKey())
                        && !"content-type".equalsIgnoreCase(header.getKey())
                        && !"content-encoding".equalsIgnoreCase(header.getKey())) {
                    builder.header(header.getKey(), StrUtil.nullToEmpty(header.getValue()));
                }
            }
        }
        HttpRequest request = builder.build();
        HttpSession session = SESSION.get();
        boolean closeAfter = false;
        if (session == null) {
            session = new HttpSession(sslFrom(http));
            closeAfter = true;
        }
        try {
            sendWithRetry(session, request, timeoutMs, retryTimes, retryIntervalMs, log);
        } catch (CancellationException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务已被手动停止", ex);
        } finally {
            if (closeAfter) {
                session.close();
            }
        }
        if (log != null) {
            log.append("HTTP POST ").append(HttpPushUrlBuilder.hostOnly(url)).append(" rows=")
                    .append(payload.getDataList() == null ? 0 : payload.getDataList().size())
                    .append('\n');
        }
    }

    /**
     * 发送包含重试。
     *
     * @param session 会话
     * @param request 请求参数
     * @param timeoutMs 超时毫秒
     * @param retryTimes 重试时间
     * @param retryIntervalMs 重试间隔毫秒
     * @param log 日志
     */
    private static void sendWithRetry(HttpSession session, HttpRequest request, int timeoutMs,
            int retryTimes, int retryIntervalMs, StringBuilder log)
            throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                session.send(request, timeoutMs);
                return;
            } catch (InterruptedException | CancellationException ex) {
                throw ex instanceof InterruptedException interrupted
                        ? interrupted : new InterruptedException("任务已被手动停止");
            } catch (Exception ex) {
                if (attempt >= retryTimes || !HttpPushRetrySupport.isRetryable(ex)) {
                    if (ex instanceof IllegalStateException illegal) {
                        throw illegal;
                    }
                    throw new IllegalStateException(ex.getMessage(), ex);
                }
                if (log != null) {
                    log.append("HTTP retry ").append(attempt + 1).append('/').append(retryTimes)
                            .append(" after ").append(StrUtil.maxLength(ex.getMessage(), 120)).append('\n');
                }
                if (retryIntervalMs > 0) {
                    Thread.sleep(retryIntervalMs);
                }
            }
        }
    }

    /**
     * 获取HTTP推送通道的SSL来源。
     *
     * @param http HTTP
     * @return SSL上下文
     */
    private static SSLContext sslFrom(DataPushChannelConfigVO.HttpConfig http) {
        if (http == null) {
            return null;
        }
        return DataPushSslContexts.create(http.getTruststorePath(), http.getTruststorePassword(),
                http.getKeystorePath(), http.getKeystorePassword());
    }

    private static final class HttpSession implements DataPushSession {

        private final HttpClient client;

        private final AtomicReference<CompletableFuture<?>> inflight = new AtomicReference<>();

        /**
         * 创建HTTP会话实例。
         *
         * @param sslContext SSL上下文
         */
        private HttpSession(SSLContext sslContext) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10));
            if (sslContext != null) {
                builder.sslContext(sslContext);
            }
            this.client = builder.build();
        }

        /**
         * 在指定超时内发送 HTTP 推送，校验响应状态并在中断或取消时终止在途请求。
         *
         * @param request 请求参数
         * @param timeoutMs 超时毫秒
         */
        private void send(HttpRequest request, int timeoutMs) throws InterruptedException {
            CompletableFuture<HttpResponse<String>> future =
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            inflight.set(future);
            try {
                HttpResponse<String> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (!HttpPushUrlBuilder.isSuccessStatus(response.statusCode())) {
                    throw new IllegalStateException(String.format("推送失败，HTTP %s：%s",
                            response.statusCode(), StrUtil.maxLength(response.body(), 500)));
                }
            } catch (InterruptedException ex) {
                future.cancel(true);
                throw ex;
            } catch (CancellationException ex) {
                throw new InterruptedException("任务已被手动停止");
            } catch (Exception ex) {
                if (ex.getCause() instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
                throw new IllegalStateException(ex.getMessage(), ex);
            } finally {
                inflight.compareAndSet(future, null);
            }
        }

        /**
         * 中止HTTP会话。
         */
        @Override
        public void abort() {
            CompletableFuture<?> future = inflight.get();
            if (future != null) {
                future.cancel(true);
            }
        }

        /**
         * 关闭当前实例持有的连接、会话或执行资源。
         */
        @Override
        public void close() {
            abort();
            SESSION.remove();
        }
    }

}

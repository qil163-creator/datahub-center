package cn.iocoder.yudao.module.infra.framework.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 文件归属来自真实客户端证书；浏览器可伪造的身份与控制头不进入受管存储边界。 */
public class ManagedFileRpcFilter extends OncePerRequestFilter {
    private static final String OWNER = ManagedFileRpcFilter.class.getName() + ".owner";
    private static final Set<String> PRESERVED = Set.of("content-type", "content-length", "accept", "accept-encoding",
            "host", "transfer-encoding", "traceparent", "tracestate");
    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();
    private final Supplier<Map<String, Set<String>>> peers;
    public ManagedFileRpcFilter(Supplier<Map<String, Set<String>>> peers) { this.peers = peers; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var peer = resolver.resolve(request, peers.get());
        if (peer.isEmpty()) {
            response.setStatus(401);
            ServletUtils.writeJSON(response, CommonResult.error(UNAUTHORIZED.getCode(), "受管文件接口需要认证服务身份"));
            return;
        }
        // JSON 的 Base64 有固定膨胀；在反序列化之前限制整个请求体，包含分块传输。
        final long requestLimit = 28L * 1024 * 1024 + 4096;
        if (request.getContentLengthLong() > requestLimit) { response.setStatus(413); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream bounded;
            private boolean stripped(String name) { return name != null && !PRESERVED.contains(name.toLowerCase(Locale.ROOT)); }
            @Override public String getHeader(String name) { return stripped(name) ? null : super.getHeader(name); }
            @Override public Enumeration<String> getHeaders(String name) { return stripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name); }
            @Override public Enumeration<String> getHeaderNames() { return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(name -> !stripped(name)).toList()); }
            @Override public int getIntHeader(String name) { return stripped(name) ? -1 : super.getIntHeader(name); }
            @Override public long getDateHeader(String name) { return stripped(name) ? -1 : super.getDateHeader(name); }
            @Override public Object getAttribute(String name) { return OWNER.equals(name) ? peer.get() : super.getAttribute(name); }
            @Override public ServletInputStream getInputStream() throws IOException {
                if (bounded != null) return bounded;
                ServletInputStream source = super.getInputStream();
                bounded = new ServletInputStream() {
                    private long remaining = requestLimit;
                    private void count(int consumed) throws IOException {
                        if (consumed > 0 && (remaining -= consumed) < 0) throw new IOException("受管文件请求体超过限额");
                    }
                    @Override public int read() throws IOException { int value = source.read(); count(value < 0 ? 0 : 1); return value; }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int size = source.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining + 1))); count(size); return size;
                    }
                    @Override public boolean isFinished() { return source.isFinished(); }
                    @Override public boolean isReady() { return source.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                    @Override public void close() throws IOException { source.close(); }
                };
                return bounded;
            }
            @Override public BufferedReader getReader() throws IOException { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }

    public static String requireOwner(HttpServletRequest request) {
        Object owner = request == null ? null : request.getAttribute(OWNER);
        if (!(owner instanceof String value) || value.isBlank()) throw exception(UNAUTHORIZED);
        return value;
    }
}

package cn.iocoder.yudao.module.supply.framework.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.supply.api.app.SupplyGrantSourceApi;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.GRANT_RPC_PEER_UNVERIFIED;

/**
 * 来源级授权 v2 RPC（{@link SupplyGrantSourceApi#PREFIX}）的服务身份门禁。
 *
 * 在租户上下文、Spring Security 及 login-user 头解析之前执行：只有容器 TLS 握手提供、且指纹已配置的
 * 客户端证书才能通过；伪造的内部头、直连、无证书或未配置对端一律以 401 拒绝，不进入业务处理。
 * 通过后把已认证的服务标识写入请求属性，业务适配层据此限定命令可声明的 sourceSystem。
 */
@Slf4j
public class SourceGrantRpcFilter extends OncePerRequestFilter {

    /** 已认证服务标识的请求属性；只有本过滤器写入，客户端无法通过头或参数设置。 */
    public static final String VERIFIED_PEER_ATTRIBUTE = SourceGrantRpcFilter.class.getName() + ".verifiedPeer";

    /** 需要门禁保护的完整 Servlet 路径前缀（不含上下文路径）。 */
    public static final String PROTECTED_PATH = SupplyGrantSourceApi.PREFIX;

    private final VerifiedServicePeerResolver resolver;

    private final Supplier<Map<String, Set<String>>> peers;

    /**
     * 创建来源授权RPC实例。
     *
     * @param resolver 解析器
     * @param peers 关联方
     */
    public SourceGrantRpcFilter(VerifiedServicePeerResolver resolver, Supplier<Map<String, Set<String>>> peers) {
        this.resolver = resolver;
        this.peers = peers;
    }

    /**
     * 按当前过滤器规则处理 HTTP 请求并传递过滤器链。
     *
     * @param request 请求参数
     * @param response 响应对象
     * @param chain 处理链
     */
    @Override
    @SuppressWarnings("NullableProblems")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> peer = resolver.resolve(request, peers.get());
        if (peer.isEmpty()) {
            log.warn("[doFilterInternal][来源授权 v2 RPC 未通过服务身份认证 uri({}) secure({}) remote({})]",
                    request.getRequestURI(), request.isSecure(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ServletUtils.writeJSON(response,
                    CommonResult.error(UNAUTHORIZED.getCode(), "来源授权 RPC 需要经过认证的服务身份"));
            return;
        }
        request.setAttribute(VERIFIED_PEER_ATTRIBUTE, peer.get());
        chain.doFilter(request, response);
    }

    /**
     * 读取当前请求已认证的服务标识；缺失时说明请求没有经过本门禁（例如过滤器未注册或路径不匹配），必须拒绝。
     */
    public static String requireVerifiedPeer(HttpServletRequest request) {
        Object peer = request == null ? null : request.getAttribute(VERIFIED_PEER_ATTRIBUTE);
        if (!(peer instanceof String service) || service.isBlank()) {
            throw exception(GRANT_RPC_PEER_UNVERIFIED);
        }
        return service;
    }

}

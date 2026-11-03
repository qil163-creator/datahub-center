package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;

/** 在任何租户/登录头解析之前验证 TLS 服务身份；业务权限仍由服务独立检查。 */
public class SyncEvidenceRpcFilter extends OncePerRequestFilter {

    private static final String VERIFIED = SyncEvidenceRpcFilter.class.getName() + ".verified";

    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();

    private final Supplier<Map<String, Set<String>>> peers;

    /**
     * 创建同步证据RPC实例。
     *
     * @param peers 关联方
     */
    public SyncEvidenceRpcFilter(Supplier<Map<String, Set<String>>> peers) {
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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!resolver.resolve(request, peers.get()).filter("governance"::equals).isPresent()) {
            response.setStatus(401);
            ServletUtils.writeJSON(response,
                    CommonResult.error(UNAUTHORIZED.getCode(), "同步批次查询需要认证服务通道"));
            return;
        }
        request.setAttribute(VERIFIED, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    /**
     * 校验已核验。
     *
     * @param request 请求参数
     */
    public static void requireVerified(HttpServletRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getAttribute(VERIFIED))) {
            throw exception(UNAUTHORIZED);
        }
    }
}


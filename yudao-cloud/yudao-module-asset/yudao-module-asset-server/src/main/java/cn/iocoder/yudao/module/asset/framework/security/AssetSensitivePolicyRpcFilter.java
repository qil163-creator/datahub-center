package cn.iocoder.yudao.module.asset.framework.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 只组装领域允许的服务身份，证书校验委托安全组件。 */
public class AssetSensitivePolicyRpcFilter extends OncePerRequestFilter {

    private static final String VERIFIED = AssetSensitivePolicyRpcFilter.class.getName() + ".verified";

    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();

    private final Supplier<Map<String, Set<String>>> peers;

    /**
     * 使用允许的服务证书初始化规则工作台敏感清单 RPC。
     */
    public AssetSensitivePolicyRpcFilter(Supplier<Map<String, Set<String>>> peers) {
        this.peers = peers;
    }

    /**
     * 校验内部调用身份并处理规则工作台敏感清单 RPC请求过滤链。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (resolver.resolve(request, peers.get()).filter("governance"::equals).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ServletUtils.writeJSON(response,
                    CommonResult.error(UNAUTHORIZED.getCode(), "规则工作台敏感清单读取需要认证治理服务通道"));
            return;
        }
        request.setAttribute(VERIFIED, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    /**
     * 校验规则工作台敏感清单 RPC的已核验，不满足约束时拒绝当前操作。
     */
    public static void requireVerified(HttpServletRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getAttribute(VERIFIED))) {
            throw exception(UNAUTHORIZED);
        }
    }
}

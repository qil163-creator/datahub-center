package cn.iocoder.yudao.module.governance.framework.security;

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

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 仅资产服务可通过已认证的 TLS 通道读取术语及清单分类定义。 */
public class StandardTermRpcFilter extends OncePerRequestFilter {

    private static final String VERIFIED = StandardTermRpcFilter.class.getName() + ".verified";

    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();

    private final Supplier<Map<String, Set<String>>> peers;

    /**
     * 使用给定同部门成员初始化标准术语RPC。
     */
    public StandardTermRpcFilter(Supplier<Map<String, Set<String>>> peers) {
        this.peers = peers;
    }

    /**
     * 校验内部调用身份并处理标准术语RPC请求过滤链。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (resolver.resolve(request, peers.get()).filter("asset"::equals).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ServletUtils.writeJSON(response,
                    CommonResult.error(UNAUTHORIZED.getCode(), "治理定义读取需要认证资产服务通道"));
            return;
        }
        request.setAttribute(VERIFIED, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    /**
     * 校验标准术语RPC的已核验，不满足约束时拒绝当前操作。
     */
    public static void requireVerified(HttpServletRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getAttribute(VERIFIED))) {
            throw exception(UNAUTHORIZED);
        }
    }

}

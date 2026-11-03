package cn.iocoder.yudao.module.dataforge.framework.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;

/** 先验证物理 mTLS 身份，再允许解析租户或内部用户头；请求头本身不是服务身份证明。 */
public class EtlBatchRpcFilter extends OncePerRequestFilter {

    private static final String VERIFIED = EtlBatchRpcFilter.class.getName() + ".verified";

    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();

    private final Supplier<Map<String, Set<String>>> peers;

    /**
     * 创建ETL批次RPC实例。
     *
     * @param peers 关联方
     */
    public EtlBatchRpcFilter(Supplier<Map<String, Set<String>>> peers) {
        this.peers = peers;
    }

    /**
     * 按当前过滤器规则处理 HTTP 请求并传递过滤器链。
     *
     * @param req 请求参数
     * @param resp HTTPServlet响应数据
     * @param chain 处理链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
            FilterChain chain) throws IOException, ServletException {
        if (resolver.resolve(req, peers.get()).filter(Set.of("asset", "supply")::contains).isEmpty()) {
            resp.setStatus(401);
            ServletUtils.writeJSON(resp, CommonResult.error(UNAUTHORIZED.getCode(), "ETL 批次消费需要认证服务通道"));
            return;
        }
        req.setAttribute(VERIFIED, Boolean.TRUE);
        chain.doFilter(req, resp);
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

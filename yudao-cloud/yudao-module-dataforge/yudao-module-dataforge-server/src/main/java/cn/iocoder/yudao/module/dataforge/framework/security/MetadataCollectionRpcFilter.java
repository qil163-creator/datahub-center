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

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 在解析任何内部身份头或执行人参数之前，验证容器实际握手取得的治理服务证书。 */
public final class MetadataCollectionRpcFilter extends OncePerRequestFilter {

    private static final String VERIFIED = MetadataCollectionRpcFilter.class.getName() + ".verified";

    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();

    private final Supplier<Map<String, Set<String>>> peers;

    /**
     * 创建元数据集合RPC实例。
     *
     * @param peers 关联方
     */
    public MetadataCollectionRpcFilter(Supplier<Map<String, Set<String>>> peers) {
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
        if (resolver.resolve(request, peers.get()).filter("governance"::equals).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ServletUtils.writeJSON(response,
                    CommonResult.error(UNAUTHORIZED.getCode(), "元数据采集需要认证治理服务通道"));
            return;
        }
        request.setAttribute(VERIFIED, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    /** 控制器再次检查门禁结果；漏注册过滤器时也不能接受任意 actorId。 */
    public static void requireVerified(HttpServletRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.getAttribute(VERIFIED))) {
            throw exception(UNAUTHORIZED);
        }
    }
}

package cn.iocoder.yudao.module.system.framework.security.core;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 只接受容器完成的治理服务 TLS 认证，任何身份转发 HTTP 头均不能建立信任。 */
public class AdminUserOptionRpcFilter extends OncePerRequestFilter {
    private static final String VERIFIED=AdminUserOptionRpcFilter.class.getName()+".verified";
    private final VerifiedServicePeerResolver resolver=new VerifiedServicePeerResolver();
    private final Supplier<Map<String,Set<String>>> peers;
    public AdminUserOptionRpcFilter(Supplier<Map<String,Set<String>>> peers){this.peers=peers;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(resolver.resolve(request,peers.get()).filter("governance"::equals).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ServletUtils.writeJSON(response,CommonResult.error(UNAUTHORIZED.getCode(),"账号候选读取需要认证治理服务通道"));return;
        }
        request.setAttribute(VERIFIED,Boolean.TRUE);chain.doFilter(request,response);
    }
    public static void requireVerified(HttpServletRequest request){
        if(request==null || !Boolean.TRUE.equals(request.getAttribute(VERIFIED)))throw exception(UNAUTHORIZED);
    }
}

package cn.iocoder.yudao.module.modeling.framework.security;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.datapermission.core.rpc.DataPermissionRequestInterceptor;
import cn.iocoder.yudao.framework.env.core.util.EnvUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.UNAUTHORIZED;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 候选字段只接受已认证治理服务传递的管理员身份，权限仍由建模服务判定。 */
public class ModelRuleContextRpcFilter extends OncePerRequestFilter {

    private static final String VERIFIED = ModelRuleContextRpcFilter.class.getName() + ".verified";

    private static final String LOGIN = SecurityFrameworkUtils.LOGIN_USER_HEADER;

    private static final Set<String> STRIPPED = Set.of(WebFrameworkUtils.HEADER_TENANT_ID,
            WebFrameworkUtils.HEADER_VISIT_TENANT_ID, DataPermissionRequestInterceptor.ENABLE_HEADER_NAME,
            EnvUtils.HEADER_TAG);

    private final VerifiedServicePeerResolver resolver = new VerifiedServicePeerResolver();

    private final Supplier<Map<String, Set<String>>> peers;

    /**
     * 创建模型规则上下文RPC实例。
     *
     * @param peers 关联方
     */
    public ModelRuleContextRpcFilter(Supplier<Map<String, Set<String>>> peers) {
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
            throws IOException, ServletException {
        if (resolver.resolve(request, peers.get()).filter("governance"::equals).isEmpty()) {
            reject(response);
            return;
        }
        String identity;
        try {
            var headers = Collections.list(request.getHeaders(LOGIN));
            if (headers.size() != 1 || headers.getFirst().length() > 8192) {
                reject(response);
                return;
            }
            LoginUser actor = JsonUtils.parseObject(URLDecoder.decode(headers.getFirst(), StandardCharsets.UTF_8),
                    LoginUser.class);
            if (actor == null || actor.getId() == null || actor.getId() <= 0
                    || !Objects.equals(actor.getUserType(), UserTypeEnum.ADMIN.getValue())) {
                reject(response);
                return;
            }
            // 不透传租户访问态、数据权限开关等可影响提供方授权的上下文。
            identity = URLEncoder.encode(JsonUtils.toJsonString(new LoginUser().setId(actor.getId())
                    .setUserType(actor.getUserType())), StandardCharsets.UTF_8);
        } catch (RuntimeException invalidIdentity) {
            reject(response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            /**
             * 判断已剥离条件是否成立。
             *
             * @param name 名称
             * @return 处理是否成功
             */
            private boolean stripped(String name) {
                return name != null && STRIPPED.stream().anyMatch(name::equalsIgnoreCase);
            }

            /**
             * 读取请求头。
             *
             * @param name 名称
             * @return 请求头文本
             */
            @Override
            public String getHeader(String name) {
                return LOGIN.equalsIgnoreCase(name) ? identity : stripped(name) ? null : super.getHeader(name);
            }

            /**
             * 读取请求头。
             *
             * @param name 名称
             * @return 枚举字符串
             */
            @Override
            public Enumeration<String> getHeaders(String name) {
                return LOGIN.equalsIgnoreCase(name) ? Collections.enumeration(List.of(identity))
                        : stripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }

            /**
             * 读取请求头名称。
             *
             * @return 请求头名称
             */
            @Override
            public Enumeration<String> getHeaderNames() {
                var names = super.getHeaderNames();
                return names == null ? Collections.emptyEnumeration() : Collections.enumeration(
                        Collections.list(names).stream()
                                .filter(name -> !stripped(name)).toList());
            }

            /**
             * 读取整数请求头。
             *
             * @param name 名称
             * @return 读取整数请求头数值
             */
            @Override
            public int getIntHeader(String name) {
                return stripped(name) ? -1 : super.getIntHeader(name);
            }

            /**
             * 读取日期请求头。
             *
             * @param name 名称
             * @return 读取日期请求头数值
             */
            @Override
            public long getDateHeader(String name) {
                return stripped(name) ? -1 : super.getDateHeader(name);
            }

            /**
             * 读取属性。
             *
             * @param name 名称
             * @return 模型规则上下文RPC
             */
            @Override
            public Object getAttribute(String name) {
                return VERIFIED.equals(name) ? Boolean.TRUE : super.getAttribute(name);
            }
        }, response);
    }

    /**
     * 拒绝模型规则上下文RPC。
     *
     * @param response 响应对象
     */
    private static void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        ServletUtils.writeJSON(response,
                CommonResult.error(UNAUTHORIZED.getCode(), "模型规则候选需要认证治理服务和管理员身份"));
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

package cn.iocoder.yudao.framework.security.core.filter;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * An explicitly registered external entry boundary. Removes privileged context headers
 * before other filters and requires real token validation, including when mock login is enabled.
 * Registration owns URL scope and must precede context filters and Spring Security.
 * No automatic registration changes existing internal RPC or other application routes.
 */
public final class ExternalIdentityHeadersFilter extends OncePerRequestFilter {

    private static final String VERIFIED_TOKEN_REQUIRED = ExternalIdentityHeadersFilter.class.getName() + ".verified-token-required";
    private final Set<String> strippedHeaders;

    /** Login-user is always removed; callers supply other component-owned control header names. */
    public ExternalIdentityHeadersFilter(Collection<String> additionalControlHeaders) {
        var names = new HashSet<String>();
        names.add(SecurityFrameworkUtils.LOGIN_USER_HEADER);
        for (String name : Objects.requireNonNull(additionalControlHeaders, "additionalControlHeaders")) {
            if (name == null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("Invalid control header name");
            }
            names.add(name.toLowerCase(Locale.ROOT));
        }
        this.strippedHeaders = Set.copyOf(names);
    }

    static boolean requiresVerifiedToken(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(VERIFIED_TOKEN_REQUIRED));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private boolean stripped(String name) {
                return name != null && strippedHeaders.contains(name.toLowerCase(Locale.ROOT));
            }
            @Override public String getHeader(String name) { return stripped(name) ? null : super.getHeader(name); }
            @Override public Enumeration<String> getHeaders(String name) {
                return stripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
            }
            @Override public Enumeration<String> getHeaderNames() {
                Enumeration<String> headers = super.getHeaderNames();
                if (headers == null) { return Collections.emptyEnumeration(); }
                return Collections.enumeration(Collections.list(headers).stream().filter(name -> !stripped(name)).toList());
            }
            @Override public int getIntHeader(String name) { return stripped(name) ? -1 : super.getIntHeader(name); }
            @Override public long getDateHeader(String name) { return stripped(name) ? -1 : super.getDateHeader(name); }
            @Override public Object getAttribute(String name) {
                // Server-created wrapper state cannot be supplied by an HTTP header or parameter.
                return VERIFIED_TOKEN_REQUIRED.equals(name) ? Boolean.TRUE : super.getAttribute(name);
            }
        }, response);
    }
}

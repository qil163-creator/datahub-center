package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.framework.push.config.DataPushProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTTP 推送地址校验：协议、白名单、解析后的私网 / 元数据地址
 *
 * @author Li
 */
@Component
public class HttpPushUrlGuard {

    @Resource
    private DataPushProperties properties;

    /**
     * 校验HTTP推送URL门禁输入及状态。
     *
     * @param rawUrl 原始URL
     * @return URI
     */
    public URI validate(String rawUrl) {
        DataPushProperties.Http http = properties == null ? null : properties.getHttp();
        return validate(rawUrl,
                http == null ? List.of() : http.getAllowedHosts(),
                http != null && http.isAllowPrivate());
    }

    /**
     * 校验HTTP推送URL门禁输入及状态。
     *
     * @param rawUrl 原始URL
     * @param allowedHosts 允许主机
     * @param allowPrivate 允许私有
     * @return URI
     */
    public static URI validate(String rawUrl, List<String> allowedHosts, boolean allowPrivate) {
        if (StrUtil.isBlank(rawUrl)) {
            throw new IllegalArgumentException("未配置推送地址");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("推送地址不合法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("推送地址仅支持 http/https 协议");
        }
        if (StrUtil.isNotBlank(uri.getUserInfo())) {
            throw new IllegalArgumentException("推送地址不允许携带用户名密码");
        }
        String host = uri.getHost();
        if (StrUtil.isBlank(host)) {
            throw new IllegalArgumentException("推送地址缺少主机名");
        }
        List<String> allowList = allowedHosts == null ? List.of() : allowedHosts;
        boolean listed = hostAllowed(host, allowList);
        if (CollUtil.isNotEmpty(allowList) && !listed) {
            throw new IllegalArgumentException("主机不在推送白名单中：" + host);
        }
        for (InetAddress address : resolveAll(host)) {
            if (isCloudMetadata(address)) {
                throw new IllegalArgumentException("不允许访问云元数据地址");
            }
            if (!listed && !allowPrivate && isPrivateOrLocal(address)) {
                throw new IllegalArgumentException("不允许推送到内网或本机地址：" + host);
            }
        }
        return uri;
    }

    /**
     * 判断主机允许条件是否成立。
     *
     * @param host 主机
     * @param allowedHosts 允许主机
     * @return 处理是否成功
     */
    static boolean hostAllowed(String host, List<String> allowedHosts) {
        if (StrUtil.isBlank(host) || CollUtil.isEmpty(allowedHosts)) {
            return false;
        }
        String target = host.toLowerCase(Locale.ROOT);
        for (String raw : allowedHosts) {
            if (StrUtil.isBlank(raw)) {
                continue;
            }
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (target.endsWith(suffix) && target.length() > suffix.length()) {
                    return true;
                }
            } else if (target.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否满足私有或本地条件。
     *
     * @param address 地址
     * @return 判断结果
     */
    static boolean isPrivateOrLocal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4 && (bytes[0] & 0xFF) == 0) {
            return true;
        }
        if (address instanceof Inet6Address && bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否满足云元数据条件。
     *
     * @param address 地址
     * @return 判断结果
     */
    static boolean isCloudMetadata(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 169
                && (bytes[1] & 0xFF) == 254
                && (bytes[2] & 0xFF) == 169
                && (bytes[3] & 0xFF) == 254;
    }

    /**
     * 解析全部。
     *
     * @param host 主机
     * @return 解析全部结果集合
     */
    private static List<InetAddress> resolveAll(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            List<InetAddress> result = new ArrayList<>(addresses.length);
            result.addAll(List.of(addresses));
            return result;
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("无法解析推送主机：" + host);
        }
    }

}

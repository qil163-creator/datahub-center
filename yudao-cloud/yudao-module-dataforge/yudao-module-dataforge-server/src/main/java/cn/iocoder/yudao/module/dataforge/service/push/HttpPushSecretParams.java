package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * HTTP 鉴权类参数禁止进入 Query，避免 Token 落入对端 / 网关访问日志
 *
 * @author Li
 */
public final class HttpPushSecretParams {

    private static final Set<String> EXACT = Set.of(
            "token", "access_token", "accesstoken", "authorization", "auth",
            "api_key", "apikey", "secret", "secret_key", "secretkey",
            "password", "passwd", "pwd", "access_key", "accesskey",
            "appsecret", "app_secret", "client_secret", "private_key", "jwt"
    );

    /**
     * 限制HTTP推送密钥参数工具的实例化，统一通过静态能力使用。
     */
    private HttpPushSecretParams() {
    }

    /**
     * 判断是否满足密钥键条件。
     *
     * @param key 标识键
     * @return 判断结果
     */
    public static boolean isSecretKey(String key) {
        if (StrUtil.isBlank(key)) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (EXACT.contains(normalized)) {
            return true;
        }
        return normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("password") || normalized.contains("passwd");
    }

    /**
     * 校验HTTP推送密钥参数输入及状态。
     *
     * @param http HTTP
     */
    public static void validate(DataPushChannelConfigVO.HttpConfig http) {
        if (http == null) {
            return;
        }
        if (http.getParams() != null) {
            for (DataPushChannelConfigVO.KeyValue param : http.getParams()) {
                if (param != null && isSecretKey(param.getKey())) {
                    throw new IllegalArgumentException(
                            "查询参数不能包含鉴权字段「" + param.getKey() + "」，请放到请求头");
                }
            }
        }
        if (StrUtil.isBlank(http.getUrl())) {
            return;
        }
        String query;
        try {
            query = URI.create(http.getUrl().trim()).getQuery();
        } catch (Exception ex) {
            return;
        }
        if (StrUtil.isBlank(query)) {
            return;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            if (isSecretKey(name)) {
                throw new IllegalArgumentException(
                        "推送地址 Query 不能包含鉴权字段「" + name + "」，请放到请求头");
            }
        }
    }

}

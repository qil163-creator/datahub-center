package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 推送 URL 拼接（Query 做 URL 编码）
 *
 * @author Li
 */
public final class HttpPushUrlBuilder {

    /**
     * 限制HTTP推送URL构造器工具的实例化，统一通过静态能力使用。
     */
    private HttpPushUrlBuilder() {
    }

    /**
     * 构造HTTP推送URL构造器。
     *
     * @param http HTTP
     * @return 构造HTTP推送URL构造器文本
     */
    public static String build(DataPushChannelConfigVO.HttpConfig http) {
        if (http == null || StrUtil.isBlank(http.getUrl())) {
            throw new IllegalArgumentException("未配置推送地址");
        }
        String url = http.getUrl().trim();
        if (http.getParams() == null || http.getParams().isEmpty()) {
            return url;
        }
        StringBuilder query = new StringBuilder();
        for (DataPushChannelConfigVO.KeyValue param : http.getParams()) {
            if (StrUtil.isBlank(param.getKey()) || param.getValue() == null
                    || HttpPushSecretParams.isSecretKey(param.getKey())) {
                continue;
            }
            if (query.length() > 0) {
                query.append("&");
            }
            query.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }
        if (query.length() == 0) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /**
     * 判断是否满足成功状态条件。
     *
     * @param status 目标状态
     * @return 判断结果
     */
    public static boolean isSuccessStatus(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * 实时日志只保留主机（含端口），不带路径和 Query
     */
    public static String hostOnly(String url) {
        if (StrUtil.isBlank(url)) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (StrUtil.isBlank(host)) {
                return stripQuery(url);
            }
            int port = uri.getPort();
            return port > 0 ? host + ":" + port : host;
        } catch (Exception ignored) {
            return stripQuery(url);
        }
    }

    /**
     * 获取HTTP推送URL构造器的剥离查询。
     *
     * @param url URL
     * @return HTTP推送URL构造器的剥离查询文本
     */
    private static String stripQuery(String url) {
        int idx = url.indexOf('?');
        return idx > 0 ? url.substring(0, idx) : url;
    }

}

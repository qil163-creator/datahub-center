package cn.iocoder.yudao.framework.file.core.client;

import cn.iocoder.yudao.framework.common.util.http.HttpUtils;

/** 默认采用静态资源域名；需要业务下载路由时由调用方替换。 */
public class DefaultFileUrlResolver implements FileUrlResolver {
    @Override
    public String format(Long clientId, String domain, String path) {
        return domain.replaceAll("/+$", "") + "/" + HttpUtils.encodeUrlPath(FileStoragePaths.validate(path));
    }
}

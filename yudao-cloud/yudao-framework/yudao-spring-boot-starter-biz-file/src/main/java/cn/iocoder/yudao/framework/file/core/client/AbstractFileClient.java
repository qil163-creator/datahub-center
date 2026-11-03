package cn.iocoder.yudao.framework.file.core.client;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;

import java.util.Objects;

/** 配置不可热修改；工厂替换整个客户端，避免跨请求共享可变连接配置。 */
public abstract class AbstractFileClient<C extends FileClientConfig> implements FileClient {

    private final Long id;
    protected final C config;
    private final FileUrlResolver urlResolver;

    protected AbstractFileClient(Long id, C config) {
        this(id, config, new DefaultFileUrlResolver());
    }

    @SuppressWarnings("unchecked")
    protected AbstractFileClient(Long id, C config, FileUrlResolver urlResolver) {
        this.id = Objects.requireNonNull(id);
        this.config = (C) JsonUtils.parseObject2(JsonUtils.toJsonString(Objects.requireNonNull(config)), config.getClass());
        this.urlResolver = Objects.requireNonNull(urlResolver);
    }

    public final void init() {
        doInit();
    }

    protected abstract void doInit();

    @Override
    public Long getId() {
        return id;
    }

    protected final String formatFileUrl(String domain, String path) {
        return urlResolver.format(id, domain, FileStoragePaths.validate(path));
    }
}

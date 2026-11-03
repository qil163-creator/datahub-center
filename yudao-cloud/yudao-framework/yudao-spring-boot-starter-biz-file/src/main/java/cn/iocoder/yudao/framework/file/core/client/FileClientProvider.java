package cn.iocoder.yudao.framework.file.core.client;

/** 持久化或自定义存储扩展点；业务 Mapper 由提供方自行注入。 */
public interface FileClientProvider {
    boolean supports(Integer storage, FileClientConfig config);

    FileClient create(Long id, FileClientConfig config, FileUrlResolver urlResolver);
}

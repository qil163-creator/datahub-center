package cn.iocoder.yudao.framework.file.core.client;
/** 调用方定义文件访问地址；组件不绑定任何业务控制器。 */
@FunctionalInterface
public interface FileUrlResolver {
    String format(Long clientId, String domain, String path);
}

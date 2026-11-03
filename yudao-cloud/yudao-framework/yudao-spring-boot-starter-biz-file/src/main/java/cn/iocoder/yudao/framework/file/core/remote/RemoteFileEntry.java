package cn.iocoder.yudao.framework.file.core.remote;

/** path 为配置根目录下的虚拟绝对路径，修改时间为 UTC 毫秒时间戳。 */
public record RemoteFileEntry(String name, String path, boolean directory, long size, long modifiedTime) { }

package cn.iocoder.yudao.framework.dbswitch.common.entity;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/** 仅加载显式验证的制品序列；所有厂商类均以平台类加载器为父加载器。 */
public final class JarClassLoader extends URLClassLoader {

    /**
     * 初始化 {@code JarClassLoader}及其父类状态。
     */
    public JarClassLoader(List<Path> artifacts) {
        super(urls(artifacts), ClassLoader.getPlatformClassLoader());
    }

    /**
     * 返回当前驱动包或类加载器使用的资源地址集合。
     */
    private static URL[] urls(List<Path> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            throw new IllegalArgumentException("Driver artifact list is empty");
        }
        return artifacts.stream().map(path -> {
            try {
                return path.toUri().toURL();
            } catch (IOException failure) {
                throw new IllegalArgumentException("Invalid driver artifact URL");
            }
        }).toArray(URL[]::new);
    }

    /**
     * 注销当前类加载器或运行时拥有的 JDBC 驱动。
     */
    public void deregisterOwnedDrivers() throws ReflectiveOperationException, IOException {
        String name = "cn.iocoder.yudao.framework.dbswitch.driver.DriverManagerCleanup";
        Class<?> cleanup = findLoadedClass(name);
        if (cleanup == null) {
            try (var input = JarClassLoader.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                if (input == null) {
                    throw new IOException("Driver cleanup helper is unavailable");
                }
                byte[] bytes = input.readAllBytes();
                cleanup = defineClass(name, bytes, 0, bytes.length);
            }
        }
        cleanup.getMethod("deregisterOwnedDrivers").invoke(null);
    }
}

package cn.iocoder.yudao.framework.dbswitch.driver;

import java.util.List;

/** 部署持有的输入契约。驱动模块不依赖 Spring 配置。 */
public interface DriverBundleSpec {

    enum Lifecycle {PROCESS, LEASED}

    interface Artifact {

        /**
         * 返回文件。
         */
        String getFile();

        /**
         * 返回已验证驱动包的 SHA-256 摘要。
         */
        String getSha256();
    }

    /**
     * 返回路径。
     */
    String getPath();

    /**
     * 返回已验证驱动包的 SHA-256 摘要。
     */
    String getSha256();

    /**
     * 返回驱动类名。
     */
    String getDriverClassName();

    /**
     * 返回驱动包根。
     */
    String getBundleRoot();

    /**
     * 返回当前驱动包包含的文件清单。
     */
    List<? extends Artifact> getArtifacts();

    /**
     * 返回当前驱动运行时的生命周期状态。
     */
    Lifecycle getLifecycle();
}

package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.nio.file.Path;
import java.util.List;

/** 不可变声明；运行时加载使用这些文件单独验证过的私有快照。 */
public final class VerifiedDriverBundle {

    public record Artifact(Path path, String sha256, long bytes) {

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "DriverArtifact[sha256=" + sha256 + ", bytes=" + bytes + "]";
        }
    }

    private final ProductTypeEnum product;

    private final String driverClassName;

    private final List<Artifact> artifacts;

    private final String fingerprint;

    private final DriverBundleSpec.Lifecycle lifecycle;

    /**
     * 初始化 {@code VerifiedDriverBundle}。
     */
    VerifiedDriverBundle(ProductTypeEnum product, String driverClassName, List<Artifact> artifacts,
            String fingerprint, DriverBundleSpec.Lifecycle lifecycle) {
        this.product = product;
        this.driverClassName = driverClassName;
        this.artifacts = List.copyOf(artifacts);
        this.fingerprint = fingerprint;
        this.lifecycle = lifecycle;
    }

    /**
     * 返回数据库产品。
     */
    public ProductTypeEnum product() {
        return product;
    }

    /**
     * 返回驱动类名。
     */
    public String driverClassName() {
        return driverClassName;
    }

    /**
     * 返回驱动包文件集合。
     */
    public List<Artifact> artifacts() {
        return artifacts;
    }

    /**
     * 返回指纹。
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * 返回生命周期。
     */
    public DriverBundleSpec.Lifecycle lifecycle() {
        return lifecycle;
    }

    /**
     * 重新校验驱动包内容未发生变化。
     */
    public void verifyUnchanged() {
        DriverBundleVerifier.verifyArtifacts(artifacts, driverClassName);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "DriverBundle[product=" + product + ", fingerprint=" + fingerprint + "]";
    }
}

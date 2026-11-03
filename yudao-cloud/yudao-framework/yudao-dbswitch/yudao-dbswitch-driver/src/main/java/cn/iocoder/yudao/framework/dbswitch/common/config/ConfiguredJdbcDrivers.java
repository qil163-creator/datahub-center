package cn.iocoder.yudao.framework.dbswitch.common.config;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverBundleSpec;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverBundleVerifier;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverProfileSpec;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverBundle;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** 可信部署快照及其运行时所有者，不依赖 Spring 或业务配置。 */
public final class ConfiguredJdbcDrivers implements AutoCloseable {

    private final Map<ProductTypeEnum, Binding> drivers;

    private final Map<String, Binding> profiles;

    private final DriverRuntimeRegistry runtimes;

    /**
     * 使用给定参数初始化 {@code ConfiguredJdbcDrivers}。
     */
    public ConfiguredJdbcDrivers(Map<ProductTypeEnum, ? extends DriverBundleSpec> configuration) {
        this(configuration, Map.of());
    }

    /**
     * 使用给定参数初始化 {@code ConfiguredJdbcDrivers}。
     */
    public ConfiguredJdbcDrivers(Map<ProductTypeEnum, ? extends DriverBundleSpec> configuration,
            Map<String, ? extends DriverProfileSpec> profileConfiguration) {
        this(configuration, profileConfiguration,
                cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.defaults());
    }

    /**
     * 校验构造参数并初始化 {@code ConfiguredJdbcDrivers}。
     */
    public ConfiguredJdbcDrivers(Map<ProductTypeEnum, ? extends DriverBundleSpec> configuration,
            Map<String, ? extends DriverProfileSpec> profileConfiguration,
            cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget connectionBudget) {
        runtimes = new DriverRuntimeRegistry(java.util.Objects.requireNonNull(connectionBudget, "connection budget"));
        if (configuration == null) {
            throw new IllegalArgumentException("JDBC driver configuration must not be null");
        }
        java.util.Objects.requireNonNull(profileConfiguration, "driver profiles");
        var snapshot = new EnumMap<ProductTypeEnum, Binding>(ProductTypeEnum.class);
        var named = new java.util.LinkedHashMap<String, Binding>();
        configuration.forEach((product, config) -> {
            var bundle = DriverBundleVerifier.verify(product, config);
            var id = "default-" + product.name().toLowerCase(java.util.Locale.ROOT);
            var binding = new Binding(id, bundle, runtimes);
            snapshot.put(product, binding);
            named.put(id, binding);
        });
        profileConfiguration.forEach((id, config) -> {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("Invalid deployment driver profile ID");
            }
            var bundle = DriverBundleVerifier.verify(config.getProduct(), config);
            var binding = new Binding(id, bundle, runtimes);
            if (named.putIfAbsent(id, binding) != null) {
                throw new IllegalArgumentException("Duplicate driver profile ID");
            }
            if (config.isDefaultForProduct() && snapshot.putIfAbsent(config.getProduct(), binding) != null) {
                throw new IllegalArgumentException("Multiple default driver profiles for one product");
            }
        });
        drivers = Map.copyOf(snapshot);
        profiles = Map.copyOf(named);
    }

    /**
     * 根据给定参数创建 {@code ConfiguredJdbcDrivers} 空实例。
     */
    public static ConfiguredJdbcDrivers empty() {
        return new ConfiguredJdbcDrivers(Map.of());
    }

    /**
     * 返回当前查找。
     */
    public Optional<Binding> find(ProductTypeEnum product) {
        return Optional.ofNullable(drivers.get(product));
    }

    /**
     * 返回当前查找。
     */
    public Optional<Binding> find(ProductTypeEnum product, String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return find(product);
        }
        var binding = profiles.get(profileId);
        if (binding == null || binding.product() != product) {
            throw new IllegalArgumentException("Driver profile is unavailable for this database product");
        }
        return Optional.of(binding);
    }

    /**
     * 返回当前可用的驱动配置档案。
     */
    public java.util.List<Binding> profiles(ProductTypeEnum product) {
        return profiles.values().stream().filter(binding -> binding.product() == product)
                .sorted(java.util.Comparator.comparing(Binding::profileId)).toList();
    }

    /**
     * 返回{@code runtimes}。
     */
    public DriverRuntimeRegistry runtimes() {
        return runtimes;
    }

    /**
     * 关闭 {@code ConfiguredJdbcDrivers} 当前持有的资源。
     */
    @Override
    public void close() {
        runtimes.close();
    }

    public static final class Binding {

        private final String profileId;

        private final VerifiedDriverBundle bundle;

        private final DriverRuntimeRegistry owner;

        /**
         * 初始化 {@code Binding}。
         */
        private Binding(String profileId, VerifiedDriverBundle bundle, DriverRuntimeRegistry owner) {
            this.profileId = profileId;
            this.bundle = bundle;
            this.owner = owner;
        }

        /**
         * 返回配置档案编号。
         */
        public String profileId() {
            return profileId;
        }

        /**
         * 返回当前操作使用的数据库产品。
         */
        public ProductTypeEnum product() {
            return bundle.product();
        }

        /** 单制品便捷取值方法；多制品消费方必须使用制品包。 */
        public String path() {
            if (bundle.artifacts().size() != 1) {
                throw new IllegalStateException("Driver bundle contains multiple artifacts");
            }
            return bundle.artifacts().getFirst().path().toString();
        }

        /**
         * 返回驱动类名。
         */
        public String driverClassName() {
            return bundle.driverClassName();
        }

        /**
         * 根据当前内容计算结构指纹，供一致性比较使用。
         */
        public String fingerprint() {
            return bundle.fingerprint();
        }

        /**
         * 返回驱动包。
         */
        public VerifiedDriverBundle bundle() {
            return bundle;
        }

        /**
         * 重新校验驱动包内容未发生变化。
         */
        public void verifyUnchanged() {
            bundle.verifyUnchanged();
        }

        /**
         * 根据当前配置获取可用资源并登记其使用配额。
         */
        public DriverRuntimeRegistry.Lease acquire() {
            return owner.acquire(bundle);
        }

        /**
         * 根据当前配置获取可用资源并登记其使用配额。
         */
        public DriverRuntimeRegistry.Lease acquire(
                cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role role) {
            return owner.acquire(bundle, role);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "PinnedJdbcDriver[" + product() + ", fingerprint=" + fingerprint() + "]";
        }
    }
}

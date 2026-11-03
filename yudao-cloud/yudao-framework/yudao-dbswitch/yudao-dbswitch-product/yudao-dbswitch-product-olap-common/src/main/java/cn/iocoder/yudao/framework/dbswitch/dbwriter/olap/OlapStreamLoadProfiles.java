package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/** 按产品名注册的 Stream Load 配置。重复注册直接拒绝，且不缓存。 */
final class OlapStreamLoadProfiles {

    private static volatile Map<String, OlapStreamLoadProfile> registered;

    /**
     * 初始化 {@code OlapStreamLoadProfiles}。
     */
    private OlapStreamLoadProfiles() {
    }

    /**
     * 根据给定参数创建 {@code OlapStreamLoadProfile} 实例。
     */
    static OlapStreamLoadProfile of(String productName) {
        OlapStreamLoadProfile profile = productName == null ? null : registered().get(productName);
        return profile != null ? profile : new Unregistered(productName);
    }

    /**
     * 返回当前已注册的 Stream Load 产品配置。
     */
    private static Map<String, OlapStreamLoadProfile> registered() {
        Map<String, OlapStreamLoadProfile> current = registered;
        if (current == null) {
            synchronized (OlapStreamLoadProfiles.class) {
                current = registered;
                if (current == null) {
                    current = load();
                    registered = current;
                }
            }
        }
        return current;
    }

    /**
     * 通过 SPI 加载并登记 Stream Load 产品配置。
     */
    private static Map<String, OlapStreamLoadProfile> load() {
        Map<String, OlapStreamLoadProfile> profiles = new HashMap<>();
        for (OlapStreamLoadProfile profile : ServiceLoader.load(OlapStreamLoadProfile.class,
                OlapStreamLoadProfile.class.getClassLoader())) {
            String name = Objects.requireNonNull(profile.productName(), "Stream Load profile product name");
            if (profiles.putIfAbsent(name, profile) != null) {
                throw new IllegalStateException("Duplicate Stream Load profile " + name);
            }
        }
        return Map.copyOf(profiles);
    }

    /** 拒绝所有产品专有步骤，与未知产品名完全一致。 */
    private record Unregistered(String productName) implements OlapStreamLoadProfile {

        /**
         * 已观察原生配置的精确 FE/BE {@code Version}；其他版本全部拒绝。
         */
        @Override
        public String admittedVersion() {
            return null;
        }

        /**
         * 写入COPY能力。
         */
        @Override
        public OperationCapability writeCopyCapability() {
            return null;
        }

        /**
         * 计划 CREATE 语法：除共享标量外准入的文本或二进制标量类型。
         */
        @Override
        public String targetTextOrBinaryKind() {
            throw new IllegalArgumentException("Unsupported OLAP target definition");
        }

        /**
         * 计划 CREATE 语法：除 DUPLICATE 外准入的键表模型。
         */
        @Override
        public String targetKeyedModel() {
            throw new IllegalArgumentException("Unsupported OLAP target definition");
        }

        /**
         * 计划 CREATE 语法：拒绝超出此产品已验证原生类型范围的列类型。
         */
        @Override
        public void requireTargetNativeType(String type) {
            throw new IllegalArgumentException("Unsupported OLAP target definition");
        }
    }
}

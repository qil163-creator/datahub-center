package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMappings;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 每个所有者持有单个完整校验的快照。不允许进程全局变更、部分注册、
 * 吞掉提供方失败，或由最后一次写入覆盖产品。
 */
public final class DbSwitchProductRegistry {

    private static final Map<String, ProductTypeEnum> PRODUCT_ALIASES = Map.ofEntries(
            Map.entry("POSTGRES", ProductTypeEnum.POSTGRESQL), Map.entry("PG", ProductTypeEnum.POSTGRESQL),
            Map.entry("PGSQL", ProductTypeEnum.POSTGRESQL), Map.entry("MSSQL", ProductTypeEnum.SQLSERVER),
            Map.entry("SQLITE", ProductTypeEnum.SQLITE3), Map.entry("MARIA", ProductTypeEnum.MARIADB),
            Map.entry("DAMENG", ProductTypeEnum.DM), Map.entry("KINGBASE8", ProductTypeEnum.KINGBASE),
            Map.entry("KINGBASEES", ProductTypeEnum.KINGBASE), Map.entry("OB", ProductTypeEnum.OCEANBASE),
            Map.entry("GBASE", ProductTypeEnum.GBASE8A), Map.entry("HIVE2", ProductTypeEnum.HIVE),
            Map.entry("INFLUXDB", ProductTypeEnum.INFLUXDB3), Map.entry("ES", ProductTypeEnum.ELASTICSEARCH));

    private final Map<ProductTypeEnum, DbSwitchProductDescriptor> products;

    private final LogicalTypeMappings typeMappings;

    /**
     * 校验构造参数并初始化 {@code DbSwitchProductRegistry}。
     */
    public DbSwitchProductRegistry(Collection<DbSwitchProductDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        var snapshot = new EnumMap<ProductTypeEnum, DbSwitchProductDescriptor>(ProductTypeEnum.class);
        for (var descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (snapshot.putIfAbsent(descriptor.product(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate DB Switch product " + descriptor.product());
            }
        }
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("No DB Switch products were discovered");
        }
        products = Map.copyOf(snapshot);
        typeMappings = LogicalTypeMappings.of(snapshot.values().stream()
                .flatMap(descriptor -> descriptor.typeMapper().stream()).toList());
    }

    /** 由精确注册的描述符构成的不可变跨产品类型映射目录。 */
    public LogicalTypeMappings typeMappings() {
        return typeMappings;
    }

    /**
     * 返回当前产品已登记的类型映射实现。
     */
    public java.util.Optional<LogicalTypeMapper> typeMapper(ProductTypeEnum product) {
        return product(product).typeMapper();
    }

    /**
     * 根据当前输入构建并返回 {@code DbSwitchProductRegistry}。
     */
    public static DbSwitchProductRegistry discover(ClassLoader classLoader) {
        var descriptors = new java.util.ArrayList<DbSwitchProductDescriptor>();
        for (var provider : ServiceLoader.load(DbSwitchProductProvider.class,
                Objects.requireNonNull(classLoader, "classLoader"))) {
            descriptors.addAll(Objects.requireNonNull(provider.products(), "Provider products"));
        }
        return new DbSwitchProductRegistry(descriptors);
    }

    /** 非 Spring 调用方持有返回的不可变注册表，可在会话期间保留。 */
    public static DbSwitchProductRegistry discover() {
        var loader = Thread.currentThread().getContextClassLoader();
        return discover(loader == null ? DbSwitchProductRegistry.class.getClassLoader() : loader);
    }

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    public Collection<DbSwitchProductDescriptor> products() {
        return products.values();
    }

    /** 此源声明且由已安装目标 UPSERT 实现支持的方向。 */
    public Set<ProductTypeEnum> cursorTargets(ProductTypeEnum source) {
        var descriptor = product(source);
        if (!declared(descriptor.capabilities().get(DatabaseOperation.SYNC_CURSOR))) {
            return Set.of();
        }
        return descriptor.cursorTargets().stream().filter(target -> {
            var destination = products.get(target);
            return destination != null && declared(destination.capabilities().get(DatabaseOperation.WRITE_UPSERT));
        }).collect(Collectors.toUnmodifiableSet());
    }

    /** 平台准入检查；执行中仍必须检查原生驱动、关系和键值域。 */
    public void requireCursorPair(ProductTypeEnum source, ProductTypeEnum target) {
        product(source).require(DatabaseOperation.SYNC_CURSOR);
        product(target).require(DatabaseOperation.WRITE_UPSERT);
        if (!cursorTargets(source).contains(target)) {
            throw new IllegalArgumentException("Cursor product direction is not declared: " + source + "/" + target);
        }
    }

    /**
     * 构造已经声明但尚未证明的能力状态。
     */
    private static boolean declared(OperationCapability capability) {
        return capability.status() == OperationCapability.Status.DECLARED
                || capability.status() == OperationCapability.Status.VERIFIED;
    }

    /** 仅解析产品名称；共享协议或驱动类绝不能确定产品标识。 */
    public java.util.Optional<ProductTypeEnum> resolve(String code) {
        if (code == null || code.isBlank()) {
            return java.util.Optional.empty();
        }
        var normalized = code.toUpperCase(java.util.Locale.ROOT).replaceAll("[-_\\s]", "");
        ProductTypeEnum product = PRODUCT_ALIASES.get(normalized);
        if (product == null) {
            try {
                product = ProductTypeEnum.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return java.util.Optional.empty();
            }
        }
        return products.containsKey(product) ? java.util.Optional.of(product) : java.util.Optional.empty();
    }

    /**
     * 返回当前操作使用的数据库产品。
     */
    public DbSwitchProductDescriptor product(ProductTypeEnum type) {
        var descriptor = products.get(Objects.requireNonNull(type, "product"));
        if (descriptor == null) {
            throw new UnsupportedOperationException("Unregistered DB Switch product " + type);
        }
        return descriptor;
    }

    /**
     * 返回元数据。
     */
    public AbstractDatabase metadata(ProductTypeEnum product) {
        return product(product).metadata();
    }

    /**
     * 返回读取器。
     */
    public IDatabaseOperator reader(DataSource dataSource, ProductTypeEnum product) {
        return product(product).reader(dataSource);
    }

    /**
     * 返回写入器。
     */
    public IDatabaseWriter writer(DataSource dataSource, ProductTypeEnum product, WriteMode mode) {
        return product(product).writer(dataSource, mode);
    }

    /**
     * 根据源端和目标端能力创建数据同步器。
     */
    public IDatabaseSynchronize synchronizer(DataSource dataSource, ProductTypeEnum product) {
        return product(product).synchronizer(dataSource);
    }

    /**
     * 按目标产品和列定义创建更新或插入写入器。
     */
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter upsertWriter(DataSource dataSource,
            ProductTypeEnum product) {
        return product(product).upsertWriter(dataSource);
    }

    /**
     * 返回当前产品的原子批次写入实现。
     */
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.IAtomicBatchWriter atomicBatchWriter(
            DataSource dataSource, ProductTypeEnum product) {
        return product(product).atomicBatchWriter(dataSource);
    }
}

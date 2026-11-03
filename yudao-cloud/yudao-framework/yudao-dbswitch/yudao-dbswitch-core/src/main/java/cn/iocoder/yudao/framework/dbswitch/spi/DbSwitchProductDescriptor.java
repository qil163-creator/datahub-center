package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter;

import javax.sql.DataSource;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** 完整不可变产品注册。构造过程绝不打开数据库连接。 */
public final class DbSwitchProductDescriptor {

    private final ProductTypeEnum product;

    private final ConnectionProbe connectionProbe;

    private final CatalogMetadata catalogMetadata;

    private final Supplier<? extends AbstractDatabase> metadata;

    private final Function<DataSource, ? extends IDatabaseOperator> reader;

    private final Map<WriteMode, Function<DataSource, ? extends IDatabaseWriter>> writers;

    private final Function<DataSource, ? extends IDatabaseSynchronize> synchronizer;

    private final Function<DataSource, ? extends IDatabaseUpsertWriter> upsertWriter;

    private final Function<DataSource, ? extends cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.IAtomicBatchWriter> atomicBatchWriter;

    private final Map<DatabaseOperation, OperationCapability> capabilities;

    /** 可选跨产品类型映射组件；缺失时仍拒绝跨产品原生列。 */
    private final LogicalTypeMapper typeMapper;

    /** 产品持有的游标方向；每个方向仍要求原生键及结构预检查。 */
    private final Set<ProductTypeEnum> cursorTargets;

    /**
     * 使用给定参数初始化 {@code DbSwitchProductDescriptor}。
     */
    public DbSwitchProductDescriptor(ProductTypeEnum product,
            Supplier<? extends AbstractDatabase> metadata,
            Function<DataSource, ? extends IDatabaseOperator> reader,
            Map<WriteMode, Function<DataSource, ? extends IDatabaseWriter>> writers,
            Function<DataSource, ? extends IDatabaseSynchronize> synchronizer,
            Map<DatabaseOperation, OperationCapability> capabilities) {
        this(product, metadata, reader, writers, synchronizer, legacyCapabilities(capabilities), null, null, null,
                Set.of(), null, null);
    }

    /** 原有构造器缺少新增连接格时按不支持补齐，保留原操作契约。 */
    private static Map<DatabaseOperation, OperationCapability> legacyCapabilities(
            Map<DatabaseOperation, OperationCapability> input) {
        var result = new EnumMap<DatabaseOperation, OperationCapability>(DatabaseOperation.class);
        result.putAll(Objects.requireNonNull(input, "capabilities"));
        result.putIfAbsent(DatabaseOperation.CONNECT,
                OperationCapability.unsupported("CONNECTION_PROBE_NOT_REGISTERED"));
        return result;
    }

    /**
     * 校验构造参数并初始化 {@code DbSwitchProductDescriptor}。
     */
    private DbSwitchProductDescriptor(ProductTypeEnum product,
            Supplier<? extends AbstractDatabase> metadata,
            Function<DataSource, ? extends IDatabaseOperator> reader,
            Map<WriteMode, Function<DataSource, ? extends IDatabaseWriter>> writers,
            Function<DataSource, ? extends IDatabaseSynchronize> synchronizer,
            Map<DatabaseOperation, OperationCapability> capabilities,
            Function<DataSource, ? extends IDatabaseUpsertWriter> upsertWriter,
            Function<DataSource, ? extends cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.IAtomicBatchWriter> atomicBatchWriter,
            LogicalTypeMapper typeMapper,
            Set<ProductTypeEnum> cursorTargets, ConnectionProbe connectionProbe, CatalogMetadata catalogMetadata) {
        this.product = Objects.requireNonNull(product, "product");
        this.connectionProbe = connectionProbe;
        this.catalogMetadata = catalogMetadata;
        if (product == ProductTypeEnum.UNKNOWN) {
            throw new IllegalArgumentException("UNKNOWN cannot be registered as a product");
        }
        if (typeMapper != null && typeMapper.product() != product) {
            throw new IllegalArgumentException("Type mapper product mismatch " + product + "/" + typeMapper.product());
        }
        this.metadata = metadata;
        this.reader = reader;
        this.writers = Map.copyOf(Objects.requireNonNull(writers, "writers"));
        this.synchronizer = synchronizer;
        this.upsertWriter = upsertWriter;
        this.atomicBatchWriter = atomicBatchWriter;
        this.typeMapper = typeMapper;
        this.cursorTargets = Set.copyOf(Objects.requireNonNull(cursorTargets, "cursorTargets"));
        if (this.cursorTargets.contains(ProductTypeEnum.UNKNOWN)) {
            throw new IllegalArgumentException("UNKNOWN cannot be a cursor target");
        }
        var snapshot = new EnumMap<DatabaseOperation, OperationCapability>(DatabaseOperation.class);
        snapshot.putAll(Objects.requireNonNull(capabilities, "capabilities"));
        for (var operation : DatabaseOperation.values()) {
            if (!snapshot.containsKey(operation)) {
                throw new IllegalArgumentException("Missing capability " + product + "/" + operation);
            }
            Objects.requireNonNull(snapshot.get(operation), "capability");
        }
        this.capabilities = Map.copyOf(snapshot);
        if (!this.cursorTargets.isEmpty() && !supported(DatabaseOperation.SYNC_CURSOR)) {
            throw new IllegalArgumentException("Cursor directions require a source cursor implementation " + product);
        }
        validateFactory(DatabaseOperation.CONNECT, connectionProbe != null);
        validateFactory(DatabaseOperation.METADATA, metadata != null);
        validateFactory(DatabaseOperation.READ, reader != null);
        validateFactory(DatabaseOperation.WRITE_INSERT, this.writers.containsKey(WriteMode.INSERT));
        validateFactory(DatabaseOperation.WRITE_COPY, this.writers.containsKey(WriteMode.COPY));
        validateFactory(DatabaseOperation.SYNC_DIFF, synchronizer != null);
        validateFactory(DatabaseOperation.WRITE_UPSERT, upsertWriter != null);
        validateFactory(DatabaseOperation.WRITE_ATOMIC_BATCH, atomicBatchWriter != null);
        if (supported(DatabaseOperation.SYNC_FULL)) {
            requireFactory(reader, "Full synchronization requires a reader");
            if (writers.isEmpty()) {
                throw new IllegalArgumentException("Full synchronization requires a writer");
            }
        }
        if (supported(DatabaseOperation.SYNC_CURSOR)) {
            requireFactory(reader, "Cursor synchronization requires a reader");
            requireFactory(synchronizer, "Cursor synchronization requires a synchronizer");
        }
        if (supported(DatabaseOperation.DDL_CREATE) || supported(DatabaseOperation.DDL_ALTER)) {
            requireFactory(metadata, "DDL requires metadata/dialect factory");
        }
    }

    /**
     * 校验工厂。
     */
    private void validateFactory(DatabaseOperation operation, boolean exists) {
        if (supported(operation) != exists) {
            throw new IllegalArgumentException("Capability/factory mismatch " + product + "/" + operation);
        }
    }

    /**
     * 返回支持标记。
     */
    private boolean supported(DatabaseOperation operation) {
        var status = capabilities.get(operation).status();
        return status != OperationCapability.Status.UNSUPPORTED;
    }

    /**
     * 校验并取得工厂。
     */
    private static void requireFactory(Object factory, String message) {
        if (factory == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 返回数据库产品。
     */
    public ProductTypeEnum product() {
        return product;
    }

    /**
     * 返回能力信息。
     */
    public Map<DatabaseOperation, OperationCapability> capabilities() {
        return capabilities;
    }

    /**
     * 返回当前产品已登记的类型映射实现。
     */
    public java.util.Optional<LogicalTypeMapper> typeMapper() {
        return java.util.Optional.ofNullable(typeMapper);
    }

    /**
     * 返回游标目标集合。
     */
    public Set<ProductTypeEnum> cursorTargets() {
        return cursorTargets;
    }

    /** 携带类型映射器的不可变副本；绝不静默替换已有映射器。 */
    public DbSwitchProductDescriptor withTypeMapper(LogicalTypeMapper mapper) {
        Objects.requireNonNull(mapper, "typeMapper");
        if (typeMapper != null) {
            throw new IllegalArgumentException("Duplicate type mapper " + product);
        }
        return new DbSwitchProductDescriptor(product, metadata, reader, writers, synchronizer, capabilities,
                upsertWriter, atomicBatchWriter, mapper, cursorTargets, connectionProbe, catalogMetadata);
    }

    /** 可选 SDK 目录，不改变既有 JDBC METADATA 与 READ 能力声明。 */
    public java.util.Optional<CatalogMetadata> catalogMetadata() {
        return java.util.Optional.ofNullable(catalogMetadata);
    }

    /**
     * 拒绝当前实现未支持的 {@code require} 操作。
     */
    public void require(DatabaseOperation operation) {
        var capability = capabilities.get(Objects.requireNonNull(operation, "operation"));
        if (capability.status() == OperationCapability.Status.UNSUPPORTED) {
            throw new UnsupportedOperationException(product + "/" + operation + ": " + capability.reason());
        }
    }

    /**
     * 返回当前产品的连接探测实现。
     */
    public ConnectionProbe connectionProbe() {
        require(DatabaseOperation.CONNECT);
        return connectionProbe;
    }

    /**
     * 返回元数据。
     */
    public AbstractDatabase metadata() {
        require(DatabaseOperation.METADATA);
        return Objects.requireNonNull(metadata.get(), "Product metadata factory returned null");
    }

    /**
     * 返回读取器。
     */
    public IDatabaseOperator reader(DataSource dataSource) {
        require(DatabaseOperation.READ);
        return Objects.requireNonNull(reader.apply(Objects.requireNonNull(dataSource, "dataSource")),
                "Product reader factory returned null");
    }

    /**
     * 返回写入器。
     */
    public IDatabaseWriter writer(DataSource dataSource, WriteMode mode) {
        Objects.requireNonNull(mode, "mode");
        require(mode == WriteMode.INSERT ? DatabaseOperation.WRITE_INSERT : DatabaseOperation.WRITE_COPY);
        return Objects.requireNonNull(writers.get(mode).apply(Objects.requireNonNull(dataSource, "dataSource")),
                "Product writer factory returned null");
    }

    /**
     * 根据源端和目标端能力创建数据同步器。
     */
    public IDatabaseSynchronize synchronizer(DataSource dataSource) {
        require(DatabaseOperation.SYNC_DIFF);
        return Objects.requireNonNull(synchronizer.apply(Objects.requireNonNull(dataSource, "dataSource")),
                "Product synchronizer factory returned null");
    }

    /**
     * 返回当前构建器。
     */
    public static Builder builder(ProductTypeEnum product) {
        return new Builder(product);
    }

    /**
     * 返回当前产品的原子批次写入实现。
     */
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.IAtomicBatchWriter atomicBatchWriter(
            DataSource dataSource) {
        require(DatabaseOperation.WRITE_ATOMIC_BATCH);
        return Objects.requireNonNull(atomicBatchWriter.apply(Objects.requireNonNull(dataSource, "dataSource")),
                "Product atomic batch factory returned null");
    }

    /**
     * 按目标产品和列定义创建更新或插入写入器。
     */
    public IDatabaseUpsertWriter upsertWriter(DataSource dataSource) {
        require(DatabaseOperation.WRITE_UPSERT);
        return Objects.requireNonNull(upsertWriter.apply(Objects.requireNonNull(dataSource, "dataSource")),
                "Product upsert factory returned null");
    }

    /** 工厂和实现声明共同保留在产品边界。 */
    public static final class Builder {

        private final ProductTypeEnum product;

        private ConnectionProbe connectionProbe;

        private CatalogMetadata catalogMetadata;

        private Supplier<? extends AbstractDatabase> metadata;

        private Function<DataSource, ? extends IDatabaseOperator> reader;

        private final Map<WriteMode, Function<DataSource, ? extends IDatabaseWriter>> writers = new EnumMap<>(
                WriteMode.class);

        private Function<DataSource, ? extends IDatabaseSynchronize> synchronizer;

        private Function<DataSource, ? extends IDatabaseUpsertWriter> upsertWriter;

        private Function<DataSource, ? extends cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.IAtomicBatchWriter> atomicBatchWriter;

        private LogicalTypeMapper typeMapper;

        private final Set<ProductTypeEnum> cursorTargets = EnumSet.noneOf(ProductTypeEnum.class);

        private final Map<DatabaseOperation, OperationCapability> capabilities = new EnumMap<>(DatabaseOperation.class);

        /**
         * 初始化 {@code Builder}。
         */
        private Builder(ProductTypeEnum product) {
            this.product = product;
            for (var operation : DatabaseOperation.values()) {
                capabilities.put(operation, OperationCapability.unsupported("OPERATION_NOT_IMPLEMENTED"));
            }
        }

        /**
         * 打开构建器并准备后续访问。
         */
        public Builder connect(ConnectionProbe probe) {
            if (connectionProbe != null) {
                throw new IllegalArgumentException("重复的连接探测器");
            }
            connectionProbe = Objects.requireNonNull(probe, "connectionProbe");
            capabilities.put(DatabaseOperation.CONNECT, OperationCapability.declared());
            return this;
        }

        /**
         * 返回元数据。
         */
        public Builder metadata(Supplier<? extends AbstractDatabase> factory) {
            metadata = Objects.requireNonNull(factory, "metadata");
            capabilities.put(DatabaseOperation.METADATA, OperationCapability.declared());
            return this;
        }

        /** 注册独立只读目录；原有 JDBC 能力仍须单独注册与验证。 */
        public Builder catalogMetadata(CatalogMetadata catalog) {
            if (catalogMetadata != null) throw new IllegalArgumentException("重复的 SDK 目录读取器");
            catalogMetadata = Objects.requireNonNull(catalog, "catalogMetadata");
            return this;
        }

        /**
         * 返回读取器。
         */
        public Builder reader(Function<DataSource, ? extends IDatabaseOperator> factory) {
            reader = Objects.requireNonNull(factory, "reader");
            capabilities.put(DatabaseOperation.READ, OperationCapability.declared());
            return this;
        }

        /**
         * 返回写入器。
         */
        public Builder writer(WriteMode mode, Function<DataSource, ? extends IDatabaseWriter> factory) {
            if (writers.putIfAbsent(Objects.requireNonNull(mode, "mode"),
                    Objects.requireNonNull(factory, "writer")) != null) {
                throw new IllegalArgumentException("Duplicate writer mode " + product + "/" + mode);
            }
            capabilities.put(mode == WriteMode.INSERT ? DatabaseOperation.WRITE_INSERT : DatabaseOperation.WRITE_COPY,
                    OperationCapability.declared());
            return this;
        }

        /**
         * 根据源端和目标端能力创建数据同步器。
         */
        public Builder synchronizer(Function<DataSource, ? extends IDatabaseSynchronize> factory) {
            synchronizer = Objects.requireNonNull(factory, "synchronizer");
            capabilities.put(DatabaseOperation.SYNC_DIFF, OperationCapability.declared());
            return this;
        }

        /**
         * 为指定产品或操作登记能力声明。
         */
        public Builder declare(DatabaseOperation operation) {
            capabilities.put(Objects.requireNonNull(operation, "operation"), OperationCapability.declared());
            return this;
        }

        /** 带作用域的实现声明；原因属于公共能力契约的一部分。 */
        public Builder declare(DatabaseOperation operation, String scope) {
            capabilities.put(Objects.requireNonNull(operation, "operation"),
                    new OperationCapability(OperationCapability.Status.DECLARED, scope));
            return this;
        }

        /** 已探测的拒绝。状态仍为不支持；原因替换默认未实现文本。 */
        public Builder unsupported(DatabaseOperation operation, String reason) {
            capabilities.put(Objects.requireNonNull(operation, "operation"),
                    OperationCapability.unsupported(Objects.requireNonNull(reason, "reason")));
            return this;
        }

        /**
         * 返回当前构建。
         */
        public DbSwitchProductDescriptor build() {
            return new DbSwitchProductDescriptor(product, metadata, reader, writers, synchronizer, capabilities,
                    upsertWriter, atomicBatchWriter, typeMapper, cursorTargets, connectionProbe, catalogMetadata);
        }

        /** 声明已测试的产品方向，不声称每个服务器版本或键值域均已认证。 */
        public Builder cursorTargets(ProductTypeEnum... targets) {
            for (var target : Objects.requireNonNull(targets, "cursorTargets")) {
                Objects.requireNonNull(target, "cursorTarget");
                if (target == ProductTypeEnum.UNKNOWN || !cursorTargets.add(target)) {
                    throw new IllegalArgumentException("Invalid or duplicate cursor target " + product + "/" + target);
                }
            }
            return this;
        }

        /** 可选组件，不改变任何操作能力。 */
        public Builder typeMapper(LogicalTypeMapper mapper) {
            if (typeMapper != null) {
                throw new IllegalArgumentException("Duplicate type mapper " + product);
            }
            typeMapper = Objects.requireNonNull(mapper, "typeMapper");
            return this;
        }

        /**
         * 依据唯一键将数据更新到已有记录或插入新记录。
         */
        public Builder upsert(Function<DataSource, ? extends IDatabaseUpsertWriter> factory) {
            if (upsertWriter != null) {
                throw new IllegalArgumentException("Duplicate upsert writer " + product);
            }
            upsertWriter = Objects.requireNonNull(factory, "upsertWriter");
            capabilities.put(DatabaseOperation.WRITE_UPSERT, OperationCapability.declared());
            return this;
        }

        /**
         * 说明当前写入路径的批次原子性能力。
         */
        public Builder atomicBatch(
                Function<DataSource, ? extends cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.IAtomicBatchWriter> factory) {
            if (atomicBatchWriter != null) {
                throw new IllegalArgumentException("Duplicate atomic batch writer " + product);
            }
            atomicBatchWriter = Objects.requireNonNull(factory, "atomicBatchWriter");
            capabilities.put(DatabaseOperation.WRITE_ATOMIC_BATCH, OperationCapability.declared());
            return this;
        }
    }
}

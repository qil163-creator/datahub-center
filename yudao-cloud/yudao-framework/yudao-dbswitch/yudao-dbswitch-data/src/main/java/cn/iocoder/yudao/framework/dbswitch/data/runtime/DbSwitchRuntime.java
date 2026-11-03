package cn.iocoder.yudao.framework.dbswitch.data.runtime;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;

import java.util.Objects;
import javax.sql.DataSource;

/** 显式借用的运行时；全部操作结束后，由其管理方关闭驱动注册表。 */
public final class DbSwitchRuntime implements AutoCloseable {

    private final DbSwitchProductRegistry products;

    private final DbSwitchOptions options;

    private final ConfiguredJdbcDrivers drivers;

    private final boolean ownsDrivers;

    private final cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.AdmissionLimits queryLimits;

    private cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime queries;

    private cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionDiagnostics diagnostics;

    private cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionProbes probes;

    private boolean closed;

    /**
     * 使用给定参数初始化 {@code DbSwitchRuntime}。
     */
    public DbSwitchRuntime(DbSwitchProductRegistry products, DbSwitchOptions options, ConfiguredJdbcDrivers drivers) {
        this(products, options, drivers,
                cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.AdmissionLimits.defaults());
    }

    /**
     * 校验构造参数并初始化 {@code DbSwitchRuntime}。
     */
    public DbSwitchRuntime(DbSwitchProductRegistry products, DbSwitchOptions options, ConfiguredJdbcDrivers drivers,
            cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime.AdmissionLimits queryLimits) {
        this.products = Objects.requireNonNull(products, "products");
        this.options = Objects.requireNonNull(options, "options");
        this.ownsDrivers = drivers == null;
        this.drivers = ownsDrivers ? ConfiguredJdbcDrivers.empty() : drivers;
        this.queryLimits = Objects.requireNonNull(queryLimits, "queryLimits");
    }

    /**
     * 返回数据库产品集合。
     */
    public DbSwitchProductRegistry products() {
        return products;
    }

    /** SDK 目录独立于 JDBC 元数据；未注册产品明确拒绝。 */
    public cn.iocoder.yudao.framework.dbswitch.spi.CatalogMetadata catalogMetadata(ProductTypeEnum product) {
        checkOpen();
        return products.product(product).catalogMetadata()
                .orElseThrow(() -> new UnsupportedOperationException(product + "/SDK_CATALOG: OPERATION_NOT_IMPLEMENTED"));
    }

    /**
     * 返回配置选项。
     */
    public DbSwitchOptions options() {
        return options;
    }

    /**
     * 返回{@code drivers}。
     */
    public ConfiguredJdbcDrivers drivers() {
        return drivers;
    }

    /**
     * 生成或读取当前对象所需的查询语句集合。
     */
    public synchronized cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime queries() {
        if (closed) {
            throw new IllegalStateException("DB Switch runtime is closed");
        }
        if (queries == null) {
            queries = new cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime(this, queryLimits);
        }
        return queries;
    }

    /**
     * 关闭 {@code DbSwitchRuntime} 当前持有的资源。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (queries != null) {
            queries.close();
        }
        if (diagnostics != null) {
            diagnostics.close();
        }
        if (probes != null) {
            probes.close();
        }
        if (ownsDrivers) {
            drivers.close();
        }
    }

    /**
     * 根据当前输入构建并返回 {@code DbSwitchRuntime}。
     */
    public static DbSwitchRuntime standalone() {
        return new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), null);
    }

    /**
     * 返回元数据。
     */
    public MetaDataByDataSourceServiceImpl metadata(DataSource source, ProductTypeEnum product) {
        return new MetaDataByDataSourceServiceImpl(source, product, products);
    }

    /**
     * 打开数据库连接。
     */
    public java.sql.Connection openConnection(ProductTypeEnum product, String profileId,
            String url, String user, String password) throws java.sql.SQLException {
        checkOpen();
        return openBound(product, drivers.find(product, profileId).orElse(null), url, user, password,
                cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.WORKLOAD);
    }

    /** 独占诊断会话；默认只读，不执行调用方配置的任意校验 SQL。 */
    public cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult diagnoseConnection(
            ProductTypeEnum product, String profileId, String jdbcUrl, String username, String password,
            cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions diagnosticOptions) {
        var binding = drivers.find(product, profileId).orElse(null);
        cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionDiagnostics executor;
        synchronized (this) {
            checkOpen();
            if (diagnostics == null) {
                diagnostics = new cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionDiagnostics();
            }
            executor = diagnostics;
        }
        return executor.diagnose(product, products.metadata(product),
                (url, user, secret) -> openBound(product, binding, url, user, secret,
                        cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.METADATA),
                (url, user, secret) -> openBound(product, binding, url, user, secret,
                        cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.WORKLOAD),
                jdbcUrl, username, password, diagnosticOptions);
    }

    /** 仅探测连接和目标授权，不调用元数据工厂或自动推断额外能力。 */
    public cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult probeConnection(
            cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest request) {
        cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionProbes executor;
        synchronized (this) {
            checkOpen();
            if (probes == null) {
                probes = new cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionProbes();
            }
            executor = probes;
        }
        return executor.probe(request, products.product(request.product()).connectionProbe(),
                () -> openBound(request.product(), drivers.find(request.product(), request.profileId()).orElse(null),
                        request.jdbcUrl(), request.username(), request.password(),
                        cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.METADATA));
    }

    /**
     * 检查当前资源尚未关闭且仍可使用。
     */
    private synchronized void checkOpen() {
        if (closed) {
            throw new IllegalStateException("DB Switch runtime is closed");
        }
    }

    /**
     * 打开边界。
     */
    private java.sql.Connection openBound(ProductTypeEnum product, ConfiguredJdbcDrivers.Binding binding,
            String url, String user, String password,
            cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role role) throws java.sql.SQLException {
        checkOpen();
        var connection = cn.iocoder.yudao.framework.dbswitch.driver.DriverConnections.open(
                product, binding, drivers.runtimes(), url, user, password, role);
        synchronized (this) {
            if (!closed) {
                return connection;
            }
        }
        // 阻塞的 JDBC 调用不得持有运行时监视器；关闭后绝不交出
        // 延迟创建的连接；物理关闭结果未知时，DriverConnections 保留其租约。
        try {
            connection.close();
        } catch (java.sql.SQLException | RuntimeException ignored) {
            throw new java.sql.SQLException("DB Switch runtime closed while connecting; cleanup is unconfirmed");
        }
        throw new java.sql.SQLException("DB Switch runtime closed while connecting");
    }

    /**
     * 通过当前连接读取 JDBC 元数据。
     */
    public cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl jdbcMetadata(
            ProductTypeEnum product) {
        return jdbcMetadata(product, null);
    }

    /**
     * 通过当前连接读取 JDBC 元数据。
     */
    public cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl jdbcMetadata(
            ProductTypeEnum product, String profileId) {
        checkOpen();
        var binding = drivers.find(product, profileId).orElse(null);
        return new cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl(product, products,
                (url, user, password) -> openBound(product, binding, url, user, password,
                        cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.METADATA),
                (url, user, password) -> openBound(product, binding, url, user, password,
                        cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Role.WORKLOAD));
    }

    /**
     * 返回读取器。
     */
    public IDatabaseOperator reader(DataSource source, ProductTypeEnum product) {
        var reader = products.reader(source, product);
        reader.configure(options);
        return reader;
    }

    /**
     * 返回写入器。
     */
    public IDatabaseWriter writer(DataSource source, ProductTypeEnum product, WriteMode mode) {
        var writer = products.writer(source, product, mode);
        writer.configure(options);
        return writer;
    }

    /**
     * 根据源端和目标端能力创建数据同步器。
     */
    public IDatabaseSynchronize synchronizer(DataSource source, ProductTypeEnum product) {
        var synchronizer = products.synchronizer(source, product);
        synchronizer.configure(options);
        return synchronizer;
    }

    /**
     * 根据当前输入构建并返回 {@code cn.iocoder.yudao.framework.dbswitch.core.service.GuardedTableMutationExecutor}。
     */
    public cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult guardedMutate(
            DataSource source, ProductTypeEnum product,
            cn.iocoder.yudao.framework.dbswitch.core.service.GuardedTableMutation mutation) {
        checkOpen();
        return new cn.iocoder.yudao.framework.dbswitch.core.service.GuardedTableMutationExecutor().mutate(source,
                product, mutation);
    }

    /**
     * 按目标产品和列定义创建更新或插入写入器。
     */
    public cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseUpsertWriter upsertWriter(DataSource source,
            ProductTypeEnum product) {
        var writer = products.upsertWriter(source, product);
        writer.configure(options);
        return writer;
    }
}

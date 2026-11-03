package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverNativeAccess;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** 运行时共享的通用诊断执行器；每次调用拥有独占连接，不保存凭据或诊断结果。 */
public final class ConnectionDiagnostics implements AutoCloseable {

    private final DiagnosticIo io;

    private final long totalMillis;

    /**
     * 使用给定参数初始化 {@code ConnectionDiagnostics}。
     */
    public ConnectionDiagnostics() {
        this(new DiagnosticIo(4, 4), 30_000);
    }

    /**
     * 初始化 {@code ConnectionDiagnostics}。
     */
    ConnectionDiagnostics(DiagnosticIo io, long totalMillis) {
        this.io = io;
        this.totalMillis = totalMillis;
    }

    /**
     * 根据当前输入构建并返回 {@code Outcome}。
     */
    public ConnectionDiagnosticResult diagnose(ProductTypeEnum product, AbstractDatabase database,
            JdbcConnectionProvider metadataProvider, JdbcConnectionProvider writeProvider,
            String jdbcUrl, String username, String password, ConnectionDiagnosticOptions options) {
        Objects.requireNonNull(product);
        Objects.requireNonNull(database);
        Objects.requireNonNull(metadataProvider);
        Objects.requireNonNull(writeProvider);
        Objects.requireNonNull(options);
        var run = new Run(options, totalMillis);
        boolean configured = Boolean.TRUE.equals(run.step("BASIC", "CONFIG", "连接配置检查", () -> {
            String template = product.getUrl();
            int prefixEnd = template == null ? -1 : template.indexOf(':', 5);
            if (prefixEnd < 0 || jdbcUrl == null || jdbcUrl.length() > 16_384
                    || !jdbcUrl.startsWith(template.substring(0, prefixEnd + 1))
                    || jdbcUrl.chars().anyMatch(Character::isISOControl)) {
                return new Outcome<>(false, "FAIL", "JDBC 协议与数据库产品不一致，或连接配置超出安全边界");
            }
            return pass(true, "已核对数据库产品、JDBC 协议及配置边界；账号有效性由认证检查确认");
        }));
        if (configured) {
            network(run, product, jdbcUrl);
        } else {
            run.skip("BASIC", "DNS", "JDBC 目标解析", "连接配置检查未通过");
            run.skip("BASIC", "TCP", "JDBC 目标端口", "连接配置检查未通过");
        }
        Connection connection = configured ? run.step("BASIC", "AUTH", "连接与认证", () -> {
            Connection opened = io.call(run.timeout(), () -> metadataProvider.open(jdbcUrl, username, password),
                    ConnectionDiagnostics::closeLate);
            return pass(opened, "已建立数据库会话并完成驱动认证");
        }) : null;
        if (!configured) {
            run.skip("BASIC", "AUTH", "连接与认证", "连接配置检查未通过");
        }
        boolean valid = false, scope = false;
        if (connection != null) {
            try {
                valid = Boolean.TRUE.equals(run.jdbc(connection, "TARGET", "VALIDATION", "服务器响应", () -> {
                    database.probeConnection(connection, run.seconds());
                    return pass(true, "产品固定只读探针已取得服务器响应");
                }));
                String schema = null;
                if (valid) {
                    schema = run.jdbc(connection, "TARGET", "SCHEMA", "目标命名空间", () -> {
                        String target = options.schemaName();
                        if (target == null) {
                            target = product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.MARIADB
                                    || product == ProductTypeEnum.TIDB ? connection.getCatalog() : connection.getSchema();
                        }
                        if (target == null || target.isBlank()) {
                            return skipped("驱动未提供可验证的默认命名空间");
                        }
                        List<String> schemas = database.querySchemaList(connection);
                        if (schemas == null) {
                            return warn(null, "驱动未返回命名空间信息");
                        }
                        if (!schemas.contains(target)) {
                            throw new SQLException("目标命名空间不存在或不可见", "3F000");
                        }
                        database.probeNamespace(connection, target, run.seconds());
                        return pass(target, "已确认目标命名空间存在且当前会话具有使用权限");
                    });
                } else {
                    run.skip("TARGET", "SCHEMA", "目标命名空间", "服务器验证未成功");
                }
                scope = schema != null;
                String privilegeScope = schema == null ? options.schemaName() : schema;
                if (valid && privilegeScope != null) {
                    run.jdbc(connection, "TARGET", "PRIVILEGES", "账号角色与权限", () -> {
                        database.probeAccountPrivileges(connection, privilegeScope, run.seconds());
                        return pass(true,
                                "已读取当前账号的产品权限信息并验证目标资源访问；不代表任意业务表写权限");
                    });
                } else {
                    run.skip("TARGET", "PRIVILEGES", "账号角色与权限", "没有可验证的目标命名空间");
                }
                read(run, product, database, connection, schema);
                if (valid) {
                    run.jdbc(connection, "ADVANCED", "CDC", "CDC 日志与复制权限", () -> {
                        var readiness = database.probeCdcReadiness(connection, run.seconds());
                        if (readiness == null) {
                            return warn(null, "未取得完整 CDC 前置条件信息");
                        }
                        if (!readiness.logConfigurationReady() || !readiness.accountPrivilegeReady()) {
                            String reason = !readiness.logConfigurationReady() && !readiness.accountPrivilegeReady()
                                    ? "日志配置与当前账号复制角色均未满足" : !readiness.logConfigurationReady()
                                    ? "日志配置未满足" : "当前账号没有复制角色权限";
                            return warn(false, reason + "；CDC 前置条件未就绪，未创建复制槽或启动复制流");
                        }
                        return pass(true,
                                "CDC 日志配置与账号复制角色前置条件满足；未验证复制槽、网络认证或目标表复制权限");
                    });
                } else {
                    run.skip("ADVANCED", "CDC", "CDC 日志与复制权限", "服务器验证未成功");
                }
            } finally {
                cleanup(run, connection, false);
            }
        } else {
            run.skip("TARGET", "VALIDATION", "服务器响应", "连接未建立");
            run.skip("TARGET", "SCHEMA", "目标命名空间", "连接未建立");
            run.skip("TARGET", "PRIVILEGES", "账号角色与权限", "连接未建立");
            skipRead(run, "连接未建立");
            run.skip("ADVANCED", "CDC", "CDC 日志与复制权限", "连接未建立");
        }
        if (options.writeChecks() && valid && scope) {
            write(run, database, writeProvider, jdbcUrl, username, password);
        } else {
            skipWrite(run, options.writeChecks() ? "连接或目标验证未成功" : "未启用临时表写入检查",
                    options.writeChecks() ? "PREREQUISITE_FAILED" : "NOT_AUTHORIZED");
        }
        return run.result(connection != null && valid && scope);
    }

    /**
     * 仅探测同一主机的一条已解析地址；不宣称所有故障切换地址都可达。
     */
    private void network(Run run, ProductTypeEnum product, String url) {
        var endpoint = DiagnosticEndpoint.parse(product, url);
        if (endpoint.isEmpty()) {
            String reason = product == ProductTypeEnum.SQLITE3 ? "文件数据库不适用 DNS/TCP 检查"
                    : "无法证明单一 JDBC 网络目标，未执行独立 DNS/TCP 检查";
            String reasonCode = product == ProductTypeEnum.SQLITE3 ? "NOT_APPLICABLE" : "UNVERIFIED";
            run.skip("BASIC", "DNS", "JDBC 目标解析", reason, reasonCode);
            run.skip("BASIC", "TCP", "JDBC 目标端口", reason, reasonCode);
            return;
        }
        InetAddress[] addresses = run.step("BASIC", "DNS", "JDBC 目标解析", () -> {
            var resolved = io.call(run.timeout(), () -> InetAddress.getAllByName(endpoint.get().host()), value -> {
            });
            if (resolved.length == 0) {
                throw new UnknownHostException();
            }
            return pass(resolved, "已解析 JDBC URL 中的目标主机");
        });
        if (addresses == null) {
            run.skip("BASIC", "TCP", "JDBC 目标端口", "目标主机解析未成功");
            return;
        }
        run.step("BASIC", "TCP", "JDBC 目标端口", () -> {
            int timeout = run.timeout();
            return io.call(timeout, () -> {
                // 仅探测同一主机的一条已解析地址；不宣称所有故障切换地址都可达。
                try (var socket = new Socket()) {
                    socket.connect(new InetSocketAddress(addresses[0], endpoint.get().port()), timeout);
                    return pass(true, "JDBC 目标的一条解析地址可建立 TCP 连接");
                }
            }, value -> {
            });
        });
    }

    /**
     * 在诊断预算内探测元数据和数据读取能力，记录各阶段结果。
     */
    private void read(Run run, ProductTypeEnum product, AbstractDatabase database, Connection connection,
            String schema) {
        if (schema == null) {
            skipRead(run, "目标命名空间未确认");
            return;
        }
        List<TableDescription> tables = run.jdbc(connection, "METADATA", "TABLES", "基本表目录", () -> {
            var listed = database.queryCatalogTableList(connection, schema, 1000, run.seconds());
            if (listed == null) {
                return warn(null, "驱动未返回表目录信息");
            }
            var eligible = listed.stream().filter(table -> "TABLE".equals(table.getTableType())
                    && schema.equals(table.getSchemaName()) && table.getTableName() != null).toList();
            return pass(eligible,
                    eligible.isEmpty() ? "目录读取成功，未发现可采样的基本表" : "已读取基本表目录（最多 1000 项）");
        });
        if (tables == null || tables.isEmpty()) {
            String reason = tables == null ? "基本表目录检查未成功，无法确定采样对象" : "目录读取成功，没有基本表可供采样";
            String reasonCode = tables == null ? "PREREQUISITE_FAILED" : "NO_SAMPLE";
            run.skip("METADATA", "COLUMNS", "字段结构", reason, reasonCode);
            run.skip("METADATA", "PRIMARY_KEYS", "主键结构", reason, reasonCode);
            run.skip("METADATA", "EXTENDED_PROPERTIES", "扩展属性读取", reason, reasonCode);
            run.skip("READ", "ZERO_ROW_READ", "零行读取", reason, reasonCode);
            return;
        }
        String table = tables.getFirst().getTableName();
        String sql = run.jdbc(connection, "READ", "TABLE_REFERENCE", "采样表安全引用", () ->
                pass("SELECT * FROM " + database.diagnosticTableReference(connection, schema, table, run.seconds())
                        + " WHERE 1=0", "已按当前连接验证采样表的物理限定名"));
        if (sql == null) {
            run.skip("METADATA", "COLUMNS", "字段结构", "此产品或物理名称尚未验证安全读取");
            run.skip("METADATA", "PRIMARY_KEYS", "主键结构", "此产品或物理名称尚未验证安全读取");
            run.skip("METADATA", "EXTENDED_PROPERTIES", "扩展属性读取", "此产品或物理名称尚未验证安全读取");
            run.skip("READ", "ZERO_ROW_READ", "零行读取", "此产品或物理名称尚未验证安全读取");
            return;
        }
        run.jdbc(connection, "METADATA", "COLUMNS", "字段结构", () -> {
            try (var statement = connection.prepareStatement(sql)) {
                timeout(statement, run.seconds());
                var fields = statement.getMetaData();
                if (fields == null || fields.getColumnCount() < 1 || fields.getColumnCount() > 2000) {
                    return warn(null, "驱动未提供有界且完整的采样表字段结构");
                }
                for (int column = 1; column <= fields.getColumnCount(); column++) {
                    String name = fields.getColumnName(column), type = fields.getColumnTypeName(column);
                    if (name == null || name.isBlank() || type == null || type.isBlank()
                            || fields.getColumnType(column) == java.sql.Types.NULL) {
                        return warn(null, "采样表字段名称或类型元数据不完整");
                    }
                }
                return pass(true, "已通过安全零行语句取得采样表字段名称及类型");
            }
        });
        run.jdbc(connection, "METADATA", "PRIMARY_KEYS", "主键结构", () -> {
            var keys = database.queryTablePrimaryKeys(connection, schema, table);
            if (keys == null) {
                return warn(null, "驱动未提供可验证的主键元数据结果");
            }
            if (keys.isEmpty()) {
                return pass(true, "已读取主键元数据，采样表未声明主键");
            }
            return pass(true, "已取得采样表的有序主键结构");
        });
        run.jdbc(connection, "METADATA", "EXTENDED_PROPERTIES", "扩展属性读取", () -> {
            var properties = database.probeExtendedProperties(connection, schema, table, run.seconds());
            if (properties == null || properties.columnsRead() < 1) {
                return warn(null, "未取得完整扩展属性目录信息");
            }
            return pass(true, "已读取表及 " + properties.columnsRead() + " 个字段的注释目录；表注释"
                    + (properties.tableCommentPresent() ? "已设置" : "未设置") + "，字段注释 " + properties.columnCommentsPresent() + " 项");
        });
        run.jdbc(connection, "READ", "ZERO_ROW_READ", "零行读取", () -> {
            try (var statement = connection.prepareStatement(sql)) {
                timeout(statement, run.seconds());
                statement.setMaxRows(1);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        throw new SQLException("零行探针返回了意外行");
                    }
                }
                return pass(true, "采样基本表的零行查询成功，未读取或返回业务数据");
            }
        });
    }

    /**
     * 通过临时目标计划探测数据写入能力并记录诊断结果。
     */
    private void write(Run run, AbstractDatabase database, JdbcConnectionProvider provider,
            String url, String user, String password) {
        TemporaryTableDiagnosticPlan plan;
        try {
            plan = database.temporaryTableDiagnosticPlan(
                    "dbswitch_diag_" + UUID.randomUUID().toString().replace("-", ""));
        } catch (SQLException | UnsupportedOperationException unsupported) {
            skipWrite(run, "此产品的会话临时表写入诊断尚未验证", "NOT_IMPLEMENTED");
            return;
        }
        Connection connection = run.step("WRITE", "TEMP_CREATE", "创建诊断临时表", () -> {
            var opened = io.call(run.timeout(), () -> provider.open(url, user, password),
                    ConnectionDiagnostics::closeLate);
            try {
                run.nativeCall(opened, () -> {
                    if (!opened.getAutoCommit()) {
                        throw new SQLException("诊断要求独立自动提交会话");
                    }
                    database.validateTemporaryTableDiagnosticConnection(opened, run.seconds());
                    if (run.options.schemaName() != null) {
                        database.probeNamespace(opened, run.options.schemaName(), run.seconds());
                    }
                    execute(opened, plan.createSql(), run.seconds());
                    return null;
                });
                return pass(opened, "已在独立会话创建随机临时表；不证明普通业务表建表权限");
            } catch (Exception | LinkageError failure) {
                cleanup(run, opened, true);
                throw failure;
            }
        });
        if (connection == null) {
            skipWriteOperations(run, "临时表未成功创建");
            return;
        }
        String relation = plan.tableReference();
        try {
            Boolean inserted = run.jdbc(connection, "WRITE", "INSERT", "临时表插入", () -> {
                int changed = execute(connection,
                        "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (1,'diagnostic')", run.seconds());
                requireRows(changed, 1);
                return pass(true, "诊断临时表单行插入成功");
            });
            run.jdbc(connection, "WRITE", "BATCH_INSERT", "临时表批量插入", () -> {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (?,?)")) {
                    timeout(statement, run.seconds());
                    for (int id = 2; id <= 3; id++) {
                        statement.setInt(1, id);
                        statement.setString(2, "batch");
                        statement.addBatch();
                    }
                    int[] counts = statement.executeBatch();
                    if (counts.length != 2) {
                        throw new SQLException("批量执行结果不完整");
                    }
                    for (int count : counts) {
                        if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                            throw new SQLException("批量插入失败");
                        }
                    }
                    requireRows(count(connection, relation, "probe_id IN (2,3)", run.seconds()), 2);
                    return pass(true, "诊断临时表批量插入并核对两行成功");
                }
            });
            if (Boolean.TRUE.equals(inserted)) {
                run.jdbc(connection, "WRITE", "UPDATE", "临时表更新", () -> {
                    requireRows(
                            execute(connection, "UPDATE " + relation + " SET probe_value='updated' WHERE probe_id=1",
                                    run.seconds()), 1);
                    requireRows(count(connection, relation, "probe_id=1 AND probe_value='updated'", run.seconds()), 1);
                    return pass(true, "诊断临时表更新并核对成功");
                });
                run.jdbc(connection, "WRITE", "DELETE", "临时表删除行", () -> {
                    requireRows(execute(connection, "DELETE FROM " + relation + " WHERE probe_id=1", run.seconds()), 1);
                    requireRows(count(connection, relation, "probe_id=1", run.seconds()), 0);
                    return pass(true, "诊断临时表删除行并核对成功");
                });
            } else {
                run.skip("WRITE", "UPDATE", "临时表更新", "单行插入未成功");
                run.skip("WRITE", "DELETE", "临时表删除行", "单行插入未成功");
            }
            if (plan.upsertSql() == null) {
                run.skip("WRITE", "UPSERT", "临时表冲突更新", "此产品尚未提供已验证的 UPSERT 诊断计划", unsupportedReason(plan));
            } else {
                run.jdbc(connection, "WRITE", "UPSERT", "临时表冲突更新", () -> {
                    execute(connection, "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (4,'initial')",
                            run.seconds());
                    // MySQL 冲突更新报告两行，PostgreSQL 报告一行；以隔离表的实际最终值核验。
                    execute(connection, plan.upsertSql(), run.seconds());
                    requireRows(count(connection, relation, "probe_id=4 AND probe_value='upserted'", run.seconds()), 1);
                    return pass(true, "诊断临时表 UPSERT 实际冲突更新并核对成功");
                });
            }
            if (plan.mergeSql() == null) {
                run.skip("WRITE", "MERGE", "临时表合并写入", "此产品尚未提供已验证的 MERGE 诊断计划", unsupportedReason(plan));
            } else {
                run.jdbc(connection, "WRITE", "MERGE", "临时表合并写入", () -> {
                    if (plan.mergeMinimumMajorVersion() > 0
                            && connection.getMetaData().getDatabaseMajorVersion() < plan.mergeMinimumMajorVersion()) {
                        return new Outcome<>(null, "SKIPPED", "服务器版本尚不支持此 MERGE 诊断语句", "NOT_APPLICABLE");
                    }
                    execute(connection, "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (5,'initial')",
                            run.seconds());
                    requireRows(execute(connection, plan.mergeSql(), run.seconds()), 2);
                    requireRows(count(connection, relation,
                            "(probe_id=5 AND probe_value='merged') OR (probe_id=6 AND probe_value='inserted')",
                            run.seconds()), 2);
                    return pass(true, "诊断临时表 MERGE 匹配更新及未匹配插入两个分支均执行并核对成功");
                });
            }
            if (plan.truncateSql() == null) {
                run.skip("WRITE", "TRUNCATE", "清空诊断临时表", "此产品尚未提供已验证的 TRUNCATE 诊断计划", unsupportedReason(plan));
            } else {
                run.jdbc(connection, "WRITE", "TRUNCATE", "清空诊断临时表", () -> {
                    execute(connection, "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (8,'truncate')",
                            run.seconds());
                    execute(connection, plan.truncateSql(), run.seconds());
                    requireRows(count(connection, relation, "1=1", run.seconds()), 0);
                    return pass(true, "已对非空诊断临时表执行 TRUNCATE 并核对零行；不代表普通表清空权限");
                });
            }
            if (!plan.transactionSupported()) {
                run.skip("ADVANCED", "TRANSACTION", "临时表事务回滚", "此产品临时表未证明原生事务回滚语义", "NOT_APPLICABLE");
            } else {
                run.step("ADVANCED", "TRANSACTION", "临时表事务回滚", () -> {
                    var needsRollback = new java.util.concurrent.atomic.AtomicBoolean();
                    try {
                        return run.nativeCall(connection, () -> {
                            needsRollback.set(true);
                            if (plan.beginSql() == null) {
                                connection.setAutoCommit(false);
                            } else {
                                execute(connection, plan.beginSql(), run.seconds());
                            }
                            execute(connection, "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (9,'rollback')",
                                    run.seconds());
                            rollback(connection, plan, run.seconds());
                            requireRows(count(connection, relation, "probe_id=9", run.seconds()), 0);
                            if (plan.beginSql() == null) {
                                connection.setAutoCommit(true);
                            }
                            needsRollback.set(false);
                            return pass(true, "独立会话内临时表事务回滚并核对成功");
                        });
                    } finally {
                        if (needsRollback.get()) {
                            // 退出原诊断原生调用后，以独立预算恢复事务；超时隔离连接仍会在最外层关闭。
                            var recovery = new Run(run.options, run.options.timeoutMillis());
                            recovery.nativeCall(connection, () -> {
                                rollback(connection, plan, recovery.seconds());
                                if (plan.beginSql() == null) {
                                    connection.setAutoCommit(true);
                                }
                                return null;
                            });
                        }
                    }
                });
            }
            if (plan.alterSql() == null) {
                run.skip("ADVANCED", "ALTER_TEMP", "临时表结构变更", "此产品临时表不提供结构变更诊断", unsupportedReason(plan));
            } else {
                run.jdbc(connection, "ADVANCED", "ALTER_TEMP", "临时表结构变更", () -> {
                    execute(connection, plan.alterSql(), run.seconds());
                    try (var statement = connection.prepareStatement(
                            "SELECT probe_extra FROM " + relation + " WHERE 1=0")) {
                        timeout(statement, run.seconds());
                        try (var ignored = statement.executeQuery()) { /* 验证新列可引用。 */ }
                    }
                    return pass(true, "诊断临时表新增列并验证成功；不证明普通表变更权限");
                });
            }
            if (plan.renameSql() == null) {
                run.skip("ADVANCED", "RENAME_TEMP", "重命名诊断临时表", "此产品临时表不提供重命名诊断", unsupportedReason(plan));
            } else {
                run.jdbc(connection, "ADVANCED", "RENAME_TEMP", "重命名诊断临时表", () -> {
                    execute(connection, plan.renameSql(), run.seconds());
                    count(connection, plan.renamedTableReference(), "1=1", run.seconds());
                    return pass(true, "诊断临时表重命名并通过新名称验证成功；不证明普通表重命名权限");
                });
            }
        } finally {
            // 临时对象清理使用独立预算；业务诊断耗尽期限也必须尝试安全 DROP，随后无条件关闭会话。
            var cleanupRun = new Run(run.options, run.options.timeoutMillis());
            try {
                cleanupRun.jdbc(connection, "ADVANCED", "DROP_TEMP", "删除诊断临时表", () -> {
                    execute(connection, plan.dropSql(), cleanupRun.seconds());
                    if (plan.dropRenamedSql() != null) {
                        execute(connection, plan.dropRenamedSql(), cleanupRun.seconds());
                    }
                    return pass(true, "已执行诊断临时表删除");
                });
                run.checks.addAll(cleanupRun.checks);
            } finally {
                cleanup(run, connection, true);
            }
        }
    }

    /**
     * 先无条件向驱动关闭队列提交清理，等待预算耗尽不能撤销资源释放。
     */
    private void cleanup(Run run, Connection connection, boolean write) {
        run.step("ADVANCED", write ? "WRITE_CLEANUP" : "CLEANUP", "会话资源清理", () -> {
            // 先无条件向驱动关闭队列提交清理，等待预算耗尽不能撤销资源释放。
            var completion = DriverNativeAccess.requestClose(connection).toCompletableFuture();
            int wait = run.options.timeoutMillis();
            completion.get(wait, TimeUnit.MILLISECONDS);
            return pass(true, (write ? "临时表会话" : "只读会话") + "已关闭");
        }, true);
    }

    /**
     * 在异步资源真正退出后补充释放其持有的资源。
     */
    private static void closeLate(Connection connection) {
        try {
            DriverNativeAccess.requestClose(connection);
        } catch (SQLException ignored) { /* 未确认资源仍由驱动注册表隔离持有。 */ }
    }

    private static String unsupportedReason(TemporaryTableDiagnosticPlan plan) {
        return plan.unsupportedOperationsNotApplicable() ? "NOT_APPLICABLE" : "NOT_IMPLEMENTED";
    }

    private static void rollback(Connection connection, TemporaryTableDiagnosticPlan plan, int seconds) throws SQLException {
        if (plan.rollbackSql() == null) {
            connection.rollback();
        } else {
            execute(connection, plan.rollbackSql(), seconds);
        }
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private static int execute(Connection connection, String sql, int seconds) throws SQLException {
        try (var statement = connection.createStatement()) {
            timeout(statement, seconds);
            return statement.executeUpdate(sql);
        }
    }

    /**
     * 返回数量。
     */
    private static int count(Connection connection, String relation, String predicate,
            int seconds) throws SQLException {
        try (var statement = connection.createStatement()) {
            timeout(statement, seconds);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + relation + " WHERE " + predicate)) {
                if (!rows.next()) {
                    throw new SQLException("临时表核对未返回结果");
                }
                return rows.getInt(1);
            }
        }
    }

    /**
     * 返回超时设置。
     */
    private static void timeout(Statement statement, int seconds) throws SQLException {
        try {
            statement.setQueryTimeout(seconds);
        } catch (SQLFeatureNotSupportedException |
                 AbstractMethodError ignored) { /* 外层 DriverNativeAccess 保证期限。 */ }
    }

    /**
     * 校验并取得数据行。
     */
    private static void requireRows(int actual, int expected) throws SQLException {
        if (actual != expected) {
            throw new SQLException("诊断临时数据核对不一致");
        }
    }

    /**
     * 记录当前表跳过读取的状态和原因。
     */
    private static void skipRead(Run run, String reason) {
        run.skip("METADATA", "TABLES", "基本表目录", reason);
        run.skip("METADATA", "COLUMNS", "字段结构", reason);
        run.skip("METADATA", "PRIMARY_KEYS", "主键结构", reason);
        run.skip("METADATA", "EXTENDED_PROPERTIES", "扩展属性读取", reason);
        run.skip("READ", "ZERO_ROW_READ", "零行读取", reason);
    }

    /**
     * 记录当前表跳过写入的状态和原因。
     */
    private static void skipWrite(Run run, String reason, String reasonCode) {
        run.skip("WRITE", "TEMP_CREATE", "创建诊断临时表", reason, reasonCode);
        skipWriteOperations(run, reason, reasonCode);
    }

    /**
     * 记录当前范围内被跳过的写入操作。
     */
    private static void skipWriteOperations(Run run, String reason) {
        skipWriteOperations(run, reason, "PREREQUISITE_FAILED");
    }

    private static void skipWriteOperations(Run run, String reason, String reasonCode) {
        run.skip("WRITE", "INSERT", "临时表插入", reason, reasonCode);
        run.skip("WRITE", "BATCH_INSERT", "临时表批量插入", reason, reasonCode);
        run.skip("WRITE", "UPDATE", "临时表更新", reason, reasonCode);
        run.skip("WRITE", "UPSERT", "临时表冲突更新", reason, reasonCode);
        run.skip("WRITE", "MERGE", "临时表合并写入", reason, reasonCode);
        run.skip("WRITE", "DELETE", "临时表删除行", reason, reasonCode);
        run.skip("WRITE", "TRUNCATE", "清空诊断临时表", reason, reasonCode);
        run.skip("ADVANCED", "TRANSACTION", "临时表事务回滚", reason, reasonCode);
        run.skip("ADVANCED", "ALTER_TEMP", "临时表结构变更", reason, reasonCode);
        run.skip("ADVANCED", "RENAME_TEMP", "重命名诊断临时表", reason, reasonCode);
        run.skip("ADVANCED", "DROP_TEMP", "删除诊断临时表", reason, reasonCode);
    }

    /**
     * 关闭 {@code ConnectionDiagnostics} 当前持有的资源。
     */
    @Override
    public void close() {
        io.close();
    }

    private record Outcome<T>(T value, String status, String message, String reasonCode) {

        private Outcome(T value, String status, String message) {
            this(value, status, message, "SKIPPED".equals(status) ? "UNVERIFIED" : null);
        }
    }

    /**
     * 根据当前输入构建并返回 {@code Outcome}。
     */
    private static <T> Outcome<T> pass(T value, String message) {
        return new Outcome<>(value, "PASS", message);
    }

    /**
     * 返回当前告警。
     */
    private static <T> Outcome<T> warn(T value, String message) {
        return new Outcome<>(value, "WARN", message);
    }

    /**
     * 根据当前输入构建并返回 {@code Outcome}。
     */
    private static <T> Outcome<T> skipped(String message) {
        return new Outcome<>(null, "SKIPPED", message);
    }

    private static final class Run {

        private final ConnectionDiagnosticOptions options;

        private final long started = System.nanoTime();

        private final long deadline;

        private long stepDeadline = Long.MAX_VALUE;

        private final ThreadLocal<Long> nativeDeadline = new ThreadLocal<>();

        private final List<ConnectionDiagnosticResult.Check> checks = new ArrayList<>();

        /**
         * 初始化 {@code Run}。
         */
        Run(ConnectionDiagnosticOptions options, long totalMillis) {
            this.options = options;
            deadline = started + TimeUnit.MILLISECONDS.toNanos(totalMillis);
        }

        /**
         * 返回当前剩余。
         */
        int remaining() {
            return (int) Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        }

        /**
         * 返回超时设置。
         */
        int timeout() throws SQLTimeoutException {
            Long nativeLimit = nativeDeadline.get();
            long limit = nativeLimit == null ? stepDeadline : nativeLimit;
            int timeout = (int) Math.min(remaining(), Math.min(options.timeoutMillis(),
                    Math.max(0, TimeUnit.NANOSECONDS.toMillis(limit - System.nanoTime()))));
            if (timeout < 1) {
                throw new SQLTimeoutException("诊断总预算已耗尽");
            }
            return timeout;
        }

        /**
         * 返回当前秒数。
         */
        int seconds() throws SQLTimeoutException {
            return Math.max(1, (timeout() + 999) / 1000);
        }

        /**
         * 校验原生列报告的 JDBC 类型是否属于允许范围。
         */
        <T> T jdbc(Connection connection, String group, String key, String label, Callable<Outcome<T>> operation) {
            return step(group, key, label, () -> nativeCall(connection, operation));
        }

        /**
         * 通过受控原生调用边界访问当前产品接口。
         */
        <T> T nativeCall(Connection connection, Callable<T> operation) throws SQLException {
            long limit = stepDeadline;
            return DriverNativeAccess.invoke(connection, timeout(), () -> {
                nativeDeadline.set(limit);
                try {
                    return operation.call();
                } finally {
                    nativeDeadline.remove();
                }
            });
        }

        /**
         * 应用当前计划中的一个执行步骤。
         */
        <T> T step(String group, String key, String label, Callable<Outcome<T>> operation) {
            return step(group, key, label, operation, false);
        }

        /**
         * 应用当前计划中的一个执行步骤。
         */
        <T> T step(String group, String key, String label, Callable<Outcome<T>> operation, boolean cleanup) {
            if (!cleanup && remaining() < 1) {
                skip(group, key, label, "诊断总预算已耗尽，未启动此项", "BUDGET_EXHAUSTED");
                return null;
            }
            long begin = System.nanoTime();
            long previousStepDeadline = stepDeadline;
            stepDeadline = Math.min(deadline, begin + TimeUnit.MILLISECONDS.toNanos(options.timeoutMillis()));
            try {
                Outcome<T> result = operation.call();
                add(group, key, label, result.status(), elapsed(begin), result.message(), null, null, result.reasonCode());
                return result.value();
            } catch (Exception | LinkageError failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Throwable root = failure;
                for (int depth = 0; depth < 16 && !(root instanceof SQLException) && root.getCause() != null; depth++) {
                    root = root.getCause();
                }
                String state = root instanceof SQLException sql ? sql.getSQLState() : null;
                if (state != null && !state.matches("[A-Z0-9]{5}")) {
                    state = null;
                }
                Integer vendor = root instanceof SQLException sql ? sql.getErrorCode() : null;
                String status = root instanceof SQLFeatureNotSupportedException || root instanceof UnsupportedOperationException
                        || "0A000".equals(state) ? "SKIPPED" : "FAIL";
                boolean notApplicable = root instanceof cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException;
                String message = "检查失败，数据库未返回可验证结果";
                if (notApplicable) {
                    message = "此项检查不适用于当前数据库产品的原生语义";
                } else if ("SKIPPED".equals(status)) {
                    message = "当前驱动或产品尚未支持此项诊断";
                } else if (root instanceof SQLTimeoutException || root instanceof SocketTimeoutException) {
                    message = "检查超过期限；执行或清理结果尚未确认";
                } else if (root instanceof UnknownHostException) {
                    message = "JDBC 目标主机解析失败";
                } else if (state != null && state.startsWith("28")) {
                    message = "数据库认证失败，请核对账号与认证配置";
                } else if (state != null && state.startsWith("08")) {
                    message = "数据库连接中断或目标不可达";
                } else if ("42501".equals(state) || "42000".equals(state)) {
                    message = "数据库拒绝当前操作，请核对所需权限";
                } else if ("3F000".equals(state) || "3D000".equals(state)) {
                    message = "目标数据库或命名空间不存在或不可见";
                } else if (root instanceof IllegalArgumentException) {
                    status = "WARN";
                    message = "配置或元数据超出此项诊断支持范围";
                }
                if (cleanup && !"PASS".equals(status)) {
                    status = "WARN";
                    message = "会话资源清理尚未确认；驱动运行时保留未释放资源额度";
                }
                String reasonCode = null;
                if ("SKIPPED".equals(status)) {
                    reasonCode = notApplicable ? "NOT_APPLICABLE" : "NOT_IMPLEMENTED";
                }
                add(group, key, label, status, elapsed(begin), message, state, vendor, reasonCode);
                return null;
            } finally {
                stepDeadline = previousStepDeadline;
            }
        }

        /**
         * 为当前诊断阶段记录跳过状态及原因。
         */
        void skip(String group, String key, String label, String reason) {
            skip(group, key, label, reason, "PREREQUISITE_FAILED");
        }

        void skip(String group, String key, String label, String reason, String reasonCode) {
            add(group, key, label, "SKIPPED", 0, reason, null, null, reasonCode);
        }

        /**
         * 将当前值加入集合或累计统计。
         */
        private void add(String group, String key, String label, String status, long millis, String message,
                String state, Integer vendor) {
            add(group, key, label, status, millis, message, state, vendor,
                    "SKIPPED".equals(status) ? "UNVERIFIED" : null);
        }

        private void add(String group, String key, String label, String status, long millis, String message,
                         String state, Integer vendor, String reasonCode) {
            checks.add(new ConnectionDiagnosticResult.Check(group, key, label, status, millis, message, state, vendor,
                    reasonCode));
        }

        /**
         * 返回结果。
         */
        ConnectionDiagnosticResult result(boolean connected) {
            String status = checks.stream().anyMatch(check -> "FAIL".equals(check.status())) ? "FAIL"
                    : checks.stream().anyMatch(check -> "WARN".equals(check.status())) ? "WARN"
                    : checks.stream().anyMatch(check -> "PASS".equals(check.status())) ? "PASS" : "SKIPPED";
            return new ConnectionDiagnosticResult(status, connected, elapsed(started), checks);
        }

        /**
         * 返回当前操作已经经过的毫秒数。
         */
        private static long elapsed(long start) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
    }
}

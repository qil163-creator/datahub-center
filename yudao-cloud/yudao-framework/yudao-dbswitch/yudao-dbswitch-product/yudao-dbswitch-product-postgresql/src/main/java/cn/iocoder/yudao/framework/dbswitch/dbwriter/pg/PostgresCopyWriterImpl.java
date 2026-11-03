package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.DataType;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresqlProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;

import javax.sql.DataSource;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

/** PostgreSQL COPY 提交见证；复用 COPY 编码/回执与事务边界，不将 PG 兼容产品视为已验证产品。 */
public final class PostgresCopyWriterImpl extends PostgresCompatibleCopyWriterImpl {

    private final PostgresIdentityTransfer identity;

    private PostgresCopyAdmission admission;

    /**
     * 初始化 {@code PostgresCopyWriterImpl}及其父类状态。
     */
    public PostgresCopyWriterImpl(DataSource dataSource) {
        super(dataSource);
        identity = new PostgresIdentityTransfer(dataSource);
    }

    /**
     * 配置标识列在当前搬运路径中的处理方式。
     */
    @Override
    public void configureIdentityTransfer(
            cn.iocoder.yudao.framework.dbswitch.dbwriter.IdentityTransferOptions options) {
        identity.configure(options);
    }

    /**
     * 配置序列传输。
     */
    @Override
    public void configureSequenceTransfer(
            cn.iocoder.yudao.framework.dbswitch.dbwriter.SequenceTransferScope.TableBinding binding) {
        identity.configureShared(binding);
    }

    /**
     * 设置当前操作使用的配置，并检查配置阶段仍允许修改。
     */
    @Override
    public void configure(cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions options) {
        super.configure(options);
        identity.configureRuntime(options);
    }

    /**
     * 在迁移前检查目标标识列策略和相关序列条件。
     */
    @Override
    public void preflightIdentityTransfer(String schema, String table, boolean exists) {
        identity.preflight(schema, table, exists);
    }

    /**
     * 在实际写入前检查写入能力及目标条件。
     */
    @Override
    public void preflightRuntimeWrite() {
        try (var connection = dataSource.getConnection()) {
            PostgresCopyAdmission.verifyRuntime(connection);
        } catch (java.sql.SQLException invalid) {
            unsupported("PostgreSQL WRITE_COPY runtime driver/server profile is not verified");
        }
    }

    /**
     * 在写入前检查目标表定义是否满足当前路径的要求。
     */
    @Override
    public void preflightTargetDefinition(String schema, String table, List<String> statements, List<String> fields) {
        try {
            cn.iocoder.yudao.framework.dbswitch.core.service.PostgresCopyTargetDefinition.validate(schema, table,
                    statements, fields);
        } catch (IllegalArgumentException invalid) {
            unsupported("PostgreSQL WRITE_COPY planned native target definition is not verified");
        }
    }

    /**
     * 写入准备：执行一条 WHERE 1=2 的探测查询（不取数据）， 从结果集元数据中缓存目标表“字段名 -> JDBC 类型”映射，供 write 阶段绑定参数类型使用； 查询失败或表无字段时抛出运行时异常。
     */
    @Override
    public void prepareWrite(String schema, String table, List<String> fields) {
        admission = null; // 重新准备失败时，不能保留前一张表的准入信息。
        PostgresCopyAdmission candidate;
        try (var connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit() || connection.isReadOnly() || Thread.currentThread().isInterrupted()) {
                throw PostgresCopyAdmission.unsupported();
            }
            candidate = PostgresCopyAdmission.capture(connection, schema, table, fields);
        } catch (java.sql.SQLException invalid) {
            unsupported("PostgreSQL WRITE_COPY runtime profile, session or column layout is not verified");
            return;
        }
        identity.prepare(schema, table, fields);
        super.prepareWrite(schema, table, fields);
        try {
            candidate.requireJdbcLayout(columnType);
        } catch (java.sql.SQLException invalid) {
            unsupported("PostgreSQL WRITE_COPY JDBC/native column layout is not verified");
        }
        admission = candidate;
    }

    /**
     * 完成标识传输。
     */
    @Override
    public void completeIdentityTransfer() {
        identity.complete();
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "PostgreSQL";
    }

    /**
     * 校验并取得COPY准入。
     */
    @Override
    protected void requireCopyAdmission() {
        refuseUnverifiedCopy();
    }

    /**
     * 批量数据写入。
     */
    @Override
    public long write(List<String> fields, List<Object[]> rows) {
        refuseUnverifiedCopy();
        return writeWithReceipt(fields, rows).acknowledgedRows();
    }

    /**
     * 可选原生单批提交见证；旧实现继续工作，但 null 不能解释为已取得提交/到数证明。
     */
    @Override
    public BatchWriteResult writeWithReceipt(List<String> fields, List<Object[]> rows) {
        refuseUnverifiedCopy();
        var batch = prepareCopy(fields, rows);
        if (batch == null) {
            return new BatchWriteResult(0, null);
        }
        requireVariableEncodingBudget(batch.fields(), batch.rows());
        return JdbcBatchExecutor.execute(dataSource, context -> {
            admission.verifyBatch(context.connection(), batch.fields());
            identity.beforeBatch(context.connection());
            var witness = PostgresCommitWitness.begin(context.connection(), schemaName, tableName);
            // 必须等 COPY 确认退出后才能在同一连接查询；活动协议错误仍由共用边界终止会话。
            long acknowledged = executeCopy(context, batch);
            if (witness != null) {
                witness.verifyTransaction(context.connection());
            }
            return new Pending(acknowledged, witness);
        }, (connection, pending) -> {
            if (pending.witness() != null) {
                pending.witness().verifyCommitted(connection);
            }
        }).result();
    }

    private record Pending(long rows, PostgresCommitWitness witness) {

        /**
         * 返回结果。
         */
        BatchWriteResult result() {
            return new BatchWriteResult(rows, witness == null ? null : witness.receipt());
        }
    }

    /**
     * 产品特定编码只有在原生准入确认后才能覆盖此实现。
     */
    @Override
    protected void validateCopyValue(String field, Object value) {
        if (admission != null && admission.isJsonb(field)) {
            if (columnType.get(field) != java.sql.Types.OTHER) {
                throw new BatchRejectedException("PostgreSQL COPY JSONB requires the native JDBC layout");
            }
            PostgresJsonbCopyValue.text(value, batchLimits.maxCellBytes());
        }
        if (admission != null && admission.isUuid(field)) {
            if (columnType.get(field) != java.sql.Types.OTHER || value != null && !(value instanceof java.util.UUID)) {
                throw new BatchRejectedException(
                        "PostgreSQL COPY UUID requires a native UUID value");
            }
        }
        String element = admission == null ? null : admission.arrayElement(field);
        if (element != null) {
            if (columnType.get(field) != java.sql.Types.ARRAY || value != null
                    && (!(value instanceof JdbcArrayValue array)
                    || !element.equals(array.baseTypeName()))) {
                throw new BatchRejectedException(
                        "PostgreSQL COPY ARRAY requires the matching detached native array");
            }
            if (value instanceof JdbcArrayValue array) {
                for (Object item : array.elements()) {
                    convertArrayElement(element, item);
                }
            }
        }
    }

    /**
     * 先验证原始值并完成已有有界批次物化，再进行转换。
     */
    @Override
    protected Object convertCopyValue(String field, Object value) {
        if (admission != null && admission.isJsonb(field)) {
            return PostgresJsonbCopyValue.text(value, batchLimits.maxCellBytes());
        }
        validateCopyValue(field, value);
        if (admission != null && admission.isUuid(field)) {
            return value;
        }
        String element = admission == null ? null : admission.arrayElement(field);
        if (element != null) {
            if (value == null) {
                return null;
            }
            var array = (JdbcArrayValue) value;
            return new JdbcArrayValue(element,
                    array.baseJdbcType(),
                    array.elements().stream().map(item -> convertArrayElement(element, item)).toList());
        }
        return super.convertCopyValue(field, value);
    }

    /**
     * 编码已完整预检批次中的一个值，保留兼容产品的默认设置。
     */
    @Override
    protected void encodeCopyValue(cn.iocoder.yudao.framework.dbswitch.pgwriter.row.SimpleRow row,
            int ordinal, String field, Object value) {
        if (admission != null && admission.isUuid(field)) {
            row.setUUID(ordinal, (java.util.UUID) value);
        } else if (admission != null && admission.isJsonb(field)) {
            row.setJsonb(ordinal, (String) value);
        } else if (admission != null && admission.arrayElement(field) != null) {
            var type = switch (admission.arrayElement(field)) {
                case "int2" -> DataType.Int2;
                case "int4" -> DataType.Int4;
                case "int8" -> DataType.Int8;
                case "bool" -> DataType.Boolean;
                case "text" -> DataType.Text;
                case "numeric" -> DataType.Numeric;
                default -> throw new IllegalStateException("Unverified PostgreSQL ARRAY encoder");
            };
            row.setCollection(ordinal, type, value == null ? null
                    : ((JdbcArrayValue) value).elements());
        } else {
            super.encodeCopyValue(row, ordinal, field, value);
        }
    }

    /**
     * 转换数组元素。
     */
    private static Object convertArrayElement(String element, Object value) {
        try {
            // 稳定的已脱离资源值已约束 Java 类型族；精确转换器在借用连接前
            // 避免 shortValue 截断和文本替换。
            int jdbc = switch (element) {
                case "int2" -> java.sql.Types.SMALLINT;
                case "int4" -> java.sql.Types.INTEGER;
                case "int8" -> java.sql.Types.BIGINT;
                case "bool" -> java.sql.Types.BOOLEAN;
                case "text" -> java.sql.Types.VARCHAR;
                case "numeric" -> java.sql.Types.NUMERIC;
                default -> throw new IllegalArgumentException("Unverified ARRAY element");
            };
            if (value instanceof java.math.BigDecimal number) {
                long scale = number.scale(), integerDigits = (long) number.precision() - scale;
                long digits = scale > 0 ? number.precision() + (4 - scale % 4) % 4 : integerDigits;
                // 已有数值二进制编码器使用有符号 16 位组计数和权重。
                // 保守地保留 PostgreSQL 无界数值范围内的精确子集。
                if (scale < -131067 || scale > 16383 || integerDigits > 131068 || (digits + 3) / 4 > 32767) {
                    throw new IllegalArgumentException("Unverified numeric ARRAY binary range");
                }
            }
            return JdbcValueConversions.forJdbcType(value, jdbc);
        } catch (RuntimeException invalid) {
            throw new BatchRejectedException(
                    "PostgreSQL COPY ARRAY element cannot be represented exactly");
        }
    }

    /** 额外的 ARRAY 和 JSONB 二进制缓冲限制，Java 值预算仍独立强制执行。 */
    private void requireVariableEncodingBudget(List<String> fields, List<Object[]> rows) {
        try {
            long arraysBytes = 0;
            for (Object[] row : rows) {
                for (int index = 0; index < fields.size(); index++) {
                    String element = admission.arrayElement(fields.get(index));
                    if (admission.isJsonb(fields.get(index))) {
                        long payload = row[index] == null ? 0 : PostgresJsonbCopyValue.payloadBytes(
                                (String) row[index]);
                        if (payload > Integer.MAX_VALUE - 4L || payload > batchLimits.maxCellBytes()) {
                            throw new ArithmeticException();
                        }
                        arraysBytes = Math.addExact(arraysBytes, Math.addExact(4, payload));
                        if (arraysBytes > batchLimits.maxBatchBytes()) {
                            throw new ArithmeticException();
                        }
                        continue;
                    }
                    if (element == null) {
                        continue;
                    }
                    long payload = 0;
                    if (row[index] instanceof JdbcArrayValue array) {
                        // CollectionValueHandler 当前即使对空集合也发送一维，
                        // PostgreSQL 会将空值规范化为零维。
                        payload = 20;
                        for (Object item : array.elements()) {
                            payload = Math.addExact(payload, Math.addExact(4, arrayElementPayloadBytes(element, item)));
                        }
                        // 编码器的 ByteArrayOutputStream 和字段长度使用有符号整数。
                        if (payload > Integer.MAX_VALUE - 4L || payload > batchLimits.maxCellBytes()) {
                            throw new ArithmeticException();
                        }
                    }
                    arraysBytes = Math.addExact(arraysBytes, Math.addExact(4, payload));
                    if (arraysBytes > batchLimits.maxBatchBytes()) {
                        throw new ArithmeticException();
                    }
                }
            }
        } catch (ArithmeticException invalid) {
            throw new BatchRejectedException("PostgreSQL COPY ARRAY/JSONB binary buffer exceeds the configured budget");
        }
    }

    /**
     * 与 BigDecimalValueHandler 使用相同组数，但不调用 TEN.pow，也不构造。
     */
    private static long arrayElementPayloadBytes(String element, Object value) {
        if (value == null) {
            return 0;
        }
        return switch (element) {
            case "int2" -> 2;
            case "int4" -> 4;
            case "int8" -> 8;
            case "bool" -> 1;
            case "numeric" -> {
                var number = (java.math.BigDecimal) value;
                long scale = number.scale(), precision = number.precision(), remainder = scale > 0 ? scale % 4 : 0;
                // 与 BigDecimalValueHandler 使用相同组数，但不调用 TEN.pow，也不构造
                // 展开后的 BigInteger 或列表；负标度的 Java 科学计数表示可能很短，
                // 但包含数以万计的二进制组。
                long groups = number.signum() == 0 ? (remainder == 0 ? 0 : 1)
                        : scale <= 0 ? (precision - scale + 3) / 4
                        : remainder == 0 ? (precision + 3) / 4 : 1 + (Math.max(precision - remainder, 0) + 3) / 4;
                yield 8 + 2 * groups;
            }
            case "text" -> {
                String text = (String) value;
                long bytes = 0;
                for (int index = 0; index < text.length(); index++) {
                    char character = text.charAt(index);
                    if (character < 0x80) {
                        bytes++;
                    } else if (character < 0x800) {
                        bytes += 2;
                    } else if (Character.isHighSurrogate(character)) {
                        bytes += 4;
                        index++;
                    } else {
                        bytes += 3;
                    }
                }
                yield bytes;
            }
            default -> throw new IllegalStateException("Unverified PostgreSQL ARRAY encoder");
        };
    }

    /** 仅注册不能授予 COPY 准入，准备阶段提供此写入器的原生操作证据。 */
    private void refuseUnverifiedCopy() {
        if (admission != null) {
            return;
        }
        var capability = new PostgresqlProductProvider().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.POSTGRESQL)
                .map(product -> product.capabilities().get(DatabaseOperation.WRITE_COPY))
                .findFirst().orElse(null);
        String state = capability == null ? "UNDECLARED" : capability.status() + "/" + capability.reason();
        unsupported("PostgreSQL WRITE_COPY is not verified (" + state + "); runtime admission is absent");
    }

    /**
     * 返回当前操作或类型不受支持的结果。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void unsupported(String reason) throws E {
        throw (E) new SQLFeatureNotSupportedException(reason, "0A000");
    }
}

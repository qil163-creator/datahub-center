package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.file.TabularFileReader;
import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.BATCH_FILE_TARGET_INVALID;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/** 领域配置到 DB Switch 的薄适配：仅新建或结构一致追加，绝不清空和删除目标。 */
@Component
public class BatchFileTargetAdapter {

    @Resource
    private DbSwitchRuntime runtime;

    @Resource
    private AccessDatabaseResolver resolver;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private BatchFileTargetAuthorization authorization;

    public record Outcome(String status, long writtenRows, String errorMessage) {

    }

    /**
     * 追加批次文件目标适配器内容。
     *
     * @param task 任务记录
     * @param file 文件
     * @param content 内容
     * @param actor 操作人编号
     * @param boundary 边界
     * @return 结果
     */
    public Outcome append(Task task, File file, byte[] content, Long actor, Runnable boundary) {
        long written = 0;
        boolean mutationInFlight = false;
        try {
            boundary.run();
            TabularFileReader.Table table = TabularFileReader.read(content, BatchFileRules.extension(file.getName()),
                    BatchFileService.parserLimits());
            List<Object[]> rows = convert(file, table.rows()); // 整个文件先校验，类型错误不产生半个文件写入。
            access.requireUserPermission(actor, "dataforge:batch-file:execute");
            access.requireDatabase(task.getTargetDatabaseId());
            var connection = resolver.resolve(task.getTargetDatabaseId(), BATCH_FILE_TARGET_INVALID);
            if (connection.getProductType() != ProductTypeEnum.MYSQL) {
                throw exception(BATCH_FILE_TARGET_INVALID);
            }
            DataSource source = dataSource(connection);
            var metadata = runtime.jdbcMetadata(ProductTypeEnum.MYSQL);
            String schema = connection.getSchema(), target = file.getTargetTable();
            boundary.run();
            boolean exists;
            // tableExist 的旧接口会把连接错误视为不存在；采用目录 API，只将明确空结果视为缺表。
            try (Connection physical = source.getConnection()) {
                exists = runtime.products().metadata(ProductTypeEnum.MYSQL)
                        .queryTableMeta(physical, schema, target) != null;
            }
            List<String> names = file.getFields().stream().map(Field::getName).toList();
            if (!exists) {
                String ddl = createSql(schema, target, file.getFields());
                var approved = authorization.authorize(connection, actor, target, ddl, "DDL", 0);
                requireCurrent(connection);
                boundary.run();
                mutationInFlight = true;
                metadata.executeSql(connection.getJdbcUrl(), connection.getUsername(), connection.getPassword(),
                        approved.sql());
                mutationInFlight = false;
            }
            try (Connection physical = source.getConnection()) {
                var relation = runtime.products().metadata(ProductTypeEnum.MYSQL)
                        .queryTableMeta(physical, schema, target);
                if (relation == null || !"TABLE".equals(relation.getTableType())) {
                    return new Outcome("FAILED", 0, "目标必须是普通数据表，不能向视图写入");
                }
            }
            List<ColumnDescription> actual = runtime.metadata(source, ProductTypeEnum.MYSQL)
                    .queryTableColumnMeta(schema, target);
            if (!matches(file.getFields(), actual)) {
                return new Outcome("FAILED", 0, "目标表现有字段名称、顺序或类型与配置不一致，未写入");
            }
            for (int offset = 0; offset < rows.size(); ) {
                boundary.run();
                access.requireUserPermission(actor, "dataforge:batch-file:execute");
                access.requireDatabase(task.getTargetDatabaseId());
                int expected = Math.min(Math.min(500, 5000 / names.size()), rows.size() - offset);
                String insert;
                while (true) {
                    try {
                        insert = GenerateSqlUtils.getParameterizedInsertSql(ProductTypeEnum.MYSQL, schema, target,
                                names, expected);
                        break;
                    } catch (IllegalArgumentException tooLarge) {
                        if (expected == 1) {
                            throw tooLarge;
                        }
                        expected /= 2;
                    }
                }
                List<Object> bindings = new ArrayList<>(expected * names.size());
                for (Object[] row : rows.subList(offset, offset + expected)) {
                    Collections.addAll(bindings, row);
                }
                var approved = authorization.authorize(connection, actor, target, insert, "DML", bindings.size());
                requireCurrent(connection);
                boundary.run();
                var plan = SqlScriptPlan.parse(ProductTypeEnum.MYSQL, approved.sql());
                mutationInFlight = true;
                // 每批由组件独占事务，执行的 SQL 与参数序均来自正式授权回执。
                var result = metadata.executePlan(connection.getJdbcUrl(), connection.getUsername(),
                        connection.getPassword(), plan,
                        approved.bind(bindings), 1, 1, null, ignored -> boundary.run());
                if (result.getTransactionOutcome() == JdbcSelectResult.TransactionOutcome.ROLLED_BACK)
                // 既有目标未提供存储引擎证据，事务回滚不能证明 MyISAM 等非事务表未写入。
                {
                    return new Outcome("UNKNOWN", written,
                            "数据库报告本批失败；目标表事务能力未确认，请核查实际写入结果");
                }
                if (result.getTransactionOutcome() != JdbcSelectResult.TransactionOutcome.COMMITTED
                        || result.getResults() == null || result.getResults().size() != 1
                        || !Boolean.TRUE.equals(result.getResults().getFirst().getSuccess())
                        || !Objects.equals(expected, result.getResults().getFirst().getCount())) {
                    return new Outcome("UNKNOWN", written, "数据库写入回执未确认，请核查目标数据");
                }
                mutationInFlight = false;
                written += expected;
                offset += expected;
            }
            return new Outcome("SUCCESS", written, null);
        } catch (Exception ex) {
            return new Outcome(mutationInFlight ? "UNKNOWN" : "FAILED", written,
                    mutationInFlight ? "目标数据库操作结果未确认，请停止工作者并核查目标数据后解除占用"
                            : written > 0 ? "文件后续写入失败；已确认写入行数保留，请核查后再执行" : "文件校验、目标结构、操作权限或数据库连接检查失败，未写入数据");
        }
    }

    /**
     * 校验当前。
     *
     * @param frozen 冻结
     */
    private void requireCurrent(AccessDatabaseResolver.ConnectionInfo frozen) {
        var current = resolver.resolve(frozen.getDatabaseId(), BATCH_FILE_TARGET_INVALID);
        if (!Objects.equals(current.getConfigurationRevision(), frozen.getConfigurationRevision())
                || current.getProductType() != frozen.getProductType() || !Objects.equals(current.getSchema(),
                frozen.getSchema())) {
            throw exception(BATCH_FILE_TARGET_INVALID);
        }
    }

    /**
     * 获取批次文件目标适配器的数据源。
     *
     * @param info 信息
     * @return 数据源
     */
    DataSource dataSource(AccessDatabaseResolver.ConnectionInfo info) {
        return new AbstractDataSource() {
            /**
             * 读取连接。
             *
             * @return 连接
             */
            @Override
            public Connection getConnection() throws SQLException {
                return runtime.openConnection(info.getProductType(), null, info.getJdbcUrl(), info.getUsername(),
                        info.getPassword());
            }

            /**
             * 读取连接。
             *
             * @param user 用户
             * @param password 密码
             * @return 连接
             */
            @Override
            public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException();
            }
        };
    }

    /**
     * 创建SQL。
     *
     * @param schema 结构
     * @param table 表
     * @param fields 字段定义
     * @return 创建SQL文本
     */
    public String createSql(String schema, String table, List<Field> fields) {
        return GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(ProductTypeEnum.MYSQL),
                fields.stream().map(BatchFileTargetAdapter::column).toList(), List.of(), schema, table, false);
    }

    /**
     * 转换批次文件目标适配器。
     *
     * @param file 文件
     * @param rows 待处理的行数据
     * @return 转换批次文件目标适配器结果集合
     */
    static List<Object[]> convert(File file, List<List<String>> rows) {
        List<Object[]> result = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            Object[] values = new Object[file.getFields().size()];
            for (int i = 0; i < values.length; i++) {
                Field f = file.getFields().get(i);
                values[i] = BatchFileRules.value(f.getSourceIndex() < row.size() ? row.get(f.getSourceIndex()) : null,
                        f.getType());
            }
            result.add(values);
        }
        return result;
    }

    /**
     * 获取批次文件目标适配器的列。
     *
     * @param field 字段
     * @return 列描述
     */
    public static ColumnDescription column(Field field) {
        ColumnDescription c = new ColumnDescription();
        c.setFieldName(field.getName());
        c.setLabelName(field.getName());
        c.setNullable(true);
        c.setSigned(true);
        c.setLength(0);
        c.setPrecision(0);
        switch (field.getType()) {
            case "string" -> {
                c.setFieldType(Types.LONGVARCHAR);
                c.setValType(ColumnMetaData.TYPE_STRING);
                c.setLength(1024 * 1024);
            }
            case "bigint" -> {
                c.setFieldType(Types.BIGINT);
                c.setValType(ColumnMetaData.TYPE_INTEGER);
                c.setLength(18);
            }
            case "int" -> {
                c.setFieldType(Types.INTEGER);
                c.setValType(ColumnMetaData.TYPE_INTEGER);
                c.setLength(9);
            }
            case "decimal" -> {
                c.setFieldType(Types.DECIMAL);
                c.setValType(ColumnMetaData.TYPE_BIGNUMBER);
                c.setLength(38);
                c.setPrecision(18);
            }
            case "double" -> {
                c.setFieldType(Types.DOUBLE);
                c.setValType(ColumnMetaData.TYPE_NUMBER);
                c.setLength(17);
                c.setPrecision(-1);
            }
            case "date" -> {
                c.setFieldType(Types.DATE);
                c.setValType(ColumnMetaData.TYPE_DATE);
            }
            case "datetime" -> {
                c.setFieldType(Types.TIMESTAMP);
                c.setValType(ColumnMetaData.TYPE_TIMESTAMP);
                c.setLength(6);
            }
            case "boolean" -> {
                c.setFieldType(Types.BOOLEAN);
                c.setValType(ColumnMetaData.TYPE_BOOLEAN);
            }
            default -> throw new IllegalArgumentException("不支持的字段类型");
        }
        return c;
    }

    /**
     * 判断给定值是否与预期结果匹配。
     *
     * @param expected 预期值
     * @param actual 实际值
     * @return 判断结果
     */
    static boolean matches(List<Field> expected, List<ColumnDescription> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            Field field = expected.get(i);
            ColumnDescription c = actual.get(i);
            if (!field.getName().equals(c.getFieldName()) || c.isAutoIncrement() || !c.isNullable()
                    || c.getMetadataEvidence().generatedExpression().state() == MetadataValue.State.VALUE) {
                return false;
            }
            String nativeType = Objects.toString(c.getFieldTypeName(), "").toLowerCase(Locale.ROOT);
            boolean compatible = switch (field.getType()) {
                case "string" -> nativeType.equals("mediumtext") || nativeType.equals("longtext");
                case "bigint" -> c.getFieldType() == Types.BIGINT && !nativeType.contains("unsigned");
                case "int" -> c.getFieldType() == Types.INTEGER && !nativeType.contains("unsigned");
                case "decimal" ->
                        c.getFieldType() == Types.DECIMAL && c.getPrecisionSize() == 38 && c.getScaleSize() == 18;
                case "double" -> c.getFieldType() == Types.DOUBLE;
                case "date" -> c.getFieldType() == Types.DATE;
                case "datetime" -> c.getFieldType() == Types.TIMESTAMP && nativeType.startsWith(
                        "datetime") && c.getScaleSize() == 6;
                case "boolean" ->
                        c.getFieldType() == Types.BOOLEAN || c.getFieldType() == Types.BIT || c.getFieldType() == Types.TINYINT;
                default -> false;
            };
            if (!compatible) {
                return false;
            }
        }
        return true;
    }
}


package cn.iocoder.yudao.framework.dbswitch.core.util;

import com.google.common.collect.Lists;
import org.springframework.util.CollectionUtils;
import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.DBTableType;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.UuidUtils;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeColumnSql;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TableTypeMappingPlan;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * 拼接SQL工具类
 * <p>
 * 职责：跨库建表 DDL 生成的统一入口。根据目标数据库类型取得对应的方言实现
 * （{@link AbstractDatabase}），把源表的字段描述列表转换为目标库的 CREATE TABLE 语句
 * （必要时附带 COMMENT ON 注释语句），字段类型映射由各方言实现的 getFieldDefinition 完成。
 * <p>
 * 调用方：MetaDataByDataSourceServiceImpl / MetaDataByJdbcServiceImpl 的
 * getDDLCreateTableSQL 方法，最终服务于 MigrationHandler 的结构迁移建表
 *
 * @author jrl
 */
public final class GenerateSqlUtils {

    /**
     * 生成有界的 MySQL 普通 INSERT；参数按行、再按字段顺序绑定，所有值均使用 JDBC 占位符。
     * 仅负责 SQL 生成，不授予权限、不打开连接；调用方必须执行授权服务返回的最终计划。
     */
    public static String getParameterizedInsertSql(ProductTypeEnum product, String schemaName, String tableName,
            List<String> fieldNames, int rowCount) {
        if (product != ProductTypeEnum.MYSQL) {
            throw new UnsupportedOperationException("Parameterized INSERT is only verified for MySQL");
        }
        if (fieldNames == null || fieldNames.isEmpty() || rowCount < 1
                || (long) fieldNames.size() * rowCount > 5000) {
            throw new IllegalArgumentException("INSERT requires rows and columns within 5000 parameters");
        }
        String target = RestrictedSelectGuard.qualifiedTableName(product, schemaName, tableName);
        var columns = new ArrayList<String>();
        var unique = new HashSet<String>();
        long columnLength = 0;
        for (String field : fieldNames) {
            String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(product, field);
            if (!unique.add(field.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("INSERT columns must be unique");
            }
            columns.add(quoted);
            columnLength += quoted.length();
        }
        // 在拼接重复行之前计算长度，拒绝超预算输入，避免调用方收到无法授权的超长 SQL。
        long prefixLength = "INSERT INTO ".length() + target.length() + " (".length()
                + columnLength + fieldNames.size() - 1 + ") VALUES ".length();
        long valuesLength = (2L * fieldNames.size() + 1) * rowCount + rowCount - 1;
        if (prefixLength + valuesLength > 25000) {
            throw new IllegalArgumentException("INSERT SQL exceeds 25000 characters");
        }
        String row = "(" + String.join(",", Collections.nCopies(fieldNames.size(), "?")) + ")";
        return "INSERT INTO " + target + " (" + String.join(",", columns) + ") VALUES "
                + String.join(",", Collections.nCopies(rowCount, row));
    }

    /** 通过现有 CREATE 组装逻辑完成已验证的同产品列及主键重建。 */
    public static String getDDLCreateTableSQL(AbstractDatabase database, TableSchemaMetadata metadata,
            String schemaName, String tableName) {
        ProductTypeEnum type = database.getDatabaseType();
        if (!metadata.provenance().nativeProduct(type)) {
            throw new UnsupportedOperationException("Native schema provenance does not match target");
        }
        if (metadata.additionalObjects().state() != MetadataValue.State.ABSENT) {
            throw new UnsupportedOperationException(
                    "Additional schema objects require an explicit reconstruction plan");
        }
        metadata.columns().forEach(column -> NativeColumnSql.definition(type, column));
        return getDDLCreateTableSQL(database, metadata.columns(), metadata.primaryKeys(), schemaName, tableName, false);
    }

    /**
     * 生成单条建表 DDL（简化入口：不带表注释与表属性，不内联字段注释）
     *
     * @param type        目标数据库类型
     * @param fieldNames  字段描述列表
     * @param primaryKeys 主键字段名列表
     * @param schemaName  目标 schema 名
     * @param tableName   目标表名
     * @param autoIncr    数值主键是否使用自增
     * @return 建表 DDL 语句
     */
    /** 供受管理消费方使用的显式方言快照；不查询进程全局工厂。 */
    public static String getDDLCreateTableSQL(AbstractDatabase db, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, boolean autoIncr) {
        return getDDLCreateTableSQL(db, fieldNames, primaryKeys, schemaName, tableName, autoIncr,
                TypeMappingOptions.defaults());
    }

    /** 使用显式跨产品类型映射目录和 LOSSY 接受策略生成单个 CREATE。 */
    public static String getDDLCreateTableSQL(AbstractDatabase db, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, boolean autoIncr,
            TypeMappingOptions typeMapping) {
        return assembleCreate(db, fieldNames, primaryKeys, schemaName, tableName, false, null, autoIncr,
                Collections.emptyMap(), false, Map.of(), typeMapping);
    }

    /**
     * 预览跨产品列（来源产品与目标不同的原生事实），不
     * 生成或执行 DDL。返回计划报告所有列，包括被拒绝的列。
     */
    public static TableTypeMappingPlan planCrossProductColumns(AbstractDatabase db, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, TypeMappingOptions typeMapping) {
        ProductTypeEnum target = db.getDatabaseType();
        return typeMapping.resolvedMappings().plan(target, crossProductColumns(target, fieldNames), primaryKeys,
                typeMapping.lossyAcceptance());
    }

    /**
     * 生成单条建表 DDL（核心实现）
     * <p>
     * 主要流程：
     * 1. 按目标库特性对字段排序（目前仅 StarRocks 要求主键字段必须排在最前）；
     * 2. 拼接 CREATE TABLE + 带引号的全限定表名；
     * 3. 逐字段调用方言实现的 getFieldDefinition 生成字段定义（类型映射的核心步骤）；
     * 4. 调用 appendPrimaryKeyForCreateTableSql 追加主键定义；
     * 5. 调用 postAppendCreateTableSql 追加方言相关的表尾内容（存储引擎、表注释、分布键等）；
     * 6. 用 DDLFormatterUtils 格式化后返回
     *
     * @param db            目标数据库的方言实现
     * @param fieldNames    字段描述列表
     * @param primaryKeys   主键字段名列表
     * @param schemaName    目标 schema 名
     * @param tableName     目标表名
     * @param withRemarks   是否在字段定义中内联注释（仅部分数据库支持）
     * @param tableRemarks  表注释
     * @param autoIncr      数值主键是否使用自增
     * @param tblProperties 附加表属性（如 Doris 的 preferUniqueKey）
     * @return 建表 DDL 语句
     */
    public static String getDDLCreateTableSQL(
            AbstractDatabase db,
            List<ColumnDescription> fieldNames,
            List<String> primaryKeys,
            String schemaName,
            String tableName,
            boolean withRemarks,
            String tableRemarks,
            boolean autoIncr, Map<String, String> tblProperties) {
        return assembleCreate(db, fieldNames, primaryKeys, schemaName, tableName, withRemarks, tableRemarks,
                autoIncr, tblProperties, false, Map.of(), TypeMappingOptions.defaults());
    }

    /**
     * 拒绝当前实现未支持的 {@code assembleCreate} 操作。
     */
    private static String assembleCreate(AbstractDatabase db, List<ColumnDescription> fieldNames,
            List<String> primaryKeys,
            String schemaName, String tableName, boolean withRemarks, String tableRemarks, boolean autoIncr,
            Map<String, String> tblProperties, boolean sequenceDependencies,
            Map<String, cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation> identitySequenceNames,
            TypeMappingOptions typeMapping) {
        //ProductTypeEnum type = db.getDatabaseType();
        StringBuilder sb = new StringBuilder();
        // 1. 按目标库要求调整字段顺序（StarRocks 主键需在最前）
        List<ColumnDescription> orderedFieldNames = orderFieldsForCreateTable(db.getDatabaseType(), fieldNames,
                primaryKeys);
        if (orderedFieldNames.isEmpty() || orderedFieldNames.size() > 2000) {
            throw new IllegalArgumentException("CREATE TABLE requires between 1 and 2000 columns");
        }
        var names = orderedFieldNames.stream().map(ColumnDescription::getFieldName).collect(Collectors.toSet());
        if (names.size() != orderedFieldNames.size() || !names.containsAll(primaryKeys)
                || !names.containsAll(identitySequenceNames.keySet())
                || new java.util.HashSet<>(primaryKeys).size() != primaryKeys.size()) {
            throw new IllegalArgumentException("Duplicate columns or invalid primary key references");
        }
        List<String> pks = List.copyOf(primaryKeys); // 复合键顺序独立于列顺序。
        // 在任何语句文本生成前，对整表的跨产品原生列统一决策。
        TableTypeMappingPlan typePlan = null;
        List<ColumnDescription> crossProduct = crossProductColumns(db.getDatabaseType(), orderedFieldNames);
        if (!crossProduct.isEmpty()) {
            typePlan = typeMapping.resolvedMappings().plan(db.getDatabaseType(), crossProduct, pks,
                    typeMapping.lossyAcceptance()).requireAccepted();
        }

        sb.append(Const.CREATE_TABLE);
        // if(ifNotExist && type!=DatabaseType.ORACLE) {
        // sb.append( Const.IF_NOT_EXISTS );
        // }
        boolean nativeColumns = orderedFieldNames.stream().anyMatch(column ->
                column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE);
        if (nativeColumns && autoIncr) {
            throw new UnsupportedOperationException(
                    "Automatic identity generation from native metadata requires an explicit identity strategy");
        }
        sb.append(nativeColumns ? cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .qualifiedTableName(db.getDatabaseType(), schemaName, tableName) : db.getQuotedSchemaTableCombination(
                schemaName, tableName));
        sb.append("(");

        // 2. 逐字段生成方言相关的字段定义（类型映射核心）
        for (int i = 0; i < orderedFieldNames.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            } else {
                sb.append("  ");
            }

            ColumnDescription column = orderedFieldNames.get(i);
            if (column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE
                    && column.getMetadataEvidence().provenance().nativeProduct(db.getDatabaseType())) {
                sb.append(sequenceDependencies ? NativeColumnSql.definition(db.getDatabaseType(), column, schemaName,
                        tableName, identitySequenceNames.get(column.getFieldName()))
                        : NativeColumnSql.definition(db.getDatabaseType(), column));
            } else if (column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE) {
                sb.append(typePlan.definition(column.getFieldName()))
                        .append(inlineComment(db.getDatabaseType(), column.getRemarks(), withRemarks));
            } else {
                if (column.getDefaultValue() != null && column.getDbType() != null && column.getDbType() != db.getDatabaseType()) {
                    throw new UnsupportedOperationException(
                            "Cross-product default expressions require an explicit mapping");
                }
                ColumnMetaData v = column.getMetaData();
                if (v.getType() == ColumnMetaData.TYPE_NONE) {
                    throw new UnsupportedOperationException("Unknown column type cannot generate DDL");
                }
                sb.append(db.getFieldDefinition(v, pks, autoIncr, false, withRemarks));
            }
        }

        // 3. 追加主键定义与方言相关的表尾内容（引擎/注释/分布键等）
        // Doris/StarRocks 通过原生方言在列列表外追加键模型。
        if (nativeColumns && !pks.isEmpty() && db.getDatabaseType() != ProductTypeEnum.DORIS
                && db.getDatabaseType() != ProductTypeEnum.STARROCKS) {
            sb.append(", PRIMARY KEY (").append(pks.stream().map(name ->
                            cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                                    db.getDatabaseType(), name))
                    .collect(Collectors.joining(", "))).append(')');
        } else {
            db.appendPrimaryKeyForCreateTableSql(sb, pks);
        }
        sb.append(")");
        db.postAppendCreateTableSql(sb, tableRemarks, pks, tblProperties, orderedFieldNames.get(0).getFieldName());
        String configuredCreate = db.applyCreateTableOptions(sb.toString(), schemaName, tableName, tblProperties);
        if (tblProperties != null && tblProperties.containsKey(
                cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY)) {
            return configuredCreate;
        }

        if (orderedFieldNames.stream()
                .anyMatch(column -> column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE)) {
            if (db.getDatabaseType() == ProductTypeEnum.SQLSERVER
                    && orderedFieldNames.stream().allMatch(column -> column.getMetadataEvidence().provenance()
                    .nativeProduct(ProductTypeEnum.SQLSERVER))) {
                validateSqlServerCreate(orderedFieldNames, pks);
                return sb.toString();
            }
            // TiDB/GBase 8a 对受支持的原生列 CREATE 使用 MySQL 语法。此本地语法检查
            // 不允许任意迁移脚本，也不保证其事务语义。
            // PG 系引擎（HighGo/UXDB/Greenplum）生成 PostgreSQL 语法的 CREATE；此检查
            // 仅作为生成 DDL 的语法验证依据，不允许它们的任意迁移脚本。
            // 受支持解析器均不接受 Doris CREATE（键模型、分布、属性）；其列已
            // 通过上方映射计划校验，所以直接返回组装后的语句。
            var syntaxProduct = db.getDatabaseType() == ProductTypeEnum.TIDB || db.getDatabaseType() == ProductTypeEnum.GBASE8A
                    ? ProductTypeEnum.MYSQL
                    : db.getDatabaseType().isLikePostgres() ? ProductTypeEnum.POSTGRESQL : db.getDatabaseType();
            // Oracle/Kingbase/DM CREATE 文本同样由已校验映射计划生成，且仅对
            // 已验证的目标服务器执行；受支持解析器不接受这些方言，沿用 Doris 的做法。
            // 跨产品 SQL Server/Sybase/DB2 CREATE 文本同样由已校验映射
            // 计划生成，且仅对已验证的目标服务器执行；受支持解析器不接受这些方言，
            // 沿用 Doris 的做法。同产品 SQL Server 保留上方原生语法验证依据。
            // SQLite 生成的 CREATE 由同一已接受列计划和带引号标识符组装。
            // 这不允许任意 SQLite 脚本；实际文件引擎仍作为执行验证依据。
            // ClickHouse CREATE 同样来自已接受的整列计划，或已校验的普通
            // 同产品原生标量定义。其
            // 没有受支持的迁移脚本解析器可以解析 Nullable/Decimal 类型和 MergeTree 子句；
            // 原生引擎作为执行验证依据。这不允许任意 ClickHouse SQL。
            if (db.getDatabaseType() == ProductTypeEnum.DORIS || db.getDatabaseType() == ProductTypeEnum.STARROCKS
                    || db.getDatabaseType() == ProductTypeEnum.ORACLE
                    || db.getDatabaseType() == ProductTypeEnum.KINGBASE || db.getDatabaseType() == ProductTypeEnum.DM
                    || db.getDatabaseType() == ProductTypeEnum.SQLSERVER || db.getDatabaseType() == ProductTypeEnum.SYBASE
                    || db.getDatabaseType() == ProductTypeEnum.DB2 || db.getDatabaseType() == ProductTypeEnum.SQLITE3
                    || db.getDatabaseType() == ProductTypeEnum.CLICKHOUSE) {
                return sb.toString();
            }
            // Greenplum 分布子句仅由有界原生钩子语法接受。
            var plan = db.getDatabaseType() == ProductTypeEnum.GREENPLUM
                    ? cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parseMigrationHook(
                    ProductTypeEnum.GREENPLUM, sb.toString())
                    : cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(syntaxProduct,
                    sb.toString());
            if (plan.steps().size() != 1) {
                throw new IllegalArgumentException("CREATE must contain exactly one statement");
            }
            return sb.toString(); // 保留原生表达式和字面量文本，不再次格式化。
        }
        return DDLFormatterUtils.format(sb.toString());
    }

    /** Druid 拒绝合法的转义方括号。使用同一已校验列模型
     * 和安全占位标识符构建语法验证依据；绝不替换调用方传入原始 SQL 中的标记。 */
    private static void validateSqlServerCreate(List<ColumnDescription> columns, List<String> keys) {
        StringBuilder syntax = new StringBuilder("CREATE TABLE [schema_name].[table_name] (");
        Map<String, String> names = new java.util.LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                syntax.append(',');
            }
            var column = columns.get(i).copy();
            String name = "field_" + i;
            names.put(column.getFieldName(), name);
            column.setFieldName(name);
            // 先校验实际原生模型。Druid 1.2.28 将 IDENTITY 数值强转为 Integer，
            // 并拒绝合法的 NOT FOR REPLICATION 标记。仅语法验证依据使用
            // 有界代表性标识生成值；可执行 SQL 保留已校验的原始值。
            NativeColumnSql.definition(ProductTypeEnum.SQLSERVER, column);
            var evidence = column.getMetadataEvidence();
            if (evidence.identity().state() == MetadataValue.State.VALUE) {
                column.setMetadataEvidence(new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                        evidence.provenance(), evidence.nativeType(), evidence.defaultExpression(), evidence.nullable(),
                        evidence.numericPrecision(), evidence.numericScale(), evidence.characterLength(),
                        MetadataValue.value("IDENTITY(1,1)"), evidence.generatedExpression(), evidence.charset(),
                        evidence.collation(), evidence.sequence()));
            }
            syntax.append(NativeColumnSql.definition(ProductTypeEnum.SQLSERVER, column));
        }
        if (!keys.isEmpty()) {
            syntax.append(", PRIMARY KEY (")
                    .append(keys.stream().map(key -> "[" + names.get(key) + "]").collect(Collectors.joining(",")))
                    .append(')');
        }
        syntax.append(')');
        var statements = com.alibaba.druid.sql.SQLUtils.parseStatements(syntax.toString(),
                com.alibaba.druid.DbType.sqlserver);
        if (statements.size() != 1 || !(statements.getFirst() instanceof com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement)) {
            throw new IllegalArgumentException("Native CREATE must contain exactly one table statement");
        }
    }

    /** 重建的 CREATE INDEX 语句；每个跳过的索引保留原因，供调用方报告。 */
    public record IndexDdlPlan(List<String> statements, List<SkippedIndex> skipped) {

        /**
         * 初始化 {@code IndexDdlPlan}。
         */
        public IndexDdlPlan {
            statements = List.copyOf(statements);
            skipped = List.copyOf(skipped);
        }
    }

    /** redundant=true：目标已约束相同的键（主键或完全相同的保留索引）。 */
    public record SkippedIndex(String indexName, String reason, boolean redundant) {

    }

    /**
     * 为已映射到目标列名的索引生成 CREATE INDEX（索引元数据模型移植自 dbswitch 2.0.2）。
     * <p>
     * 标识符引用与同一张表的 CREATE TABLE 一致：存在原生列证据时用 quotePhysicalIdentifier，否则用方言的主键列引用。
     * 键顺序、唯一性与 DESC 原样保留。任一键不在目标表或目标类型不能建索引，或唯一索引含可空列而目标对 NULL 的唯一性
     * 判断比源端更严格时，整个索引跳过，不删键、不改唯一性；与主键列集合相同或与已保留索引键序相同的视为冗余。
     * 索引名只由目标表名和源索引名决定，重复执行得到相同名称。
     */
    public static IndexDdlPlan getCreateIndexSqlList(AbstractDatabase db, ProductTypeEnum sourceProduct,
            List<ColumnDescription> createdColumns, List<String> primaryKeys, String schemaName, String tableName,
            List<IndexDescription> indexes) {
        ProductTypeEnum target = db.getDatabaseType();
        Map<String, ColumnDescription> columns = new HashMap<>();
        for (ColumnDescription column : createdColumns) {
            columns.put(column.getFieldName(), column);
        }
        boolean nativeColumns = createdColumns.stream().anyMatch(column ->
                column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE);
        UnaryOperator<String> quote = nativeColumns
                ? name -> RestrictedSelectGuard.quotePhysicalIdentifier(target, name)
                : name -> db.getPrimaryKeyAsString(List.of(name)).trim();
        Set<String> keys = Set.copyOf(primaryKeys);
        List<SkippedIndex> skipped = new ArrayList<>();
        Map<List<String>, IndexDescription> retained = new LinkedHashMap<>();
        for (IndexDescription index : indexes) {
            List<String> names = index.fieldNames();
            String rejection = null;
            for (String name : names) {
                ColumnDescription column = name == null ? null : columns.get(name);
                if (column == null) {
                    rejection = "键 " + name + " 不在目标表中";
                    break;
                }
                if (!db.canCreateIndex(column.getMetaData())) {
                    rejection = "列 " + name + " 的目标类型不能建索引";
                    break;
                }
            }
            if (rejection == null && new HashSet<>(names).size() != names.size()) {
                rejection = "索引键重复";
            }
            if (rejection == null && index.unique() && !uniqueNullsCompatible(sourceProduct, target)
                    && names.stream().anyMatch(name -> columns.get(name).isNullable())) {
                rejection = "唯一索引含可空列，目标产品对 NULL 的唯一性判断比源端更严格";
            }
            if (rejection != null) {
                skipped.add(new SkippedIndex(index.indexName(), rejection, false));
                continue;
            }
            if (keys.size() == names.size() && keys.containsAll(names)) {
                skipped.add(new SkippedIndex(index.indexName(), "与主键列相同，已由主键约束保证", true));
                continue;
            }
            List<String> signature = index.indexFields().stream()
                    .map(field -> field.fieldName() + (field.descending() ? " DESC" : "")).toList();
            IndexDescription previous = retained.get(signature);
            if (previous == null) {
                retained.put(signature, index);
            } else if (!previous.unique() && index.unique()) {
                retained.put(signature, index);
                skipped.add(
                        new SkippedIndex(previous.indexName(), "与唯一索引 " + index.indexName() + " 键序相同", true));
            } else {
                skipped.add(new SkippedIndex(index.indexName(), "与索引 " + previous.indexName() + " 键序相同", true));
            }
        }
        String relation = nativeColumns ? RestrictedSelectGuard.qualifiedTableName(target, schemaName, tableName)
                : db.getQuotedSchemaTableCombination(schemaName, tableName).trim();
        List<String> statements = new ArrayList<>(retained.size());
        for (IndexDescription index : retained.values()) {
            String name = quote.apply(targetIndexName(target, tableName, index.indexName()));
            if (db.qualifiesIndexName()) {
                name = quote.apply(schemaName) + "." + name;
            }
            String keyList = index.indexFields().stream()
                    .map(field -> quote.apply(field.fieldName()) + (field.descending() ? " DESC" : ""))
                    .collect(Collectors.joining(", "));
            statements.add("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + name + " ON " + relation
                    + " (" + keyList + ")");
        }
        return new IndexDdlPlan(statements, skipped);
    }

    /** 唯一索引对 NULL 的判断：0 多个 NULL 互不冲突；1 复合键部分为 NULL 时仍比较其余键（Oracle）；2 NULL 视为相等。 */
    private static int uniqueNullStrictness(ProductTypeEnum product) {
        return switch (product) {
            case POSTGRESQL, GREENPLUM, HIGHGO, UXDB, OPENGAUSS, MYSQL, MARIADB, TIDB, SQLITE3 -> 0;
            case ORACLE -> 1;
            case SQLSERVER, SQLSERVER2000, SYBASE, DB2 -> 2;
            default -> -1;
        };
    }

    /** 源端已满足的数据在目标唯一索引下仍然满足：同产品，或目标不比源端更严格；语义未知时视为不兼容。 */
    private static boolean uniqueNullsCompatible(ProductTypeEnum source, ProductTypeEnum target) {
        if (source == null || target == null) {
            return false;
        }
        if (source == target) {
            return true;
        }
        int sourceStrictness = uniqueNullStrictness(source);
        int targetStrictness = uniqueNullStrictness(target);
        return sourceStrictness >= 0 && targetStrictness >= 0 && targetStrictness <= sourceStrictness;
    }

    /** 不超过 28 个字符的普通标识符，兼容最短的 30 字节索引名上限。 */
    private static String targetIndexName(ProductTypeEnum target, String tableName, String sourceIndexName) {
        String readable = sourceIndexName.replaceAll("[^A-Za-z0-9_]", "_");
        if (readable.length() > 12) {
            readable = readable.substring(0, 12);
        }
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((tableName + '\0' + sourceIndexName).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String name = "ix_" + readable + "_" + digest.substring(0, 12);
        return target == ProductTypeEnum.ORACLE || target == ProductTypeEnum.DM || target == ProductTypeEnum.DB2
                || target == ProductTypeEnum.OSCAR ? name.toUpperCase(Locale.ROOT) : name.toLowerCase(Locale.ROOT);
    }

    /** 原生事实列，其来源并非目标已验证的原生产品。 */
    private static List<ColumnDescription> crossProductColumns(ProductTypeEnum target,
            List<ColumnDescription> columns) {
        return columns.stream()
                .filter(column -> column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE
                        && !column.getMetadataEvidence().provenance().nativeProduct(target)).toList();
    }

    /**
     * MySQL 系内联列注释使用双写引号。反斜杠在 NO_BACKSLASH_ESCAPES 下含义不同，
     * 所以显式拒绝此类注释，不丢弃，也不生成依赖模式的引号形式。
     */
    private static String inlineComment(ProductTypeEnum target, String remarks, boolean withRemarks) {
        if (!withRemarks || remarks == null || remarks.isBlank() || (target != ProductTypeEnum.MYSQL
                && target != ProductTypeEnum.MARIADB && target != ProductTypeEnum.TIDB)) {
            return "";
        }
        return " COMMENT " + quoteMysqlComment(remarks, 1024);
    }

    /**
     * 不需要连接的 MySQL/MariaDB 注释字面量，无论是否启用 NO_BACKSLASH_ESCAPES 均有效。
     * 由旧方言和原生事实渲染器复用。MySQL 8.4/MariaDB 11.4 注释目录
     * 将补充字符替换为 '?'，所以其已验证的注释值域限于 BMP 文本。
     */
    public static String quoteMysqlComment(String remarks, int maxCharacters) {
        if (remarks == null || maxCharacters < 1 || remarks.indexOf('\0') >= 0 || remarks.indexOf('\\') >= 0
                || remarks.chars().anyMatch(c -> Character.isSurrogate((char) c)) || remarks.length() > maxCharacters) {
            throw new IllegalArgumentException(
                    "Comment cannot be represented in the verified catalog without session-dependent quoting");
        }
        return "'" + remarks.replace("'", "''") + "'";
    }

    /**
     * 建表字段排序：StarRocks 要求主键（Key 列）必须位于所有字段之前，
     * 这里把主键字段挪到列表前部；其他数据库保持原顺序
     */
    private static List<ColumnDescription> orderFieldsForCreateTable(ProductTypeEnum type,
            List<ColumnDescription> fieldNames,
            List<String> primaryKeys) {
        // Doris 的键模型同样要求键列在最前；其余数据库保持原顺序。
        if ((!ProductTypeEnum.STARROCKS.equals(type) && !ProductTypeEnum.DORIS.equals(type)) || CollectionUtils.isEmpty(
                primaryKeys)) {
            return fieldNames;
        }
        List<ColumnDescription> orderedFieldNames = new ArrayList<>();
        fieldNames.stream()
                .filter(cd -> primaryKeys.contains(cd.getFieldName()))
                .forEach(orderedFieldNames::add);
        fieldNames.stream()
                .filter(cd -> !primaryKeys.contains(cd.getFieldName()))
                .forEach(orderedFieldNames::add);
        return orderedFieldNames;
    }

    /**
     * 生成完整的建表语句列表（建表 DDL + 可能的注释语句），是结构迁移建表的主入口
     * <p>
     * 按目标库分三种情况：
     * 1. Hive 系：建表语法特殊，直接由方言实现的 getCreateTableSqlList 生成全部语句；
     * 2. 不支持独立注释语句的数据库（noCommentStatement，如 MySQL）：
     *    注释内联在建表语句中，返回单条建表语句；
     * 3. 其他数据库（如 Oracle/PostgreSQL）：先生成建表语句，
     *    再生成 COMMENT ON TABLE/COLUMN 注释语句列表，建表语句放在首位一并返回
     *
     * @param type          目标数据库类型
     * @param fieldNames    字段描述列表
     * @param primaryKeys   主键字段名列表
     * @param schemaName    目标 schema 名
     * @param tableName     目标表名
     * @param tableRemarks  表注释
     * @param autoIncr      数值主键是否使用自增
     * @param tblProperties 附加表属性
     * @return 依次执行即可完成建表的 SQL 语句列表
     */
    /** 受管理方言重载，同时保留 CREATE 和独立 COMMENT 语句。 */
    public static List<String> getDDLCreateTableSQL(AbstractDatabase db, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, String tableRemarks,
            boolean autoIncr, Map<String, String> tblProperties) {
        return getDDLCreateTableSQL(db, fieldNames, primaryKeys, schemaName, tableName, tableRemarks, autoIncr,
                tblProperties, Map.of());
    }

    /**
     * 根据表和列定义生成建表 SQL。
     */
    public static List<String> getDDLCreateTableSQL(AbstractDatabase db, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, String tableRemarks, boolean autoIncr,
            Map<String, String> tblProperties,
            Map<String, cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation> identitySequenceNames) {
        return getDDLCreateTableSQL(db, fieldNames, primaryKeys, schemaName, tableName, tableRemarks, autoIncr,
                tblProperties,
                identitySequenceNames, TypeMappingOptions.defaults());
    }

    /** 使用显式跨产品类型映射目录和 LOSSY 接受策略生成完整语句列表。 */
    public static List<String> getDDLCreateTableSQL(AbstractDatabase db, List<ColumnDescription> fieldNames,
            List<String> primaryKeys, String schemaName, String tableName, String tableRemarks, boolean autoIncr,
            Map<String, String> tblProperties,
            Map<String, cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation> identitySequenceNames,
            TypeMappingOptions typeMapping) {
        java.util.Objects.requireNonNull(typeMapping, "typeMapping");
        identitySequenceNames = Map.copyOf(identitySequenceNames);
        ProductTypeEnum type = db.getDatabaseType();
        if (!identitySequenceNames.isEmpty() && type != ProductTypeEnum.POSTGRESQL) {
            throw new IllegalArgumentException("Named identity dependencies require PostgreSQL");
        }
        if (type.isLikeHive()) {
            // Hive 系建表语法特殊，由方言实现直接生成全部语句
            return db.getCreateTableSqlList(
                    fieldNames, primaryKeys, schemaName, tableName, tableRemarks, autoIncr, tblProperties);
        } else if (type.noCommentStatement()) {
            // 注释内联型数据库（如 MySQL）：仅一条建表语句
            String createTableSql = assembleCreate(db, fieldNames, primaryKeys, schemaName,
                    tableName, true, tableRemarks, autoIncr, tblProperties, type == ProductTypeEnum.POSTGRESQL,
                    identitySequenceNames, typeMapping);
            return Lists.newArrayList(createTableSql);
        } else {
            // 独立注释型数据库：建表语句 + COMMENT ON 注释语句
            String createTableSql = assembleCreate(db, fieldNames, primaryKeys, schemaName,
                    tableName, true, tableRemarks, autoIncr, tblProperties, type == ProductTypeEnum.POSTGRESQL,
                    identitySequenceNames, typeMapping);
            TableDescription td = new TableDescription();
            td.setSchemaName(schemaName);
            td.setTableName(tableName);
            td.setRemarks(tableRemarks);
            td.setTableType(DBTableType.TABLE.name());
            List<String> results = db.getTableColumnCommentDefinition(td, fieldNames);
            if (CollectionUtils.isEmpty(results)) {
                results = Lists.newArrayList(createTableSql);
            } else {
                results.add(0, createTableSql);
            }
            if (type == ProductTypeEnum.POSTGRESQL) {
                List<String> before = new ArrayList<>(), after = new ArrayList<>();
                for (var column : fieldNames) {
                    var sequence = column.getMetadataEvidence().sequence().value();
                    if (sequence == null || sequence.kind() != cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata.Kind.OWNED_DEFAULT
                            || column.getMetadataEvidence().defaultExpression().state() != MetadataValue.State.VALUE) {
                        continue;
                    }
                    String destination = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                            type,
                            schemaName, NativeColumnSql.sequenceName(schemaName, tableName, column.getFieldName()));
                    before.add(
                            "CREATE SEQUENCE " + destination + " AS " + sequence.dataType() + " " + sequence.optionsSql());
                    after.add("ALTER SEQUENCE " + destination + " OWNED BY "
                            + cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.qualifiedTableName(
                            type, schemaName, tableName)
                            + "." + cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                            type, column.getFieldName()));
                }
                before.addAll(results);
                before.addAll(after);
                cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan.parse(type, String.join(";\n", before));
                return List.copyOf(before);
            }
            return results;
        }
    }

}

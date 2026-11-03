package cn.iocoder.yudao.framework.dbswitch.core.service.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.DatabaseAwareUtils;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableData;
import cn.iocoder.yudao.framework.dbswitch.core.model.SchemaTableMeta;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByDatasourceService;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用DataSource对象的元数据获取服务
 *
 * @author jrl
 */
public class MetaDataByDataSourceServiceImpl implements IMetaDataByDatasourceService {

    private DataSource dataSource;

    private AbstractDatabase database;

    private ProductTypeEnum type;

    /** 所属注册表提供的跨产品类型映射；仅当注册表没有目录信息（如模拟对象）时为 null。 */
    private cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMappings typeMappings;

    /**
     * 使用给定参数初始化 {@code MetaDataByDataSourceServiceImpl}。
     */
    public MetaDataByDataSourceServiceImpl(DataSource dataSource) {
        this(dataSource, DatabaseAwareUtils.getDatabaseTypeByDataSource(dataSource));
    }

    /**
     * 使用给定参数初始化 {@code MetaDataByDataSourceServiceImpl}。
     */
    public MetaDataByDataSourceServiceImpl(DataSource dataSource, ProductTypeEnum type) {
        this(dataSource, type, DbSwitchProductRegistry.discover());
    }

    /**
     * 初始化 {@code MetaDataByDataSourceServiceImpl}。
     */
    public MetaDataByDataSourceServiceImpl(DataSource dataSource, ProductTypeEnum type,
            DbSwitchProductRegistry registry) {
        this.dataSource = dataSource;
        this.database = registry.metadata(type);
        this.type = type;
        this.typeMappings = registry.typeMappings();
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return this.dataSource;
    }

    /**
     * 获取数据库的schema模式列表。
     */
    @Override
    public List<String> querySchemaList() {
        try (Connection connection = dataSource.getConnection()) {
            return database.querySchemaList(connection);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取指定Schema下所有的表列表。
     */
    @Override
    public List<TableDescription> queryTableList(String schemaName) {
        try (Connection connection = getConnectionWithRetry()) {
            return database.queryTableList(connection, schemaName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取物理表的DDL建表语句。
     */
    @Override
    public String getTableDDL(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return database.getTableDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取物理表的注释。
     */
    @Override
    public String getTableRemark(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            TableDescription td = database.queryTableMeta(connection, schemaName, tableName);
            return null == td ? null : td.getRemarks();
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取物理表的DDL建表语句。
     */
    @Override
    public String getViewDDL(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return database.getViewDDL(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取指定schema.table的字段名列表。
     */
    @Override
    public List<String> queryTableColumnName(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return database.queryTableColumnName(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取指定schema.table的表结构字段信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(String schemaName, String tableName) {
        return queryTableColumnMeta(schemaName, tableName, type == ProductTypeEnum.POSTGRESQL
                || type == ProductTypeEnum.TIDB || type == ProductTypeEnum.SQLSERVER);
    }

    /**
     * 读取当前表列元数据，并附带原生类型和结构证据。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaWithNativeEvidence(String schemaName, String tableName) {
        return queryTableColumnMeta(schemaName, tableName, true);
    }

    /**
     * 读取表列的 JDBC 元数据。
     */
    private List<ColumnDescription> queryTableColumnMeta(String schemaName, String tableName, boolean nativeEvidence) {
        try (Connection connection = dataSource.getConnection()) {
            List<ColumnDescription> columnDescriptions = database.queryTableColumnMeta(connection, schemaName,
                    tableName);
            database.setColumnDefaultValue(connection, schemaName, tableName, columnDescriptions);
            database.setColumnIndexInfo(connection, schemaName, tableName, columnDescriptions);
            if (nativeEvidence) {
                var nativeSchema = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(
                        connection, type, schemaName, tableName, 10);
                var nativeColumns = nativeSchema.columns().stream().collect(Collectors.toMap(
                        ColumnDescription::getFieldName, column -> column));
                if (nativeColumns.size() != columnDescriptions.size()) {
                    throw new SQLException("Schema changed during metadata observation");
                }
                for (var column : columnDescriptions) {
                    var nativeColumn = nativeColumns.get(column.getFieldName());
                    if (nativeColumn == null) {
                        throw new SQLException("Schema changed during metadata observation");
                    }
                    var evidence = nativeColumn.getMetadataEvidence();
                    if (!evidence.provenance().nativeProduct(type)
                            || evidence.nativeType()
                            .state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.VALUE) {
                        throw new SQLException(
                                "Native column metadata is incomplete; portable fallback is not allowed");
                    }
                    column.setMetadataEvidence(nativeColumn.getMetadataEvidence());
                    column.setFieldType(nativeColumn.getFieldType());
                    column.setFieldTypeName(nativeColumn.getFieldTypeName());
                    column.setFiledTypeClassName(nativeColumn.getFiledTypeClassName());
                    column.setPrecisionSize(nativeColumn.getPrecisionSize());
                    column.setScaleSize(nativeColumn.getScaleSize());
                    column.setDisplaySize(nativeColumn.getDisplaySize());
                    column.setSigned(nativeColumn.isSigned());
                    column.setDefaultValue(nativeColumn.getDefaultValue());
                    column.setNullable(nativeColumn.isNullable());
                    column.setAutoIncrement(nativeColumn.isAutoIncrement());
                    column.setPk(nativeColumn.isPk());
                }
            }
            return columnDescriptions;
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return database.queryTableColumnMeta(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取指定SQL结构字段信息。
     */
    @Override
    public List<ColumnDescription> querySqlColumnMeta(String querySql) {
        try (Connection connection = dataSource.getConnection()) {
            return database.querySelectSqlColumnMeta(connection, querySql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的主键信息字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return database.queryTablePrimaryKeys(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的索引列表（移植自 dbswitch 2.0.2），语义见 {@code IDatabaseInterface#queryTableIndexes}。
     */
    @Override
    public List<IndexDescription> queryTableIndexes(String schemaName, String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return database.queryTableIndexes(connection, schemaName, tableName);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的元数据。
     */
    @Override
    public SchemaTableMeta queryTableMeta(String schemaName, String tableName) {
        SchemaTableMeta tableMeta = new SchemaTableMeta();

        try (Connection connection = dataSource.getConnection()) {
            TableDescription tableDesc = database.queryTableMeta(connection, schemaName, tableName);
            if (null == tableDesc) {
                throw new IllegalArgumentException("Table Or View Not Exist");
            }

            List<ColumnDescription> columns = database.queryTableColumnMeta(
                    connection, schemaName, tableName);

            List<String> pks;
            String createSql;
            List<IndexDescription> indexes;
            if (tableDesc.isViewTable()) {
                pks = Collections.emptyList();
                createSql = database.getViewDDL(connection, schemaName, tableName);
                indexes = Collections.emptyList();
            } else {
                pks = database.queryTablePrimaryKeys(connection, schemaName, tableName);
                createSql = database.getTableDDL(connection, schemaName, tableName);
                indexes = database.queryTableIndexes(connection, schemaName, tableName);
            }

            tableMeta.setSchemaName(schemaName);
            tableMeta.setTableName(tableName);
            tableMeta.setTableType(tableDesc.getTableType());
            tableMeta.setRemarks(tableDesc.getRemarks());
            tableMeta.setColumns(columns);
            tableMeta.setPrimaryKeys(pks);
            tableMeta.setCreateSql(createSql);
            tableMeta.setIndexes(indexes);

            return tableMeta;
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 获取表的数据内容。
     */
    @Override
    public SchemaTableData queryTableData(String schemaName, String tableName, int rowCount) {
        try (Connection connection = dataSource.getConnection()) {
            return database.queryTableData(connection, schemaName, tableName, rowCount);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 测试数据库SQL查询。
     */
    @Override
    public void testQuerySQL(String sql) {
        try (Connection connection = dataSource.getConnection()) {
            database.testQuerySQL(connection, sql);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 根据字段结构信息组装对应数据库的建表DDL语句。
     */
    @Override
    public List<String> getDDLCreateTableSQL(ProductTypeEnum type,
            List<ColumnDescription> fieldNames, List<String> primaryKeys, String schemaName,
            String tableName, String tableRemarks, boolean autoIncr, Map<String, String> tblProperties) {
        return getDDLCreateTableSQL(type, fieldNames, primaryKeys, schemaName, tableName, tableRemarks,
                autoIncr, tblProperties, typeMapping());
    }

    /**
     * 根据表和列定义生成建表 SQL。
     */
    @Override
    public List<String> getDDLCreateTableSQL(ProductTypeEnum type,
            List<ColumnDescription> fieldNames, List<String> primaryKeys, String schemaName,
            String tableName, String tableRemarks, boolean autoIncr, Map<String, String> tblProperties,
            cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions typeMapping) {
        java.util.Objects.requireNonNull(typeMapping, "typeMapping");
        if (type != database.getDatabaseType()) {
            throw new IllegalArgumentException("DDL target does not match the configured product");
        }
        if (database.requiresDialectConnection()) {
            try (Connection connection = dataSource.getConnection()) {
                database.prepareDialect(connection);
                return GenerateSqlUtils.getDDLCreateTableSQL(database, fieldNames, primaryKeys, schemaName, tableName,
                        tableRemarks, autoIncr, tblProperties, java.util.Map.of(), typeMapping);
            } catch (SQLException unavailable) {
                throw new IllegalStateException("DDL target dialect could not be verified");
            }
        }
        return GenerateSqlUtils.getDDLCreateTableSQL(database, fieldNames, primaryKeys, schemaName, tableName,
                tableRemarks, autoIncr, tblProperties, java.util.Map.of(), typeMapping);
    }

    /**
     * 返回当前产品的类型映射能力或实现。
     */
    private cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions typeMapping() {
        return typeMappings == null ? cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions.defaults()
                : cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions.of(typeMappings);
    }

    /**
     * 根据要同步的字段添加目标表中不存在的字段。
     */
    @Override
    public void addNoExistColumnsByTarget(String targetSchemaName, String targetTableName,
            List<ColumnDescription> targetColumnDescriptions) {
        try (Connection connection = dataSource.getConnection()) {
            database.addNoExistColumnsByTarget(connection, targetSchemaName, targetTableName,
                    queryTableColumnMetaOnly(targetSchemaName, targetTableName).stream()
                            .map(ColumnDescription::getFieldName).collect(Collectors.toList()),
                    targetColumnDescriptions);
        } catch (SQLException se) {
            throw new RuntimeException(se);
        }
    }

    /**
     * 返回连接带重试。
     */
    private Connection getConnectionWithRetry() throws SQLException {
        int attempts = 0;
        SQLException last = null;
        while (attempts < 3) {
            try {
                return dataSource.getConnection();
            } catch (SQLException se) {
                last = se;
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            attempts++;
        }
        throw last;
    }
}

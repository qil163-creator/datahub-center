package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in fixtures create/drop only their own random database or schema on disposable servers. */
@Timeout(30)
class DatabaseNamespaceJdbcTest {
    @Test void sqliteMainDefaultAndExplicitAttachedSchemaRemainDistinct() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            execute(connection,"CREATE TABLE main.main_rows(id INTEGER)");
            execute(connection,"ATTACH DATABASE ':memory:' AS attached");
            execute(connection,"CREATE TABLE attached.attached_rows(id INTEGER)");
            var metadata=new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl();
            String main=DatabaseNamespaceResolver.resolve(ProductTypeEnum.SQLITE3,"file.sqlite",null).orElseThrow();
            String attached=DatabaseNamespaceResolver.resolve(ProductTypeEnum.SQLITE3,"file.sqlite","attached").orElseThrow();
            assertEquals(List.of("main_rows"),metadata.queryTableList(connection,main).stream().map(table->table.getTableName()).toList());
            assertEquals(List.of("attached_rows"),metadata.queryTableList(connection,attached).stream().map(table->table.getTableName()).toList());
        }
    }

    @Test
    @EnabledIfSystemProperty(named="datahub.test.namespace.mysql.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    void mysqlDatabaseWithoutSchemaListsAndDescribesTheExactDatabase() throws Exception {
        String database="Namespace_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(System.getProperty("datahub.test.namespace.mysql.url"),"root","")){
            String quoted=RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL,database);
            execute(connection,"CREATE DATABASE "+quoted);
            try {
                execute(connection,"CREATE TABLE "+quoted+".`Orders` (`Id` INT PRIMARY KEY)");
                var resolved=DatabaseNamespaceResolver.resolve(ProductTypeEnum.MYSQL,database,"").orElseThrow();
                assertEquals(database,resolved);
                var metadata=new DatabaseMysqlImpl();
                var tables=metadata.queryTableList(connection,resolved);
                assertEquals(1,tables.size());assertEquals(database,tables.getFirst().getSchemaName());
                assertEquals("Orders",tables.getFirst().getTableName());
                assertEquals(List.of("Id"),metadata.queryTablePrimaryKeys(connection,resolved,"Orders"));
                assertEquals("Id",metadata.queryTableColumnMeta(connection,resolved,"Orders").getFirst().getFieldName());
                assertEquals(database,DatabaseNamespaceResolver.resolve(ProductTypeEnum.MYSQL,"different",database).orElseThrow());
            } finally {execute(connection,"DROP DATABASE "+quoted);}
        }
    }

    @Test
    @EnabledIfSystemProperty(named="datahub.test.namespace.pg.url",matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
    void postgresCatalogIsNeverSubstitutedForItsSeparateExactSchema() throws Exception {
        String schema="Namespace With Space_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(System.getProperty("datahub.test.namespace.pg.url"),"postgres","")){
            assertEquals("postgres",connection.getCatalog());
            assertTrue(DatabaseNamespaceResolver.resolve(ProductTypeEnum.POSTGRESQL,connection.getCatalog(),null).isEmpty());
            String quoted=RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.POSTGRESQL,schema);
            execute(connection,"CREATE SCHEMA "+quoted);
            try {
                execute(connection,"CREATE TABLE "+quoted+".\"Orders\" (\"Id\" integer PRIMARY KEY)");
                var resolved=DatabaseNamespaceResolver.resolve(ProductTypeEnum.POSTGRESQL,connection.getCatalog(),schema).orElseThrow();
                assertEquals(schema,resolved);
                var metadata=new DatabasePostgresImpl();
                var tables=metadata.queryTableList(connection,resolved);
                assertEquals(1,tables.size());assertEquals(schema,tables.getFirst().getSchemaName());
                assertEquals("Orders",tables.getFirst().getTableName());
                assertEquals(List.of("Id"),metadata.queryTablePrimaryKeys(connection,resolved,"Orders"));
                assertEquals("Id",metadata.queryTableColumnMeta(connection,resolved,"Orders").getFirst().getFieldName());
            } finally {execute(connection,"DROP SCHEMA "+quoted+" CASCADE");}
        }
    }
    private static void execute(Connection connection,String sql)throws Exception {try(var statement=connection.createStatement()){statement.setQueryTimeout(10);statement.execute(sql);}}
}

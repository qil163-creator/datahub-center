package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseNamespaceResolverTest {
    private static final EnumSet<ProductTypeEnum> DATABASE_NAMESPACES = EnumSet.of(
            ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB, ProductTypeEnum.GBASE8A,
            ProductTypeEnum.DORIS, ProductTypeEnum.STARROCKS, ProductTypeEnum.CLICKHOUSE, ProductTypeEnum.HIVE,
            ProductTypeEnum.MONGODB, ProductTypeEnum.TDENGINE);

    @ParameterizedTest @EnumSource(ProductTypeEnum.class)
    void anExplicitSchemaRemainsExactForEveryProduct(ProductTypeEnum product) {
        assertEquals(Optional.of(" Mixed Schema "), DatabaseNamespaceResolver.resolve(product,"different"," Mixed Schema "));
    }

    @ParameterizedTest @EnumSource(ProductTypeEnum.class)
    void missingSchemaOnlyUsesDatabasesForImplementedDatabaseNamespaces(ProductTypeEnum product) {
        Optional<String> expected = switch (product) {
            case SQLITE3 -> Optional.of("main");
            case INFLUXDB3 -> Optional.of("iox");
            case ELASTICSEARCH -> Optional.of("es");
            default -> DATABASE_NAMESPACES.contains(product) ? Optional.of("Mixed_Db") : Optional.empty();
        };
        assertEquals(expected,DatabaseNamespaceResolver.resolve(product,"Mixed_Db",null));
        assertEquals(expected,DatabaseNamespaceResolver.resolve(product,"Mixed_Db",""));
        assertEquals(expected,DatabaseNamespaceResolver.resolve(product,"Mixed_Db"," \t"));
    }

    @Test void preservesLiteralWhitespaceAndNeverParsesNamesOrUrls() {
        assertEquals(Optional.of(" Database.Name "),DatabaseNamespaceResolver.resolve(ProductTypeEnum.MYSQL," Database.Name ",null));
        assertEquals(Optional.of("schema.with.dots"),DatabaseNamespaceResolver.resolve(ProductTypeEnum.POSTGRESQL,"postgres","schema.with.dots"));
    }

    @Test void absentAndUnknownConfigurationDoesNotInventDefaults() {
        assertTrue(DatabaseNamespaceResolver.resolve(ProductTypeEnum.MYSQL,null,null).isEmpty());
        assertTrue(DatabaseNamespaceResolver.resolve(ProductTypeEnum.MYSQL," \t",null).isEmpty());
        assertTrue(DatabaseNamespaceResolver.resolve(null,"database",null).isEmpty());
        assertEquals(Optional.of("explicit"),DatabaseNamespaceResolver.resolve(null,"database","explicit"));
    }

    @Test void establishedProductDefaultsDoNotRequireOrMisuseAConnectionDatabase() {
        assertEquals(Optional.of("main"),DatabaseNamespaceResolver.resolve(ProductTypeEnum.SQLITE3,"file.sqlite",null));
        assertEquals(Optional.of("iox"),DatabaseNamespaceResolver.resolve(ProductTypeEnum.INFLUXDB3,"metrics",null));
        assertEquals(Optional.of("es"),DatabaseNamespaceResolver.resolve(ProductTypeEnum.ELASTICSEARCH,null,null));
        assertEquals(Optional.of("attached"),DatabaseNamespaceResolver.resolve(ProductTypeEnum.SQLITE3,"file.sqlite","attached"));
    }

    @Test void malformedSelectedIdentifiersFailBeforeAnyDatabaseAccess() {
        assertThrows(IllegalArgumentException.class,()->DatabaseNamespaceResolver.resolve(ProductTypeEnum.MYSQL,"bad\0db",null));
        assertThrows(IllegalArgumentException.class,()->DatabaseNamespaceResolver.resolve(ProductTypeEnum.POSTGRESQL,"postgres","bad\0schema"));
    }
}

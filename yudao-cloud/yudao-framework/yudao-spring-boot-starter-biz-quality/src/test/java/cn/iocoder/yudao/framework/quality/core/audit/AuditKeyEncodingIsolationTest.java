package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Types;
import java.util.Set;

import static cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditKeyEncodingIsolationTest {
    @ParameterizedTest @EnumSource(ProductTypeEnum.class)
    void productAllowListsCannotBorrowNewOrForeignCodecs(ProductTypeEnum product) {
        String name = product.name();
        assertEquals(product == ProductTypeEnum.STARROCKS, supportsEncoding(name, STARROCKS_TEXT_ALGORITHM, Types.VARCHAR));
        assertEquals(product == ProductTypeEnum.STARROCKS, supportsEncoding(name, STARROCKS_TYPED_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.STARROCKS, supportsTextGroupEncoding(name, STARROCKS_TEXT_GROUP_ENCODING, Types.VARCHAR));
        assertFalse(supportsEncoding(name, STARROCKS_TEXT_ALGORITHM, Types.LONGVARCHAR));
        assertFalse(supportsTextGroupEncoding(name, STARROCKS_TEXT_GROUP_ENCODING, Types.CHAR));
        for (int type : new int[]{Types.VARCHAR, Types.LONGVARCHAR}) {
            assertEquals(product == ProductTypeEnum.OPENGAUSS, supportsEncoding(name, OPENGAUSS_TEXT_ALGORITHM, type));
            assertEquals(product == ProductTypeEnum.OPENGAUSS, supportsTextGroupEncoding(name, OPENGAUSS_TEXT_GROUP_ENCODING, type));
        }
        assertEquals(product == ProductTypeEnum.OPENGAUSS, supportsEncoding(name, OPENGAUSS_TYPED_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertFalse(supportsEncoding(name, OPENGAUSS_TEXT_ALGORITHM, Types.CHAR));
        assertFalse(supportsEncoding(name, OPENGAUSS_TEXT_ALGORITHM, Types.OTHER));
        assertFalse(supportsTextGroupEncoding(name, OPENGAUSS_TEXT_GROUP_ENCODING, Types.OTHER));
        assertEquals(product == ProductTypeEnum.CLICKHOUSE, supportsEncoding(name, CLICKHOUSE_TEXT_ALGORITHM, Types.VARCHAR));
        assertEquals(product == ProductTypeEnum.CLICKHOUSE, supportsEncoding(name, CLICKHOUSE_TYPED_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.CLICKHOUSE, supportsTextGroupEncoding(name, CLICKHOUSE_TEXT_GROUP_ENCODING, Types.VARCHAR));
        assertFalse(supportsEncoding(name, CLICKHOUSE_TEXT_ALGORITHM, Types.LONGVARCHAR));
        assertFalse(supportsEncoding(name, CLICKHOUSE_TEXT_ALGORITHM, Types.VARBINARY));
        assertFalse(supportsTextGroupEncoding(name, CLICKHOUSE_TEXT_GROUP_ENCODING, Types.CHAR));
        assertFalse(supportsTextGroupEncoding(name, CLICKHOUSE_TEXT_GROUP_ENCODING, Types.LONGVARCHAR));
        assertEquals(product == ProductTypeEnum.TIDB, supportsEncoding(name, TIDB_TEXT_ALGORITHM, Types.VARCHAR));
        assertEquals(product == ProductTypeEnum.TIDB, supportsEncoding(name, TIDB_TEXT_ALGORITHM, Types.LONGVARCHAR));
        assertEquals(product == ProductTypeEnum.TIDB, supportsEncoding(name, TIDB_TYPED_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.TIDB, supportsTextGroupEncoding(name, TIDB_TEXT_GROUP_ENCODING));
        assertEquals(product == ProductTypeEnum.MYSQL, supportsTextGroupEncoding(name, MYSQL_TEXT_GROUP_ENCODING));
        assertEquals(product == ProductTypeEnum.MYSQL, supportsEncoding(name, MYSQL_TEXT_ALGORITHM, Types.VARCHAR));
        assertFalse(supportsEncoding(name, "SHA256_SORTED_TIDB_UTF8_BYTES_V1", Types.CHAR));
        assertFalse(supportsEncoding(name, "SHA256_SORTED_TIDB_UTF8_BYTES_V1", Types.VARBINARY));
        assertFalse(supportsEncoding(name, "SHA256_SORTED_TIDB_TYPED_TUPLE_V1", Types.VARCHAR));
        assertEquals(product == ProductTypeEnum.TIDB, supportsEncoding(name, TIDB_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.OCEANBASE, supportsEncoding(name, OCEANBASE_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.GREENPLUM, supportsEncoding(name, GREENPLUM_COMPOSITE_ALGORITHM, Types.STRUCT));
        for (int type : new int[]{Types.VARCHAR, Types.LONGVARCHAR}) {
            assertEquals(product == ProductTypeEnum.GREENPLUM, supportsEncoding(name, GREENPLUM_TEXT_ALGORITHM, type));
            assertEquals(product == ProductTypeEnum.GREENPLUM, supportsTextGroupEncoding(name, GREENPLUM_TEXT_GROUP_ENCODING, type));
        }
        assertEquals(product == ProductTypeEnum.GREENPLUM, supportsEncoding(name, GREENPLUM_TYPED_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertFalse(supportsEncoding(name, GREENPLUM_TEXT_ALGORITHM, Types.CHAR));
        assertFalse(supportsEncoding(name, GREENPLUM_TEXT_ALGORITHM, Types.OTHER));
        assertFalse(supportsTextGroupEncoding(name, GREENPLUM_TEXT_GROUP_ENCODING, Types.OTHER));
        assertEquals(product == ProductTypeEnum.HIGHGO, supportsEncoding(name, HIGHGO_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.DM, supportsEncoding(name, DM_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.KINGBASE, supportsEncoding(name, KINGBASE_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.OSCAR, supportsEncoding(name, OSCAR_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.UXDB, supportsEncoding(name, UXDB_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.ORACLE, supportsEncoding(name, ORACLE_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.TDENGINE, supportsEncoding(name, TDENGINE_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.STARROCKS, supportsEncoding(name, STARROCKS_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.DORIS, supportsEncoding(name, DORIS_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.MARIADB, supportsEncoding(name, MARIADB_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.CLICKHOUSE, supportsEncoding(name, CLICKHOUSE_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.OPENGAUSS, supportsEncoding(name, OPENGAUSS_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.MYSQL, supportsEncoding(name, MYSQL_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(product == ProductTypeEnum.POSTGRESQL, supportsEncoding(name, COMPOSITE_ALGORITHM, Types.STRUCT));
        assertEquals(Set.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL, ProductTypeEnum.DORIS,
                        ProductTypeEnum.MARIADB, ProductTypeEnum.CLICKHOUSE, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.STARROCKS,
                        ProductTypeEnum.TIDB, ProductTypeEnum.OCEANBASE, ProductTypeEnum.TDENGINE,
                        ProductTypeEnum.GREENPLUM, ProductTypeEnum.HIGHGO, ProductTypeEnum.DM,
                        ProductTypeEnum.KINGBASE, ProductTypeEnum.OSCAR, ProductTypeEnum.UXDB,
                        ProductTypeEnum.ORACLE).contains(product),
                supportsEncoding(name, ALGORITHM, Types.BIGINT));
        assertFalse(supportsEncoding(name, DORIS_COMPOSITE_ALGORITHM, Types.VARCHAR));
        assertFalse(supportsEncoding(name, "UNKNOWN_FUTURE_CODEC", Types.STRUCT));
        assertFalse(supportsEncoding(name, ALGORITHM, Types.DECIMAL));
        // Scanning is implemented; physical source generation/acceptance is deliberately not yet qualified.
        assertFalse(supportsEncoding(name, INFLUXDB_COMPOSITE_ALGORITHM, Types.STRUCT));
        assertFalse(supportsEncoding(name, HIVE_COMPOSITE_ALGORITHM, Types.STRUCT));
    }
}

package cn.iocoder.yudao.framework.dbswitch.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class ProductManifestTest {
    @Test
    void postgresScopedImplementationsDoNotGrantCapabilitiesToCompatibleProducts() {
        var registry = DbSwitchProductRegistry.discover();
        var postgres = registry.products().stream()
                .filter(product -> product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL)
                .findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "MONOTONIC_WATERMARK_V2_NATIVE_OR_EXACT_NUMERIC_KEYS"),
                postgres.capabilities().get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "POSTGRESQL_NULLABLE_ADD_WITH_FRESH_LOCKED_PRECONDITION_ONLY"),
                postgres.capabilities().get(DatabaseOperation.DDL_ALTER));
        for (var product : registry.products()) {
            if (product == postgres) continue;
            if(product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OPENGAUSS) {
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "OPENGAUSS_NATIVE_GENERATION_REPEATABLE_READ_NUMERIC_KEYS"),product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "OPENGAUSS_EXPLICIT_NUMERIC_PRIMARY_KEY_TRANSACTIONAL_MERGE"),product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if(product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER) {
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SQLSERVER16_SNAPSHOT_MANAGED_GENERATION_TYPED_KEYS"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(OperationCapability.Status.DECLARED,
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT).status());
                continue;
            }
            if(product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DM) {
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "DEPLOYMENT_PINNED_NATIVE_GENERATION_SERIALIZABLE_NUMERIC_KEYS"),product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "NON_IDENTITY_NUMERIC_PRIMARY_KEY_TRANSACTIONAL_MERGE"),product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if(product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OCEANBASE) {
                // 两种租户模式各自进行原厂准入，不能借用 MySQL/TiDB 的声明。
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "OCEANBASE_TENANT_PINNED_MODE_SPECIFIC_SNAPSHOT_EXACT_KEYS"),product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "OCEANBASE_TENANT_MODE_SPECIFIC_EXACT_VALUE_UPSERT"),product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if(product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TDENGINE) {
                // Leaf-table timestamp keyset with a pinned instance generation; no snapshot, so it never borrows the PG/MySQL declarations.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "TDENGINE_PINNED_FIXED_UPPER_TIMESTAMP_KEYSET_WITHOUT_SNAPSHOT"),product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "TDENGINE_TIMESTAMP_KEY_OVERWRITE_REQUEST_ACKNOWLEDGED_VERIFIED_TAG_ROUTING"),product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if(product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLITE3
                    ||product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TIDB) {
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TIDB
                            ?"TIDB_SNAPSHOT_TSO_MONOTONIC_WATERMARK_V2_EXACT_KEYS":"DEPLOYMENT_PINNED_FILE_INTEGER_WATERMARK_BINARY_KEYS"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL
                    || product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MARIADB) {
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                                "INNODB_MONOTONIC_WATERMARK_V2_EXACT_KEYS"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                continue;
            }
            if(product.product()==cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3) {
                // Measurement (tagset, time) keyset with a pinned database generation; no snapshot, so it never borrows the PG/MySQL declarations.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "INFLUX_PINNED_FIXED_UPPER_NANOSECOND_TAGSET_KEYSET_WITHOUT_SNAPSHOT"),product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "INFLUX_NATIVE_TAGSET_TIME_FIELD_MERGE_REQUEST_ACKNOWLEDGED"),product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MONGODB) {
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                                "MONGODB_PINNED_FIXED_UPPER_SINGLE_TYPE_WATERMARK_GROUPS_WITHOUT_SNAPSHOT"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                                "MONGODB_SINGLE_ID_TYPED_UPSERT_MAJORITY_JOURNALED"),
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DORIS
                    || product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.STARROCKS) {
                // Unique/primary-key keyset with a pinned database generation; no snapshot, so it never borrows the PG/MySQL declarations.
                boolean doris = product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DORIS;
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        doris ? "DORIS_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED"
                                : "STARROCKS_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        doris ? "DORIS_UNIQUE_KEY_MERGE_ON_WRITE_FULL_ROW_REPLACE_RECEIPT_ACKNOWLEDGED"
                                : "STARROCKS_PRIMARY_KEY_FULL_ROW_REPLACE_RECEIPT_ACKNOWLEDGED"),
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.GREENPLUM) {
                // Scoped to the verified 7.1.0 heap/hash profile; it never borrows the PostgreSQL declaration.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GREENPLUM_HEAP_HASH_PINNED_GENERATION_REPEATABLE_READ_NUMERIC_KEYSET"), product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GREENPLUM_HEAP_HASH_ON_CONFLICT_EXACT_NUMERIC_PRIMARY_KEY_COMMIT_RETURNED"), product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ORACLE) {
                // Scoped to the verified Free 23.26.3 PDB; it does not borrow the DM/openGauss declarations.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "ORACLE23AI_FREE_PINNED_SERIALIZABLE_ROW_SHARE_EXACT_KEYS"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "ORACLE23AI_SINGLE_UNIQUE_KEY_DERIVED_TABLE_MERGE_EXACT_VALUES"),
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ELASTICSEARCH) {
                // One physical index, a mapped sortable watermark and the single _id index upsert key; no snapshot.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                                "ELASTICSEARCH_PINNED_FIXED_UPPER_SINGLE_TYPE_WATERMARK_GROUPS_WITHOUT_SNAPSHOT"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                                "ELASTICSEARCH_SINGLE_ID_INDEX_UPSERT_BULK_ACKNOWLEDGED"),
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.CLICKHOUSE) {
                // Sorting-key keyset with a pinned database generation; mutation read-back upsert with exclusive
                // target ownership. No snapshot, so it never borrows the PG/MySQL/OLAP declarations.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "CLICKHOUSE_PINNED_FIXED_UPPER_SORTING_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "CLICKHOUSE_EXCLUSIVE_TARGET_OWNERSHIP_MUTATION_READBACK_UPSERT"),
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.GBASE8A) {
                // Pinned fixed-upper keyset with a data-level uniqueness proof: GBase 8a keeps PRIMARY KEY as
                // metadata without enforcing uniqueness and has no snapshot isolation, so it never borrows the
                // MySQL declaration. UPSERT uses locked native MERGE with a fully verified temporary source.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GBASE8A_PINNED_FIXED_UPPER_EXACT_KEY_KEYSET_WITHOUT_SNAPSHOT_IDENTITY_RECHECKED"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GBASE8A_86243_EXPRESS_LOCKED_SINGLE_MERGE_EXACT_STAGING"),
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR
                    || product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER2000) {
                boolean oscar = product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR;
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        oscar ? "OSCAR_V8_0_251217_PINNED_GENERATION_LOCKED_NUMERIC_KEYSET"
                                : "SQLSERVER2000_8_00_760_PINNED_GENERATION_LOCKED_NUMERIC_KEYSET"),
                        product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        oscar ? "OSCAR_V8_0_251217_EXCLUSIVE_LOCK_MERGE_ORDERED_PRIMARY_KEY"
                                : "SQLSERVER2000_8_00_760_EXCLUSIVE_LOCK_UPDATE_INSERT_ORDERED_PRIMARY_KEY"),
                        product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.KINGBASE) {
                // Scoped to the verified V008R006C009B0014 oracle/ci profile; it never borrows the PostgreSQL declaration.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "KINGBASE_ORACLE_CI_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET"), product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "KINGBASE_ORACLE_CI_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED"), product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIGHGO) {
                // Scoped to the verified 6.0.4.2 heap profile; it never borrows the PostgreSQL declaration.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "HIGHGO_6_0_4_2_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET"), product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "HIGHGO_6_0_4_2_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED"), product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SYBASE) {
                // Scoped to the verified ASE 16.0 SP02 PL02 profile; it never borrows the SQL Server declaration.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SYBASE_ASE16_SP02_PL02_PINNED_GENERATION_SERIALIZABLE_ROWCOUNT_KEYSET"), product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SYBASE_ASE16_SP02_PL02_MERGE_EXACT_ORDERED_PRIMARY_KEY_COMMIT_RETURNED"), product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DB2) {
                // Scoped to the verified LUW 12.1.5.0 profile; it never borrows the SQL Server declaration.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "DB2_LUW12150_PINNED_GENERATION_REPEATABLE_READ_FETCH_FIRST_KEYSET"), product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "DB2_LUW12150_MERGE_EXACT_ORDERED_PRIMARY_KEY_COMMIT_RETURNED"), product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIVE) {
                // Pinned fixed-upper directory-identity keyset on the verified 4.1.0 profile; no snapshot, so it
                // never borrows the PG/MySQL declarations. UPSERT is scoped to full ACID ORC targets.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "HIVE41_PINNED_FIXED_UPPER_DIRECTORY_IDENTITY_KEYSET_WITHOUT_SNAPSHOT"), product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "HIVE41_FULL_ACID_ORC_EXACT_KEY_INSERT_UPDATE_READBACK_ACKNOWLEDGED"), product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            if (product.product() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.UXDB) {
                // Scoped to the verified UXsinoDB 2.1.0.3 heap profile; it never borrows the PostgreSQL/HighGo declarations.
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "UXSINODB_2_1_0_3_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET"), product.capabilities().get(DatabaseOperation.SYNC_CURSOR));
                assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "UXSINODB_2_1_0_3_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED"), product.capabilities().get(DatabaseOperation.WRITE_UPSERT));
                continue;
            }
            assertEquals(OperationCapability.Status.UNSUPPORTED,
                    product.capabilities().get(DatabaseOperation.SYNC_CURSOR).status(), product.product().name());
        }
    }

    @Test
    void shippedManifestsMatchActualRegisteredOperations() throws Exception {
        var registry = DbSwitchProductRegistry.discover();
        var mapper = new ObjectMapper();
        for (var product : registry.products()) {
            String resource = "META-INF/dbswitch/products/" + product.product().name().toLowerCase(Locale.ROOT) + ".json";
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                var manifest = mapper.readTree(input);
                assertEquals(1, manifest.path("schemaVersion").asInt());
                assertEquals(product.product().name(), manifest.path("product").asText());
                assertTrue(manifest.path("cursorTargets").isArray(), resource + "/cursorTargets");
                var cursorTargets = new java.util.ArrayList<String>();
                manifest.path("cursorTargets").forEach(target -> cursorTargets.add(target.asText()));
                assertEquals(product.cursorTargets().stream().map(Enum::name).sorted().toList(),
                        cursorTargets, resource + "/cursorTargets");
                assertEquals(DatabaseOperation.values().length, manifest.path("operations").size());
                for (var operation : DatabaseOperation.values()) {
                    var declared = manifest.path("operations").path(operation.name());
                    var actual = product.capabilities().get(operation);
                    assertEquals(actual.status().name(), declared.path("status").asText(), resource + "/" + operation);
                    assertEquals(actual.reason(), declared.path("reason").asText(), resource + "/" + operation);
                }
            }
        }
    }
}

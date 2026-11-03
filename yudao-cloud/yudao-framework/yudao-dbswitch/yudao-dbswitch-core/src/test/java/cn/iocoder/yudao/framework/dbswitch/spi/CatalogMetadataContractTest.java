package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalColumn;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.NativeColumnType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CatalogMetadataContractTest {
    private static final CatalogMetadata CATALOG = new CatalogMetadata() {
        public List<Table> tables(ConnectionProbeRequest request) { return List.of(); }
        public Structure describe(ConnectionProbeRequest request, String name) { return new Structure(new Table(name, null, null), List.of()); }
    };

    @Test void sdkCatalogNeverGrantsJdbcMetadataReadOrWrite() {
        var product = DbSwitchProductDescriptor.builder(ProductTypeEnum.ODPS).catalogMetadata(CATALOG).build();
        assertSame(CATALOG, product.catalogMetadata().orElseThrow());
        for (DatabaseOperation operation : DatabaseOperation.values()) {
            assertEquals(OperationCapability.Status.UNSUPPORTED, product.capabilities().get(operation).status());
            assertThrows(UnsupportedOperationException.class, () -> product.require(operation));
        }
        assertThrows(IllegalArgumentException.class, () -> DbSwitchProductDescriptor.builder(ProductTypeEnum.ODPS)
                .catalogMetadata(CATALOG).catalogMetadata(CATALOG));
        var mapper = new LogicalTypeMapper() {
            public ProductTypeEnum product() { return ProductTypeEnum.ODPS; }
            public TypeMappingResult<LogicalType> toLogical(ColumnDescription column) { throw new UnsupportedOperationException(); }
            public TypeMappingResult<NativeColumnType> toNative(LogicalColumn column) { throw new UnsupportedOperationException(); }
        };
        assertSame(CATALOG, product.withTypeMapper(mapper).catalogMetadata().orElseThrow());
    }

    @Test void legacyConstructorAndUnregisteredProductsRetainUnsupportedCatalog() {
        var capabilities = new EnumMap<DatabaseOperation, OperationCapability>(DatabaseOperation.class);
        for (var operation : DatabaseOperation.values()) capabilities.put(operation, OperationCapability.unsupported("fixture"));
        var legacy = new DbSwitchProductDescriptor(ProductTypeEnum.ODPS, null, null, Map.of(), null, capabilities);
        assertTrue(legacy.catalogMetadata().isEmpty());
        assertTrue(DbSwitchProductDescriptor.builder(ProductTypeEnum.MYSQL).build().catalogMetadata().isEmpty());
    }

    @Test void structureCopiesColumnsAndPreservesUnknownConstraints() {
        var columns = new ArrayList<CatalogMetadata.Column>();
        columns.add(new CatalogMetadata.Column("name", "STRING", null, null, null, null, null, false));
        var structure = new CatalogMetadata.Structure(new CatalogMetadata.Table("rows", null, null), columns);
        columns.clear();
        assertEquals(1, structure.columns().size());
        assertNull(structure.columns().getFirst().nullable());
        assertNull(structure.columns().getFirst().length());
        assertThrows(UnsupportedOperationException.class, () -> structure.columns().clear());
    }
}

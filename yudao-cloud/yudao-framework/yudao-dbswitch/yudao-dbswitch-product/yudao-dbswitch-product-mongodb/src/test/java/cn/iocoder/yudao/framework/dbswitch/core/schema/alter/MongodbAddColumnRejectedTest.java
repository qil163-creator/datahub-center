package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMongoDBImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongodbProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Refusal only. No MongoDB connection is opened and no ALTER text is produced. */
class MongodbAddColumnRejectedTest {

    private static final String MANIFEST = "META-INF/dbswitch/products/mongodb.json";

    @Test
    void addColumnIsRejectedBeforeAnySqlIsBuilt() {
        IDatabaseInterface database = new DatabaseMongoDBImpl();
        assertEquals(ProductTypeEnum.MONGODB, database.getDatabaseType());
        ColumnDescription column = new ColumnDescription();
        column.setFieldName("note");
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.MONGODB);

        assertRejected(database, "app", "sample", column);
        assertRejected(database, null, null, null);
        assertRejected(database, "app; DROP COLLECTION sample", "sample", column);
        assertRejected(new DatabaseMongoDBImpl(), "", " ", column);
    }

    @Test
    void providerKeepsAlterUnsupportedAndDoesNotRegisterATypeMapper() throws Exception {
        var products = new MongodbProductProvider().products();
        assertEquals(1, products.size());
        var product = products.iterator().next();
        assertEquals(ProductTypeEnum.MONGODB, product.product());
        assertTrue(product.typeMapper().isEmpty());
        assertEquals(new OperationCapability(OperationCapability.Status.UNSUPPORTED, "SyntaxError"),
                product.capabilities().get(DatabaseOperation.DDL_ALTER));

        try (var input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            assertNotNull(input, MANIFEST);
            var declared = new ObjectMapper().readTree(input).path("operations").path("DDL_ALTER");
            assertEquals("UNSUPPORTED", declared.path("status").asText());
            assertEquals("SyntaxError", declared.path("reason").asText());
        }
    }

    private static void assertRejected(IDatabaseInterface database, String schema, String table,
                                       ColumnDescription column) {
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql(schema, table, column));
        assertEquals("0A000", failure.getSQLState());
        assertEquals("MongoDB 不能执行 ALTER TABLE", failure.getMessage());
    }
}

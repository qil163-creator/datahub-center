package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogRelationIdentityTest {
    @Test void preservesLiteralCatalogSpellingAndRejectsIncompleteObservations() {
        var identity = new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", "41");
        assertEquals("Tenant", identity.schemaName());
        assertEquals("Orders", identity.tableName());
        assertEquals("41", identity.objectId());
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogRelationIdentity(ProductTypeEnum.UNKNOWN, "Tenant", "Orders", "41"));
        for (String invalid : new String[]{null, "", " ", "bad\0name"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CatalogRelationIdentity(ProductTypeEnum.TIDB, invalid, "Orders", "41"));
            assertThrows(IllegalArgumentException.class,
                    () -> new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", invalid, "41"));
            assertThrows(IllegalArgumentException.class,
                    () -> new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", invalid));
        }
    }

    @Test void existingFourBindingProductsHaveNoImplicitNativeCatalogOptIn() {
        Connection unused = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> { throw new AssertionError(method.getName()); });
        for (var product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL,
                ProductTypeEnum.MARIADB, ProductTypeEnum.OPENGAUSS)) {
            IDatabaseInterface database = (IDatabaseInterface) Proxy.newProxyInstance(IDatabaseInterface.class.getClassLoader(),
                    new Class<?>[]{IDatabaseInterface.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("getDatabaseType")) return product;
                        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
                        throw new AssertionError(method.getName());
                    });
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> database.resolveCatalogRelation(unused, "Tenant", "Orders", 5));
        }
    }
}

package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RestrictedRowFilterTest {
    private static final List<String> PHYSICAL = List.of("id", "name", "org_id", "secret");

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "MYSQL", "MARIADB"})
    void filtersDoNotExpandUserProjectionAndCombineWithAnd(ProductTypeEnum product) {
        var result = merge(product, "SELECT * FROM citizen WHERE id=1 OR id=2 ORDER BY id LIMIT 3",
                List.of("org_id=9 OR org_id=10", "secret IS NOT NULL"));
        assertEquals(Set.of("id", "name"), result.selectedColumns());
        assertEquals(Set.copyOf(PHYSICAL), result.referencedColumns());
        assertFalse(result.sql().substring(0, result.sql().indexOf("FROM")).contains("secret"));
        assertTrue(result.sql().contains("AND")); assertTrue(result.sql().contains("LIMIT 3"));
        assertTrue(result.sql().matches("(?s).*WHERE\\s*\\(.*"), result.sql());
        assertEquals(result.sql(), merge(product, "SELECT * FROM citizen WHERE id=1 OR id=2 ORDER BY id LIMIT 3",
                List.of("org_id=9 OR org_id=10", "secret IS NOT NULL")).sql());
        assertThrows(IllegalArgumentException.class, () -> merge(product,
                "SELECT id FROM citizen WHERE secret IS NOT NULL", List.of("org_id=9")));
        assertThrows(IllegalArgumentException.class, () -> merge(product,
                "SELECT secret FROM citizen", List.of("org_id=9")));
        assertThrows(IllegalArgumentException.class, () -> merge(product,
                "SELECT id FROM other.citizen", List.of("org_id=9")));
    }

    @ParameterizedTest @ValueSource(strings = {"org_id=9); DROP TABLE citizen; SELECT id FROM citizen WHERE (id=1",
            "org_id=9) --", "org_id=9 /*comment*/", "org_id IN (SELECT id FROM other)", "pg_sleep(1)=1",
            "missing=1", "other.org_id=9", "org_id=?", "org_id=$1", "org_id=9) ORDER BY id LIMIT 1", ""})
    void invalidFiltersNeverBecomeExecutable(String filter) {
        for (ProductTypeEnum product : new ProductTypeEnum[]{ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB}) {
            assertThrows(IllegalArgumentException.class, () -> merge(product, "SELECT id FROM citizen", List.of(filter)));
        }
    }

    private static RestrictedSelectProjection merge(ProductTypeEnum product, String sql, List<String> filters) {
        return RestrictedSelectGuard.restrictProjectionWithRowFilters(product, sql, "public", "citizen", PHYSICAL,
                Set.of("id", "name"), filters);
    }
}

package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.QueryFilterOperator.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryFilterSqlTest {

    @Test
    void fieldApplicabilityIsFiniteAndUnknownTypesOnlyAllowNullChecks() {
        assertEquals(Set.of(EQ, NE, CONTAINS, NOT_CONTAINS, IS_NULL, IS_NOT_NULL, IS_EMPTY, IS_NOT_EMPTY), applicable(TYPE_STRING));
        for (int type : List.of(TYPE_NUMBER, TYPE_INTEGER, TYPE_BIGNUMBER, TYPE_DATE, TYPE_TIMESTAMP, TYPE_TIME)) {
            assertEquals(Set.of(EQ, NE, GT, GE, LT, LE, IS_NULL, IS_NOT_NULL), applicable(type));
        }
        assertEquals(Set.of(EQ, NE, IS_NULL, IS_NOT_NULL), applicable(TYPE_BOOLEAN));
        for (int type : List.of(TYPE_NONE, TYPE_SERIALIZABLE, TYPE_BINARY, TYPE_INET, -1, 999)) {
            assertEquals(Set.of(IS_NULL, IS_NOT_NULL), applicable(type));
        }
        assertFalse(IS_NULL.requiresValue());
        assertFalse(IS_NOT_NULL.requiresValue());
        assertFalse(IS_EMPTY.requiresValue());
        assertFalse(IS_NOT_EMPTY.requiresValue());
        assertTrue(EQ.requiresValue());
    }

    @Test
    void wireCodesAcceptNoSqlExpressionsAliasesOrWhitespace() {
        for (QueryFilterOperator operator : QueryFilterOperator.values()) {
            assertSame(operator, QueryFilterOperator.fromCode(operator.name()));
        }
        for (String code : List.of("eq", "=", "NE ", " EQ", "EQ OR 1=1", "", "CUSTOM")) {
            assertThrows(IllegalArgumentException.class, () -> QueryFilterOperator.fromCode(code));
        }
        assertThrows(IllegalArgumentException.class, () -> QueryFilterOperator.fromCode(null));
    }

    @Test
    void comparisonGoldensReuseVerifiedIdentifierQuotingForEveryAdvertisedProduct() {
        for (ProductTypeEnum product : ProductTypeEnum.values()) {
            if (!QueryFilterSql.supports(product, EQ)) {
                continue;
            }
            String column = RestrictedSelectGuard.quotePhysicalIdentifier(product, "approved_column");
            Map<QueryFilterOperator, String> operators = Map.of(EQ, "=", NE, "<>", GT, ">", GE, ">=", LT, "<", LE, "<=");
            operators.forEach((operator, sqlOperator) -> {
                var fragment = QueryFilterSql.render(product, "approved_column", TYPE_INTEGER, operator, 4L);
                assertEquals(column + " " + sqlOperator + " ?", fragment.sql(), product + " " + operator);
                assertEquals(List.of(4L), fragment.parameters());
            });
            assertEquals(column + " IS NULL", QueryFilterSql.render(product, "approved_column", TYPE_BINARY, IS_NULL, null).sql());
        }
    }

    @Test
    void literalLikeGoldensEscapeThePatternWithoutInliningValues() {
        String input = "a%_![x]\\' OR 1=1 --";
        for (ProductTypeEnum product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL,
                ProductTypeEnum.MARIADB, ProductTypeEnum.SQLITE3, ProductTypeEnum.SQLSERVER, ProductTypeEnum.SQLSERVER2000)) {
            for (QueryFilterOperator operator : List.of(CONTAINS, NOT_CONTAINS)) {
                var fragment = QueryFilterSql.render(product, "approved_column", TYPE_STRING, operator, input);
                String expected = (product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000)
                        ? "%a!%!_!!![x]\\' OR 1=1 --%" : "%a!%!_!![x]\\' OR 1=1 --%";
                assertEquals(List.of(expected), fragment.parameters(), product.toString());
                assertEquals(RestrictedSelectGuard.quotePhysicalIdentifier(product, "approved_column")
                        + (operator == CONTAINS ? " LIKE ?" : " NOT LIKE ?") + " ESCAPE '!'", fragment.sql());
                assertFalse(fragment.sql().contains(input));
                assertFalse(fragment.toString().contains(input));
            }
        }
    }

    @Test
    void unsupportedProductsAndTypeOperatorPairsFailClosed() {
        for (ProductTypeEnum product : ProductTypeEnum.values()) {
            if (!QueryFilterSql.supports(product, CONTAINS)) {
                assertThrows(IllegalArgumentException.class,
                        () -> QueryFilterSql.render(product, "approved_column", TYPE_STRING, CONTAINS, "value"));
            }
        }
        for (ProductTypeEnum product : List.of(ProductTypeEnum.UNKNOWN, ProductTypeEnum.MONGODB, ProductTypeEnum.ELASTICSEARCH)) {
            assertFalse(QueryFilterSql.supports(product, IS_NULL));
            assertThrows(IllegalArgumentException.class,
                    () -> QueryFilterSql.render(product, "approved_column", TYPE_STRING, IS_NULL, null));
        }
        assertFalse(QueryFilterSql.supports(null, EQ));
        assertFalse(QueryFilterSql.supports(ProductTypeEnum.POSTGRESQL, null));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_BOOLEAN, GT, true));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_INTEGER, CONTAINS, 4L));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_BINARY, EQ, new byte[]{1}));
    }

    @Test
    void sqlNullAndEmptyStringAreSeparateValueFreePredicates() {
        assertEquals("\"approved_column\" IS NULL", render(TYPE_STRING, IS_NULL, null).sql());
        assertEquals(List.of(), render(TYPE_STRING, IS_NULL, null).parameters());
        assertEquals("\"approved_column\" IS NOT NULL", render(TYPE_STRING, IS_NOT_NULL, null).sql());
        assertEquals("\"approved_column\" = ?", render(TYPE_STRING, IS_EMPTY, null).sql());
        assertEquals(List.of(""), render(TYPE_STRING, IS_EMPTY, null).parameters());
        assertEquals("\"approved_column\" <> ?", render(TYPE_STRING, IS_NOT_EMPTY, null).sql());
        assertEquals(List.of(""), render(TYPE_STRING, IS_NOT_EMPTY, null).parameters());
        assertEquals("1 = 0", QueryFilterSql.render(ProductTypeEnum.ORACLE, "approved_column", TYPE_STRING, IS_EMPTY, null).sql());
        assertEquals("\"approved_column\" IS NOT NULL",
                QueryFilterSql.render(ProductTypeEnum.ORACLE, "approved_column", TYPE_STRING, IS_NOT_EMPTY, null).sql());
        assertFalse(QueryFilterSql.supports(ProductTypeEnum.DM, IS_EMPTY));
        assertFalse(QueryFilterSql.supports(ProductTypeEnum.DM, IS_NOT_EMPTY));
    }

    @Test
    void unconvertedMissingAndNonFiniteValuesAreRejectedBeforeJdbc() {
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_STRING, EQ, null));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_STRING, EQ, 42L));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_INTEGER, EQ, "42"));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_INTEGER, EQ, 42.0));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_BIGNUMBER, EQ, 1.01));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_NUMBER, EQ, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_NUMBER, EQ, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_DATE, EQ, "2026-10-12"));
        assertThrows(IllegalArgumentException.class, () -> render(TYPE_BOOLEAN, EQ, "true"));
    }

    @Test
    void parametersKeepExactTypedValuesAndTheListIsImmutable() {
        BigDecimal decimal = new BigDecimal("9007199254740993.12345678901234567890");
        var fragment = render(TYPE_BIGNUMBER, GE, decimal);
        assertSame(decimal, fragment.parameters().getFirst());
        assertEquals(List.of(9007199254740993L), render(TYPE_INTEGER, EQ, 9007199254740993L).parameters());
        assertThrows(UnsupportedOperationException.class, () -> fragment.parameters().add("other"));
        assertFalse(fragment.toString().contains(decimal.toPlainString()));
    }

    @Test
    void approvedPhysicalColumnNamesCannotBreakOutOfIdentifierQuotes() {
        assertEquals("\"name\"\" OR 1=1 --\" = ?",
                QueryFilterSql.render(ProductTypeEnum.POSTGRESQL, "name\" OR 1=1 --", TYPE_STRING, EQ, "test").sql());
        assertEquals("[name]] OR 1=1 --] = ?",
                QueryFilterSql.render(ProductTypeEnum.SQLSERVER, "name] OR 1=1 --", TYPE_STRING, EQ, "test").sql());
        assertThrows(IllegalArgumentException.class,
                () -> QueryFilterSql.render(ProductTypeEnum.POSTGRESQL, "", TYPE_STRING, EQ, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> QueryFilterSql.render(ProductTypeEnum.POSTGRESQL, "name\0", TYPE_STRING, EQ, "test"));
    }

    private static Set<QueryFilterOperator> applicable(int type) {
        return Arrays.stream(QueryFilterOperator.values()).filter(operator -> operator.supports(type)).collect(Collectors.toSet());
    }

    private static QueryFilterSql.Fragment render(int fieldType, QueryFilterOperator operator, Object value) {
        return QueryFilterSql.render(ProductTypeEnum.POSTGRESQL, "approved_column", fieldType, operator, value);
    }
}

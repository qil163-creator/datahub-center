package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

import static cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.QueryFilterOperator.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against the explicitly configured private localhost fixture, never a platform database. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/filter_test")
@Timeout(30)
class QueryFilterSqlPostgreSqlTest {
    private static final long LARGE_ID = 9007199254740993L;
    private static final String LITERAL = "a%_![x]\\' OR 1=1 --";
    private static final BigDecimal EXACT_AMOUNT = new BigDecimal("9007199254740993.123456789012");
    private Connection connection;

    @BeforeEach
    void setup() throws Exception {
        connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), "codex_test", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE filter_rows(id bigint PRIMARY KEY, label text, amount numeric(40,12), "
                    + "day date, stamp timestamp, clock time, active boolean)");
            statement.execute("INSERT INTO filter_rows VALUES(1,NULL,NULL,NULL,NULL,NULL,NULL),"
                    + "(2,'',10,'2026-10-11','2026-10-11 08:00:00','08:00:00',false),"
                    + "(4,'plain',9007199254740993.123456789013,'2026-10-13','2026-10-13 10:00:00','10:00:00',true)");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO filter_rows VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, LARGE_ID);
            statement.setString(2, LITERAL);
            statement.setBigDecimal(3, EXACT_AMOUNT);
            statement.setDate(4, Date.valueOf("2026-10-12"));
            statement.setTimestamp(5, Timestamp.valueOf("2026-10-12 09:00:00.123456"));
            statement.setTime(6, Time.valueOf("09:00:00"));
            statement.setBoolean(7, true);
            statement.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        if (connection != null) {
            connection.close(); // Temporary fixture is dropped with its owning connection.
        }
    }

    @ParameterizedTest
    @EnumSource(value = QueryFilterOperator.class, names = {"EQ", "NE", "GT", "GE", "LT", "LE"})
    void comparisonsRetainExactDecimalsAndExcludeSqlNull(QueryFilterOperator operator) throws Exception {
        Set<Long> expected = switch (operator) {
            case EQ -> Set.of(LARGE_ID);
            case NE -> Set.of(2L, 4L);
            case GT -> Set.of(4L);
            case GE -> Set.of(LARGE_ID, 4L);
            case LT -> Set.of(2L);
            case LE -> Set.of(2L, LARGE_ID);
            default -> throw new AssertionError(operator);
        };
        assertEquals(expected, ids("amount", TYPE_BIGNUMBER, operator, EXACT_AMOUNT));
    }

    @Test
    void largeIntegerIdDoesNotRoundThroughDouble() throws Exception {
        assertEquals(Set.of(LARGE_ID), ids("id", TYPE_INTEGER, EQ, LARGE_ID));
        assertEquals(Set.of(), ids("id", TYPE_INTEGER, EQ, LARGE_ID - 1));
    }

    @Test
    void literalContainsEscapesWildcardEscapeCharacterAndSqlText() throws Exception {
        assertEquals(Set.of(LARGE_ID), ids("label", TYPE_STRING, CONTAINS, LITERAL));
        for (String literal : new String[]{"%", "_", "!", "[x]", "\\", "' OR 1=1 --"}) {
            assertEquals(Set.of(LARGE_ID), ids("label", TYPE_STRING, CONTAINS, literal), literal);
        }
        assertEquals(Set.of(2L, 4L), ids("label", TYPE_STRING, NOT_CONTAINS, "%"));
        assertEquals(Set.of(2L, 4L, LARGE_ID), ids("label", TYPE_STRING, CONTAINS, ""));
    }

    @Test
    void sqlNullAndEmptyStringRemainDifferentAndRequireNoInput() throws Exception {
        assertEquals(Set.of(1L), ids("label", TYPE_STRING, IS_NULL, null));
        assertEquals(Set.of(2L, 4L, LARGE_ID), ids("label", TYPE_STRING, IS_NOT_NULL, null));
        assertEquals(Set.of(2L), ids("label", TYPE_STRING, IS_EMPTY, null));
        assertEquals(Set.of(4L, LARGE_ID), ids("label", TYPE_STRING, IS_NOT_EMPTY, null));
        assertEquals(Set.of(2L), ids("label", TYPE_STRING, EQ, ""));
        assertEquals(Set.of(4L, LARGE_ID), ids("label", TYPE_STRING, NE, ""));
    }

    @Test
    void booleansUseTypedParametersAndNullChecks() throws Exception {
        assertEquals(Set.of(4L, LARGE_ID), ids("active", TYPE_BOOLEAN, EQ, true));
        assertEquals(Set.of(2L), ids("active", TYPE_BOOLEAN, NE, true));
        assertEquals(Set.of(1L), ids("active", TYPE_BOOLEAN, IS_NULL, null));
    }

    @Test
    void dateTimestampAndTimeComparisonsUseDatabaseTypes() throws Exception {
        assertEquals(Set.of(4L, LARGE_ID), ids("day", TYPE_DATE, GE, Date.valueOf("2026-10-12")));
        assertEquals(Set.of(LARGE_ID), ids("stamp", TYPE_TIMESTAMP, EQ, Timestamp.valueOf("2026-10-12 09:00:00.123456")));
        assertEquals(Set.of(4L), ids("stamp", TYPE_TIMESTAMP, GT, Timestamp.valueOf("2026-10-12 09:00:00.123456")));
        assertEquals(Set.of(2L, LARGE_ID), ids("clock", TYPE_TIME, LE, Time.valueOf("09:00:00")));
    }

    @Test
    void escapedPhysicalIdentifierIsStillOneColumn() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE filter_rows ADD COLUMN \"name\"\" OR 1=1 --\" text");
            statement.execute("UPDATE filter_rows SET \"name\"\" OR 1=1 --\" = 'match' WHERE id=2");
        }
        assertEquals(Set.of(2L), ids("name\" OR 1=1 --", TYPE_STRING, EQ, "match"));
    }

    private Set<Long> ids(String column, int type, QueryFilterOperator operator, Object value) throws Exception {
        var fragment = QueryFilterSql.render(ProductTypeEnum.POSTGRESQL, column, type, operator, value);
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM filter_rows WHERE " + fragment.sql())) {
            for (int index = 0; index < fragment.parameters().size(); index++) {
                statement.setObject(index + 1, fragment.parameters().get(index));
            }
            statement.setQueryTimeout(5);
            Set<Long> ids = new HashSet<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
            return ids;
        }
    }
}

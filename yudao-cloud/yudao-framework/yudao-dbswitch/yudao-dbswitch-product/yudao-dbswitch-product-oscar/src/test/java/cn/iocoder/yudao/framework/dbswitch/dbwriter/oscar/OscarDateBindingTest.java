package cn.iocoder.yudao.framework.dbswitch.dbwriter.oscar;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OscarDateBindingTest {
    @Test
    void adaptsDateOnlyValuesInInputFieldOrderWithoutChangingCallerRows() {
        var source = mock(DataSource.class);
        var writer = new CapturingWriter(source, Map.of("D", Types.DATE, "S", Types.VARCHAR));
        var date = LocalDate.of(2024, 2, 29);
        Object[] first = {"untouched", date};
        Object[] second = {"text", "2026-10-08"};
        Object[] third = {"null", null};
        var fields = List.of("S", "D");
        assertEquals(3, writer.write(fields, List.of(first, second, third)));
        assertEquals(java.sql.Date.valueOf(date), writer.rows.get(0)[1]);
        assertEquals(java.sql.Date.valueOf("2026-10-08"), writer.rows.get(1)[1]);
        assertNull(writer.rows.get(2)[1]);
        assertEquals("untouched", writer.rows.getFirst()[0]);
        assertSame(date, first[1]);
        assertEquals("2026-10-08", second[1]);
        assertNotSame(first, writer.rows.getFirst());
        assertArrayEquals(new int[]{Types.VARCHAR, Types.DATE}, writer.types);
        verifyNoInteractions(source);
    }

    @Test
    void preservesExistingTimeBearingValuesAndNonDateTargets() {
        var writer = new CapturingWriter(mock(DataSource.class), Map.of("D", Types.DATE, "T", Types.TIMESTAMP));
        var timestamp = Timestamp.valueOf("2026-10-08 12:34:56.123456789");
        var localDateTime = LocalDateTime.of(2026, 10, 8, 12, 34, 56);
        var date = LocalDate.of(2026, 10, 8);
        var timeText = "2026-10-08 12:34:56";
        writer.write(List.of("D", "T"), List.<Object[]>of(new Object[]{timestamp, date},
                new Object[]{localDateTime, null}, new Object[]{timeText, null}));
        assertSame(timestamp, writer.rows.get(0)[0]);
        assertEquals(123456789, timestamp.getNanos());
        assertSame(date, writer.rows.get(0)[1]);
        assertSame(localDateTime, writer.rows.get(1)[0]);
        assertSame(timeText, writer.rows.get(2)[0]);
    }

    @Test
    void invalidDateRejectsWholeBatchBeforeExecutionAndDoesNotLeakInput() {
        var source = mock(DataSource.class);
        var writer = new CapturingWriter(source, Map.of("D", Types.DATE));
        Object[] valid = {LocalDate.of(2026, 10, 8)};
        var failure = assertThrows(BatchRejectedException.class, () -> writer.write(List.of("D"),
                List.of(valid, new Object[]{"2026-02-30"})));
        assertNull(writer.rows);
        assertFalse(failure.getMessage().contains("2026-02-30"));
        assertNull(failure.getCause());
        assertInstanceOf(LocalDate.class, valid[0]);
        verifyNoInteractions(source);
    }

    @Test
    void rejectsUnverifiedCalendarYearsBeforeExecution() {
        for (var date : List.of(LocalDate.of(0, 1, 1), LocalDate.of(10000, 1, 1))) {
            var writer = new CapturingWriter(mock(DataSource.class), Map.of("D", Types.DATE));
            assertThrows(BatchRejectedException.class,
                    () -> writer.write(List.of("D"), List.<Object[]>of(new Object[]{date})));
            assertNull(writer.rows);
        }
    }

    private static final class CapturingWriter extends OscarWriterImpl {
        private List<Object[]> rows;
        private int[] types;

        CapturingWriter(DataSource source, Map<String, Integer> types) {
            super(source);
            this.schemaName = "TEST_SCHEMA";
            this.tableName = "DATE_ROWS";
            this.columnType = types;
        }

        @Override
        protected void batchWrite(List<String> fields, List<Object[]> rows, String sql, int[] types) {
            this.rows = rows;
            this.types = types;
        }
    }
}

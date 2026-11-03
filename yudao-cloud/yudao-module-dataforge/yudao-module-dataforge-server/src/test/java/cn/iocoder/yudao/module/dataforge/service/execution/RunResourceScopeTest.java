package cn.iocoder.yudao.module.dataforge.service.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunResourceScopeTest {

    @Test
    void accessUsesFrozenSourceAndTargetAndDeduplicates() {
        assertEquals(List.of(10L, 20L), RunResourceScope.decode(RunResourceScope.access(
                "{\"sourceDatabaseId\":20,\"targetDatabaseId\":10}")));
        assertEquals(List.of(20L), RunResourceScope.decode(RunResourceScope.access(
                "{\"sourceDatabaseId\":20,\"targetDatabaseId\":20}")));
        assertEquals(List.of(9007199254740993L), RunResourceScope.decode("[9007199254740993]"));
        assertEquals(List.of(), RunResourceScope.decode(RunResourceScope.access("{}")));
    }

    @Test
    void etlUsesTheSameNodeAndDefaultParameterResolutionAsExecution() {
        String snapshot = "{\"parameters\":[{\"name\":\"db\",\"defaultValue\":\"12\"}],"
                + "\"nodes\":[{\"id\":\"read\",\"properties\":{\"toolCode\":\"read-db\","
                + "\"nodeJson\":{\"databaseId\":\"${params.db}\"}}},{\"id\":\"write\",\"properties\":{"
                + "\"toolCode\":\"insert-db\",\"nodeJson\":{\"databaseId\":34}}}],\"edges\":[]}";
        assertEquals(List.of(12L, 34L), RunResourceScope.decode(RunResourceScope.etl(snapshot)));
    }

    @Test
    void invalidOrUnknownScopeMustNotBecomeAnEmptyGrant() {
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.decode(null));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.decode("[1,1]"));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.decode("[\"1\"]"));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.decode("[9007199254740993.1]"));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.access("{\"targetDatabaseId\":0}"));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.access("{\"engineType\":1,\"targetDatabaseId\":20}"));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.etl("{\"nodes\":[{\"id\":\"read\",\"properties\":{\"toolCode\":\"read-db\",\"nodeJson\":{}}}]}"));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.etl("{\"nodes\":[{\"id\":\"read\",\"properties\":{\"toolCode\":\"read-db\",\"nodeJson\":{\"databaseId\":\"${sys.epochMillis}\"}}}]}"));
        assertThrows(IllegalArgumentException.class, () -> RunResourceScope.etl("not-json"));
    }
}

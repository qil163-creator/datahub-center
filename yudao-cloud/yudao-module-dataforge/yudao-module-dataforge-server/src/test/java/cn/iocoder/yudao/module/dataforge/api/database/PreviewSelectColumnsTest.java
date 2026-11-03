package cn.iocoder.yudao.module.dataforge.api.database;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviewSelectColumnsTest {

    @Test
    void resolveIgnoresCaseAndDropsDuplicates() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("id", "Id");
        actual.put("name", "NAME");
        assertEquals(List.of("Id", "NAME"), PreviewSelectColumns.resolve(List.of("ID", "name", "Name"), actual));
        assertNull(PreviewSelectColumns.firstMissing(List.of("id"), actual));
        assertEquals("secret", PreviewSelectColumns.firstMissing(List.of("id", "secret"), actual));
    }

    @Test
    void emptyRequestMeansSelectAll() {
        assertEquals(List.of(), PreviewSelectColumns.resolve(List.of(), Map.of("id", "id")));
        assertNull(PreviewSelectColumns.firstMissing(null, Map.of("id", "id")));
    }

}

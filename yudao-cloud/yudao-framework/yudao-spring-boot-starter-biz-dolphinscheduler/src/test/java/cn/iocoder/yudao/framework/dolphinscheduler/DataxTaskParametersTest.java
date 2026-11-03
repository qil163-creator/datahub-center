package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.iocoder.yudao.framework.dolphinscheduler.model.DataxTaskParameters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataxTaskParametersTest {
    @Test void hooksRequireVersionedAtomicWriterAndKeepLegacyEmptyHookContract() {
        var pre = java.util.List.of("DELETE FROM public.target WHERE id < 0");
        var post = java.util.List.of("UPDATE public.audit SET count=count+1");
        var params = DataxTaskParameters.upsertWithHooks(options(1024, 2048, 0), "public", "target", java.util.List.of("id"), pre, post);
        assertEquals("POSTGRESQL_UPSERT_HOOKS_V2", params.get("writeContract"));
        assertEquals(pre, params.get("preStatements")); assertEquals(post, params.get("postStatements"));
        assertEquals("POSTGRESQL_UPSERT_V1", DataxTaskParameters.upsertWithHooks(options(1024, 2048, 0), "public", "target", java.util.List.of("id"), java.util.List.of(), java.util.List.of()).get("writeContract"));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.upsertWithHooks(options(1024, 2048, 0), "public", "target", java.util.List.of("id"), java.util.List.of("UPDATE public.t SET a=${unsafe}"), post));
    }
    @Test void upsertIsExplicitAndContainsOnlyManagedIdentityAndKeys() {
        var params = DataxTaskParameters.upsert(options(1024, 2048, 0), "public", "target", java.util.List.of("id"));
        assertEquals("POSTGRESQL_UPSERT_V1", params.get("writeContract"));
        assertEquals(java.util.List.of("id"), params.get("upsertKeys"));
        assertEquals("target", params.get("upsertTable"));
        assertFalse(params.toString().contains("password"));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.upsert(options(1024, 2048, 0), "public", "target", java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.upsert(options(1024, 2048, 0), "public", "target", java.util.List.of("id", "id")));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.upsert(options(1024, 2048, 0), "public", "target", java.util.List.of("${key}")));
    }
    @Test void mapsMegabytesToDsIntegerGibAndUsesResourceIds() {
        var params = DataxTaskParameters.build(options(1024, 2048, 0));
        assertEquals(1, params.get("xms")); assertEquals(2, params.get("xmx"));
        assertEquals(17, params.get("dataSource")); assertEquals(19, params.get("dataTarget"));
        assertEquals(0, params.get("customConfig")); assertFalse(params.containsKey("json"));
        assertFalse(params.toString().contains("password"));
    }

    @Test void doesNotSilentlyRoundMemoryOrIgnoreDirtyDataLimit() {
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.build(options(512, 2048, 0)));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.build(options(1024, 2049, 0)));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.build(options(1024, 2048, 1)));
    }

    @Test void rejectsUnsupportedProductsAndSpeedOverflow() {
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.build(new DataxTaskParameters.Options(
                17, "OTHER", 19, "POSTGRESQL", "select id from t", "target", 1024, 2048, 1, 0, 0, 0, 1000)));
        assertThrows(IllegalArgumentException.class, () -> DataxTaskParameters.build(new DataxTaskParameters.Options(
                17, "POSTGRESQL", 19, "POSTGRESQL", "select id from t", "target", 1024, 2048, 1,
                (long) Integer.MAX_VALUE + 1, 0, 0, 1000)));
    }

    private static DataxTaskParameters.Options options(int initial, int maximum, int dirty) {
        return new DataxTaskParameters.Options(17, "POSTGRESQL", 19, "POSTGRESQL", "select id from public.source",
                "public.target", initial, maximum, 2, 0, 0, dirty, 1000);
    }
}

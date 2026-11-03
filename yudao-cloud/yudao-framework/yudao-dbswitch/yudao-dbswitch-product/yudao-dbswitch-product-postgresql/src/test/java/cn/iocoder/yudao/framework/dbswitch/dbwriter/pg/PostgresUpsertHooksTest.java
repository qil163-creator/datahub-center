package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class PostgresUpsertHooksTest {
    @Test void parsesQualifiedDmlWithCommentsAndLiteralSemicolons() {
        var result=PostgresUpsertHooks.parse("-- 钩子\nUPDATE \"test-schema\".audit SET n=n+1 WHERE phase='pre;one'; /* 外层 /* 内层 */ */ INSERT INTO \"test-schema\".audit (phase,n) VALUES ('post',2); DELETE FROM \"test-schema\".audit WHERE n<0", "test-schema");
        assertEquals(3,result.size());
        assertTrue(result.get(0).contains("'pre;one'"));
        assertTrue(PostgresUpsertHooks.parse("/* 空脚本 */", "public").isEmpty());
    }

    @Test void rejectsTransactionControlSubqueriesCallsAndRuntimeTables() {
        for(String sql:Arrays.asList("COMMIT", "BEGIN", "TRUNCATE public.audit", "UPDATE public.audit SET n=(SELECT 1)",
                "UPDATE public.audit SET n=(TABLE public.audit)", "UPDATE public.audit SET n=nextval('s')", "UPDATE public.audit SET n=\"pg_catalog\".abs(n)",
                "UPDATE public.audit SET n=1 FROM public.other", "DELETE FROM public.audit USING public.other", "INSERT INTO public.audit (n) VALUES (audit.conflict())", "UPDATE public.audit SET n=audit.evil '1'", "UPDATE public.audit SET n=evil '1'", "UPDATE public.audit SET n=(VALUES (1))", "UPDATE public.audit SET n=1 -- hidden\r; SELECT pg_notify('a','b')", "UPDATE public._datahub_upsert_runs SET state='COMPLETED'",
                "UPDATE public.\"_DATAHUB_upsert_stage_x\" SET n=1", "INSERT INTO public.audit DEFAULT VALUES", "UPDATE other.audit SET n=1"))
            assertThrows(IllegalArgumentException.class, () -> PostgresUpsertHooks.parse(sql,"public"),sql);
    }

    @Test void preservesKeywordsInsideStringsAndQuotedColumnNames() {
        assertEquals(1,PostgresUpsertHooks.parse("UPDATE public.audit SET \"SELECT\"='COMMIT;TABLE',phase='pre''one' WHERE n=0","public").size());
        assertEquals(1,PostgresUpsertHooks.parse("INSERT INTO public.audit (phase,n) VALUES ('post',2) ON CONFLICT (phase) DO UPDATE SET n=excluded.n","public").size());
    }

    @Test void rejectsAmbiguousOrUnboundedScripts() {
        for(String sql:Arrays.asList("UPDATE audit SET n=1", "UPDATE public.audit SET n=1::int", "UPDATE public.audit SET phase=$$x$$", "UPDATE public.audit SET phase='a\\b'", "UPDATE public.audit SET phase='unfinished", "/* unfinished"))
            assertThrows(IllegalArgumentException.class, () -> PostgresUpsertHooks.parse(sql,"public"),sql);
        assertThrows(IllegalArgumentException.class, () -> PostgresUpsertHooks.validate(Arrays.asList("UPDATE public.audit SET n=1; DELETE FROM public.audit"),"public"));
        assertThrows(IllegalArgumentException.class, () -> PostgresUpsertHooks.parse("UPDATE public.audit SET phase='"+"a".repeat(16000)+"'","public"));
    }

    @Test void retryRetainsLogicalKeyAndGetsFreshStagingTable() {
        var first=publication("project/workflow/instance/task");
        var retry=publication("project/workflow/instance/task");
        assertEquals(first.getExecutionKey(),retry.getExecutionKey());
        assertNotEquals(first.getStageTable(),retry.getStageTable());
        assertNotEquals(first.getExecutionKey(),publication("project/workflow/other/task").getExecutionKey());
    }

    private PostgresUpsertPublication publication(String identity) {
        return new PostgresUpsertPublication(identity,"source","public","target",Arrays.asList("id","n"),Collections.singletonList("id"),Collections.singletonList("UPDATE public.audit SET n=n+1"),Collections.emptyList(),null);
    }
}

package cn.iocoder.yudao.framework.dbswitch.product.dm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DmNativeChecksTest {
    @ParameterizedTest @ValueSource(strings={"A>1","A<>-1","A!=1","A<=1.25","A>=-0.000000000000000001","A BETWEEN -1 AND 2","A NOT BETWEEN 0 AND 9","A IN(1,2,NULL)","A NOT IN(1,2)","A IS NULL","A IS NOT NULL","NOT(A=1)","A>1 AND A<5 OR A IS NULL"})
    void supportedPredicatesRoundTripThroughCanonicalRendering(String text) {
        var first=new DmNativeChecks.Entry("C",text,Set.of("A"),true);
        assertEquals(first,new DmNativeChecks.Entry("C",first.expression(),Set.of("A"),true));
    }
    @Test void doubledQuotesBackslashesAndSemicolonsAreLiteralTokens() {
        String field="a\"'\\;()";String sql="\""+field.replace("\"","\"\"")+"\" IN ('a''b','x\\y',';--/*')";
        var entry=new DmNativeChecks.Entry("C",sql,Set.of(field),true);
        assertTrue(entry.expression().contains("\"a\"\"'\\;()\""));assertTrue(entry.expression().contains("'x\\y'"));
        assertEquals(entry,new DmNativeChecks.Entry("C",entry.expression(),Set.of(field),true));
        var mapped=entry.mapped(Map.of(field,"new\"column"));assertEquals(Set.of("new\"column"),mapped.columns());assertTrue(mapped.expression().contains("\"new\"\"column\""));
    }
    @ParameterizedTest @ValueSource(strings={"A>1;DROP TABLE T","A>1--comment","A>1/*comment*/","A=ABS(1)","A=(SELECT 1 FROM DUAL)","EXISTS(SELECT 1 FROM DUAL)","A=?","A=:value","A=@value","T.A>1","A>1e3","A+1>2","A IS TRUE","A IN()","A>'unterminated","A > 1 garbage","A > 1#comment"})
    void unverifiedGrammarCannotBecomeExecutableDdl(String text) {assertThrows(RuntimeException.class,()->new DmNativeChecks.Entry("C",text,Set.of("A"),true));}
    @Test void referencedColumnSetMustExactlyMatchCatalogAndSurviveMapping() {
        assertThrows(IllegalArgumentException.class,()->new DmNativeChecks.Entry("C","A>B",Set.of("A"),true));
        assertThrows(IllegalArgumentException.class,()->new DmNativeChecks.Entry("C","A>1",Set.of("A","B"),true));
        var entry=new DmNativeChecks.Entry("C","A>B",Set.of("A","B"),true);
        assertThrows(IllegalArgumentException.class,()->entry.mapped(Map.of("A","A")));
    }
    @Test void nestingNodeAndByteBudgetsApplyBeforeRecursiveParser() {
        assertThrows(IllegalArgumentException.class,()->new DmNativeChecks.Entry("C","(".repeat(100)+"A>1"+")".repeat(100),Set.of("A"),true));
        assertThrows(IllegalArgumentException.class,()->new DmNativeChecks.Entry("C","NOT ".repeat(600)+"(A>1)",Set.of("A"),true));
        assertThrows(IllegalArgumentException.class,()->new DmNativeChecks.Entry("C","A='"+"界".repeat(6000)+"'",Set.of("A"),true));
    }
    @Test void sourceNamesCannotCollideWithInternalQuotedTokenPlaceholders() {
        var entry=new DmNativeChecks.Entry("C","DBSW_CHECK_TOKEN_0='literal'",Set.of("DBSW_CHECK_TOKEN_0"),true);
        assertEquals(Set.of("DBSW_CHECK_TOKEN_0"),entry.columns());assertTrue(entry.expression().contains("'literal'"));
    }
}

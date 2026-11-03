package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.core.util.StrUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EtlTokenSplitTest {
    @Test void literalRegexZeroWidthAndMissingTokensFollowJavaContract() {
        for (String source : new String[]{"", ",", "a,", ",a", "a,,b"})
            assertArrayEquals(StrUtil.splitToArray(source, ","), EtlTokenSplit.compile(",", false, "").split(source));
        assertArrayEquals("abc".split("(?=b)", -1), EtlTokenSplit.compile("(?=b)", true, "").split("abc"));
        assertArrayEquals(new String[]{"a", "b", ""}, EtlTokenSplit.compile("[,;]", true, "").split("a,b;"));
        assertNull(EtlTokenSplit.token("a", ",", "false", "", "1", "NONE"));
        assertEquals("", EtlTokenSplit.token(null, ",", "false", "", "0", "NONE"));
    }
    @Test void wrappersAreRemovedBeforeTrimAndUnicodeBlankRulesMatchTheExistingExecutor() {
        var plan = EtlTokenSplit.compile(",", false, "\"");
        assertEquals("a", plan.clean("\" a \"", "BOTH"));
        assertEquals("\" a \"", plan.clean(" \" a \" ", "BOTH"));
        var bare = EtlTokenSplit.compile(",", false, "");
        for (int codePoint = 0; codePoint <= Character.MAX_VALUE; codePoint++) {
            String text = (char) codePoint + "x" + (char) codePoint;
            assertEquals(StrUtil.trimStart(text), bare.clean(text, "LEFT"), Integer.toString(codePoint));
            assertEquals(StrUtil.trimEnd(text), bare.clean(text, "RIGHT"), Integer.toString(codePoint));
        }
    }
}

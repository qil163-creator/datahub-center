package cn.iocoder.yudao.framework.dbswitch.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DbswitchStrUtils} 的单元测试（重点验证 glob 转正则）
 */
class DbswitchStrUtilsTest {

	@Test
	void globToRegexShouldMatchStarWildcard() {
		String regex = DbswitchStrUtils.globToRegex("user_*");
		assertTrue(Pattern.matches(regex, "user_info"));
		assertTrue(Pattern.matches(regex, "user_"));
		assertFalse(Pattern.matches(regex, "order_info"));
	}

	@Test
	void globToRegexShouldMatchSingleStar() {
		// 单独的 "*" 是非法 Java 正则，但必须是合法 glob（匹配一切）
		String regex = DbswitchStrUtils.globToRegex("*");
		assertTrue(Pattern.matches(regex, "anything"));
		assertTrue(Pattern.matches(regex, ""));
	}

	@Test
	void globToRegexShouldMatchQuestionMark() {
		String regex = DbswitchStrUtils.globToRegex("t_?_log");
		assertTrue(Pattern.matches(regex, "t_a_log"));
		assertFalse(Pattern.matches(regex, "t_ab_log"));
	}

	@Test
	void globToRegexShouldEscapeRegexMetaChars() {
		// 点号等正则元字符应按字面量处理
		String regex = DbswitchStrUtils.globToRegex("a.b*");
		assertTrue(Pattern.matches(regex, "a.b_table"));
		assertFalse(Pattern.matches(regex, "aXb_table"));
	}

	@Test
	void stringToListShouldSplitByComma() {
		List<String> list = DbswitchStrUtils.stringToList("a,b,c");
		assertEquals(3, list.size());
		assertEquals("a", list.get(0));
		assertTrue(DbswitchStrUtils.stringToList(null).isEmpty());
		assertTrue(DbswitchStrUtils.stringToList("").isEmpty());
	}
}

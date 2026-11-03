package cn.iocoder.yudao.framework.dbswitch.common.type;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProductTypeEnum} 辅助判断方法的单元测试
 */
class ProductTypeEnumTest {

	@Test
	void sqliteResourceUrlShouldBeRejectedAsTarget() {
		assertTrue(ProductTypeEnum.SQLITE3.isUnsupportedTargetSqlite("jdbc:sqlite::resource:test.db"));
		assertTrue(ProductTypeEnum.SQLITE3.isUnsupportedTargetSqlite("jdbc:sqlite::RESOURCE:test.db"));
		assertFalse(ProductTypeEnum.SQLITE3.isUnsupportedTargetSqlite("jdbc:sqlite:/tmp/test.db"));
		assertFalse(ProductTypeEnum.SQLITE3.isUnsupportedTargetSqlite(null));
		// 非 SQLite 类型不受影响
		assertFalse(ProductTypeEnum.MYSQL.isUnsupportedTargetSqlite("jdbc:sqlite::resource:test.db"));
	}

	@Test
	void sqliteUrlTemplateShouldBeWritableFileMode() {
		assertFalse(ProductTypeEnum.SQLITE3.getUrl().contains(":resource:"));
	}

	@Test
	void greenplumDriverShouldBePostgresDriver() {
		assertTrue("org.postgresql.Driver".equals(ProductTypeEnum.GREENPLUM.getDriveClassName()));
	}
}

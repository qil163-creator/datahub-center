package cn.iocoder.yudao.framework.dbswitch.common.type;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SyncOptionEnum} 操作组合开关的单元测试
 */
class SyncOptionEnumTest {

	@Test
	void defaultOptionShouldAllowAllOperations() {
		SyncOptionEnum option = SyncOptionEnum.INSERT_UPDATE_DELETE;
		assertTrue(option.callInsert());
		assertTrue(option.callUpdate());
		assertTrue(option.callDelete());
	}

	@Test
	void insertUpdateShouldNotDelete() {
		SyncOptionEnum option = SyncOptionEnum.INSERT_UPDATE;
		assertTrue(option.callInsert());
		assertTrue(option.callUpdate());
		assertFalse(option.callDelete());
	}

	@Test
	void singleOperationOptionsShouldOnlyAllowThemselves() {
		assertTrue(SyncOptionEnum.INSERT.callInsert());
		assertFalse(SyncOptionEnum.INSERT.callUpdate());
		assertFalse(SyncOptionEnum.INSERT.callDelete());

		assertFalse(SyncOptionEnum.UPDATE.callInsert());
		assertTrue(SyncOptionEnum.UPDATE.callUpdate());
		assertFalse(SyncOptionEnum.UPDATE.callDelete());

		assertFalse(SyncOptionEnum.DELETE.callInsert());
		assertFalse(SyncOptionEnum.DELETE.callUpdate());
		assertTrue(SyncOptionEnum.DELETE.callDelete());
	}
}

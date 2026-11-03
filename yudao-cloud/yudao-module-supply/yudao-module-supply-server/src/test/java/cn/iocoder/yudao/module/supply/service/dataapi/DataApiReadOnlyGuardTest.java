package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataApiReadOnlyGuardTest {

    @Test
    void draftRejectsWriteSqlEvenWhenApiTypeIsQuery() {
        DataApiDO api = DataApiDO.builder()
                .apiType(DataApiDO.API_TYPE_QUERY)
                .configType(DataApiDO.CONFIG_TYPE_SQL)
                .sqlText("INSERT INTO orders(id) VALUES (1)")
                .build();
        assertThatThrownBy(() -> DataApiReadOnlyGuard.assertAllowed(
                api, ProductTypeEnum.POSTGRESQL, DataApiExecuteMode.DRAFT_READONLY_PREVIEW))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("只读");
    }

    @Test
    void draftRejectsForwardAndMulti() {
        DataApiDO forward = DataApiDO.builder()
                .apiType(DataApiDO.API_TYPE_QUERY)
                .configType(DataApiDO.CONFIG_TYPE_FORWARD)
                .forwardUrl("https://example.com")
                .build();
        assertThatThrownBy(() -> DataApiReadOnlyGuard.assertAllowed(
                forward, null, DataApiExecuteMode.ADMIN_EXECUTE))
                .isInstanceOf(ServiceException.class);
        DataApiDO multi = DataApiDO.builder()
                .apiType(DataApiDO.API_TYPE_MULTI)
                .configType(DataApiDO.CONFIG_TYPE_SQL)
                .sqlText("SELECT 1")
                .build();
        assertThatThrownBy(() -> DataApiReadOnlyGuard.assertAllowed(
                multi, ProductTypeEnum.POSTGRESQL, DataApiExecuteMode.DRAFT_READONLY_PREVIEW))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void tableQueryAndSelectAreAllowed() {
        DataApiDO table = DataApiDO.builder()
                .apiType(DataApiDO.API_TYPE_QUERY)
                .configType(DataApiDO.CONFIG_TYPE_TABLE)
                .tableName("orders")
                .build();
        assertThatCode(() -> DataApiReadOnlyGuard.assertAllowed(
                table, ProductTypeEnum.POSTGRESQL, DataApiExecuteMode.DRAFT_READONLY_PREVIEW))
                .doesNotThrowAnyException();
        DataApiDO sql = DataApiDO.builder()
                .apiType(DataApiDO.API_TYPE_QUERY)
                .configType(DataApiDO.CONFIG_TYPE_SQL)
                .sqlText("SELECT id FROM orders")
                .build();
        assertThatCode(() -> DataApiReadOnlyGuard.assertAllowed(
                sql, ProductTypeEnum.POSTGRESQL, DataApiExecuteMode.ADMIN_EXECUTE))
                .doesNotThrowAnyException();
    }
}

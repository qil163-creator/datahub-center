package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.physical.DorisExtVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelDdlDecimalContractTest {
    private final cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime =
            cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone();
    @org.junit.jupiter.api.AfterEach void closeRuntime() { dbSwitchRuntime.close(); }

    @Test
    void dorisAdapter_shouldMapTotalPrecisionAndScaleWithoutLegacyDefaults() {
        ModelDdlExecutor executor = new ModelDdlExecutor();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", dbSwitchRuntime);
        var connection = new ModelingDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.DORIS);
        DorisExtVO dorisExt = executor.parseDorisExt(
                """
                {"tableModel":"DUPLICATE","keyColumns":["id"],"distributionType":"HASH","distributionColumns":["id"],"bucketMode":"AUTO"}
                """);
        ModelFieldItem id = new ModelFieldItem();
        id.setFieldName("id");
        id.setDataType("bigint");
        ModelFieldItem amount = new ModelFieldItem();
        amount.setFieldName("amount");
        amount.setDataType("decimal");
        amount.setDataPrecision(18);
        for (int scale : List.of(0, 2)) {
            amount.setDataScale(scale);
            String ddl = String.join("\n", executor.buildCreateTableDdl(
                    "decimal_contract", "decimal_contract", List.of(id, amount), connection, dorisExt));
            assertTrue(ddl.contains("DECIMAL(18," + scale + ")"), ddl);
        }
        amount.setDataScale(null);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> executor.buildCreateTableDdl(
                        "decimal_contract", "decimal_contract", List.of(id, amount), connection, dorisExt));
    }
}

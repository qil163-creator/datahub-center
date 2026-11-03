package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPushReadOnlySupportTest {

    @Test
    @DisplayName("PostgreSQL URL 追加 readOnly")
    void postgresAppendsReadOnly() {
        String url = DataPushReadOnlySupport.applyUrl(ProductTypeEnum.POSTGRESQL,
                "jdbc:postgresql://localhost:5432/demo");
        assertTrue(url.contains("readOnly=true") || url.contains("readonly=true"));
    }

    @Test
    @DisplayName("已有 readOnly 不再重复追加")
    void skipExisting() {
        String raw = "jdbc:postgresql://localhost:5432/demo?readOnly=true";
        assertEquals(raw, DataPushReadOnlySupport.applyUrl(ProductTypeEnum.POSTGRESQL, raw));
    }

    @Test
    @DisplayName("MySQL URL 追加 transaction_read_only")
    void mysqlAppendsSessionReadOnly() {
        String url = DataPushReadOnlySupport.applyUrl(ProductTypeEnum.MYSQL,
                "jdbc:mysql://localhost:3306/demo");
        assertTrue(url.toLowerCase().contains("transaction_read_only"));
    }

}

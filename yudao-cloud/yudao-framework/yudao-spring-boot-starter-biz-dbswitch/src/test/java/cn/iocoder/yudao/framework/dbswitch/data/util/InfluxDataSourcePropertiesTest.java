package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InfluxDataSourcePropertiesTest {
    @ParameterizedTest
    @ValueSource(strings={"token", "%74oken", "ToKeN", "password", "user", "username", "authorization"})
    void embeddedCredentialsAreRejectedBeforePoolCreationWithoutEchoingInput(String key) {
        var properties=target();properties.setUrl("jdbc:arrow-flight-sql://127.0.0.1:1/?database=unit&"+key+"=sensitive_unit_value");
        var error=assertThrows(IllegalArgumentException.class,()->DataSourceUtils.createTargetDataSource(properties));
        assertFalse(error.getMessage().contains("sensitive_unit_value"));assertNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings={"jdbc:mysql://127.0.0.1/unit", "jdbc:arrow-flight-sql://user:secret@127.0.0.1:1/", "jdbc:arrow-flight-sql://127.0.0.1:1/#secret", "jdbc:arrow-flight-sql://:1/"})
    void malformedOrCredentialBearingUrlNeverStartsPool(String url) {
        var properties=target();properties.setUrl(url);
        assertThrows(IllegalArgumentException.class,()->DataSourceUtils.createTargetDataSource(properties));
    }

    @Test void absentTokenIsNotAnAnonymousConnectionFallback() {
        var properties=target();properties.setPassword(null);
        assertThrows(IllegalArgumentException.class,()->DataSourceUtils.createTargetDataSource(properties));
        var source=new SourceDataSourceProperties();source.setSourceProductType(ProductTypeEnum.INFLUXDB3);source.setUrl(properties.getUrl());
        assertThrows(IllegalArgumentException.class,()->DataSourceUtils.createSourceDataSource(source));
    }

    private TargetDataSourceProperties target() {
        var properties=new TargetDataSourceProperties();properties.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        properties.setUrl("jdbc:arrow-flight-sql://127.0.0.1:1/?database=unit");properties.setPassword("unit_fixture_not_a_real_token");
        return properties;
    }
}

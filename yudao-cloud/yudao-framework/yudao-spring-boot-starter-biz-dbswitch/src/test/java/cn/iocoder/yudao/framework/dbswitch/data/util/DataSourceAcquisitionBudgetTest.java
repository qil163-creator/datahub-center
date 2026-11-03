package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.driver.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourceAcquisitionBudgetTest {
    @Test void onlyExplicitOwnedBudgetClampsAcquisitionAndValidationWithoutChangingDto() {
        var input=new SourceDataSourceProperties();input.setSourceProductType(ProductTypeEnum.POSTGRESQL);input.setConnectionTimeout(700L);
        var observed=new AtomicReference<JdbcPoolOptions>();
        try(var registry=new DriverRuntimeRegistry();var factory=mockStatic(DriverPoolFactory.class)) {
            factory.when(()->DriverPoolFactory.create(any(),isNull(),same(registry))).thenAnswer(call->{observed.set(call.getArgument(0));return null;});
            DataSourceUtils.createSourceDataSource(input,null,null,null,registry,DbSwitchOptions.defaults(),1000L);
            assertEquals(500,observed.get().connectionTimeoutMs());assertEquals(500,observed.get().validationTimeoutMs());
            assertEquals(700,input.getConnectionTimeout());
            DataSourceUtils.createSourceDataSource(input,null,null,null,registry,DbSwitchOptions.defaults());
            assertEquals(700,observed.get().connectionTimeoutMs());assertEquals(DbSwitchOptions.defaults().poolValidationTimeoutMs(),observed.get().validationTimeoutMs());
        }
    }
    @Test void explicitSmallerLimitIsNotRaisedToTheReservedHalf() {
        var output=new TargetDataSourceProperties();output.setTargetProductType(ProductTypeEnum.POSTGRESQL);output.setConnectionTimeout(250L);
        var observed=new AtomicReference<JdbcPoolOptions>();
        try(var registry=new DriverRuntimeRegistry();var factory=mockStatic(DriverPoolFactory.class)) {
            factory.when(()->DriverPoolFactory.create(any(),isNull(),same(registry))).thenAnswer(call->{observed.set(call.getArgument(0));return null;});
            DataSourceUtils.createTargetDataSource(output,null,null,null,registry,DbSwitchOptions.defaults(),1000L);
            assertEquals(250,observed.get().connectionTimeoutMs());assertEquals(500,observed.get().validationTimeoutMs());
            assertEquals(250,output.getConnectionTimeout());
        }
    }
    @Test void impossiblePoolLimitsFailBeforeStartingAnyPhysicalDriver() {
        var input=new SourceDataSourceProperties();input.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        try(var registry=new DriverRuntimeRegistry();var factory=mockStatic(DriverPoolFactory.class)) {
            input.setConnectionTimeout(249L);
            assertThrows(IllegalArgumentException.class,()->DataSourceUtils.createSourceDataSource(input,null,null,null,registry,DbSwitchOptions.defaults(),1000L));
            input.setConnectionTimeout(1000L);
            assertThrows(IllegalArgumentException.class,()->DataSourceUtils.createSourceDataSource(input,null,null,null,registry,DbSwitchOptions.defaults(),999L));
            assertThrows(IllegalArgumentException.class,()->DataSourceUtils.createSourceDataSource(input,null,null,null,registry,DbSwitchOptions.defaults(),120001L));
            factory.verifyNoInteractions();
        }
    }
}

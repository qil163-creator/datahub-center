package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MasterDecimalWireTest {
    @Test void decimalSurvivesReadJsonAndWriteWithoutBinaryConversion() {
        String decimal = "123456789012345678.123456789012345678";
        var rows = MasterDataServiceImpl.toWireRows(List.of(Map.of("amount", new BigDecimal(decimal))));
        assertThat(JsonUtils.toJsonString(rows)).contains("\"amount\":\"" + decimal + "\"");
        MasterColumnDO column = MasterColumnDO.builder().name("amount").fieldType(6).build();
        assertThat(MasterDataServiceImpl.convertCell(column, rows.getFirst().get("amount")))
                .isEqualTo(new BigDecimal(decimal));
    }
    @Test void exactTypeRejectsAnAlreadyRoundedFloatingNumber() {
        MasterColumnDO column = MasterColumnDO.builder().name("amount").fieldType(6).build();
        assertThatThrownBy(() -> MasterDataServiceImpl.convertCell(column, 0.1d)).isInstanceOf(ServiceException.class);
    }
    @Test void everyExternalDeliveryBatchSerializesDecimalsAsText() throws Exception {
        var executor=new MasterDistributeExecutor();
        var columns=org.mockito.Mockito.mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper.class);
        var resolver=org.mockito.Mockito.mock(MasterDatabaseResolver.class);
        var runtime=org.mockito.Mockito.mock(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.class);
        var source=org.mockito.Mockito.mock(com.zaxxer.hikari.HikariDataSource.class);
        var operator=org.mockito.Mockito.mock(cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator.class);
        var rows=org.mockito.Mockito.mock(java.sql.ResultSet.class);
        var result=org.mockito.Mockito.mock(cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet.class);
        var model=new cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO();
        model.setId(1L);model.setDatabaseId(9L);model.setTableName("payments");
        var connection=new MasterDatabaseResolver.ConnectionInfo();
        connection.setSchema("public");connection.setProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL);
        org.mockito.Mockito.when(columns.selectListByModelId(1L)).thenReturn(List.of(MasterColumnDO.builder().name("amount").fieldType(6).build()));
        org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.eq(9L),org.mockito.ArgumentMatchers.any())).thenReturn(connection);
        org.mockito.Mockito.when(resolver.createDataSource(connection)).thenReturn(source);
        org.mockito.Mockito.when(runtime.reader(source,connection.getProductType())).thenReturn(operator);
        org.mockito.Mockito.when(operator.queryTableData("public","payments",List.of("amount"))).thenReturn(result);
        org.mockito.Mockito.when(result.getResultset()).thenReturn(rows);
        org.mockito.Mockito.when(rows.next()).thenReturn(true,true,false);
        org.mockito.Mockito.when(rows.getObject("amount")).thenReturn(new BigDecimal("987654321012345678.123456789"));
        org.springframework.test.util.ReflectionTestUtils.setField(executor,"columnMapper",columns);
        org.springframework.test.util.ReflectionTestUtils.setField(executor,"databaseResolver",resolver);
        org.springframework.test.util.ReflectionTestUtils.setField(executor,"dbSwitchRuntime",runtime);
        var payloads=new java.util.ArrayList<String>();
        Class<?> consumerType=Class.forName(MasterDistributeExecutor.class.getName()+"$BatchConsumer");
        Object consumer=java.lang.reflect.Proxy.newProxyInstance(consumerType.getClassLoader(),new Class<?>[]{consumerType},
                (proxy,method,args) -> {payloads.add(JsonUtils.toJsonString(args[0]));return null;});
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(executor,"readAndBatch",model,1,consumer);
        assertThat(payloads).hasSize(2).allSatisfy(json -> assertThat(json)
                .contains("\"amount\":\"987654321012345678.123456789\""));
    }
    @Test void largeIntegralReadRemainsText() {
        assertThat(MasterDataServiceImpl.toWireRows(List.of(Map.of("id", Long.MAX_VALUE))).getFirst().get("id"))
                .isEqualTo(Long.toString(Long.MAX_VALUE));
    }
}

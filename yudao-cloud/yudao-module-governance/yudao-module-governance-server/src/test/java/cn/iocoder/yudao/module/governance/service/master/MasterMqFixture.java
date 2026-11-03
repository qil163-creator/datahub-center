package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.util.List;

import static org.mockito.Mockito.*;

/** Deterministic two-row source; integration tests use real brokers, not a real source database. */
final class MasterMqFixture implements AutoCloseable {
    final MasterDistributeExecutor executor = new MasterDistributeExecutor();
    final MasterDistributeRunCoordinator coordinator = mock(MasterDistributeRunCoordinator.class);
    final MasterDistributeLogDO run = MasterDistributeLogDO.builder().id(100L).distributeId(10L)
            .dataCount(0L).byteCount("0").build();
    final HikariDataSource source = mock(HikariDataSource.class);
    final ResultSet rows = mock(ResultSet.class);

    MasterMqFixture(MasterDistributeConfigVO.MqConfig mq) throws Exception {
        var config = new MasterDistributeConfigVO(); config.setMq(mq);
        var task = MasterDistributeDO.builder().id(10L).masterModelId(20L).distributeType(3)
                .distributeJson(JsonUtils.toJsonString(config)).build();
        var tasks = mock(MasterDistributeMapper.class); when(tasks.selectById(10L)).thenReturn(task);
        var model = new MasterModelDO(); model.setId(20L); model.setStatus(1);
        model.setDatabaseId(30L); model.setTableName("fixture_rows");
        var models = mock(MasterModelMapper.class); when(models.selectById(20L)).thenReturn(model);
        var column = new MasterColumnDO(); column.setName("id");
        var columns = mock(MasterColumnMapper.class); when(columns.selectListByModelId(20L)).thenReturn(List.of(column));
        var connection = new MasterDatabaseResolver.ConnectionInfo(); connection.setSchema("public");
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        var resolver = mock(MasterDatabaseResolver.class); when(resolver.resolve(anyLong(), any())).thenReturn(connection);
        when(resolver.createDataSource(connection)).thenReturn(source);
        when(rows.next()).thenReturn(true, true, false); when(rows.getObject("id")).thenReturn(1, 2);
        var result = new StatementResultSet(); result.setResultset(rows);
        var operator = mock(IDatabaseOperator.class); when(operator.queryTableData(anyString(), anyString(), anyList())).thenReturn(result);
        when(coordinator.begin(run)).thenReturn(true);
        ReflectionTestUtils.setField(executor, "distributeMapper", tasks);
        ReflectionTestUtils.setField(executor, "modelMapper", models);
        ReflectionTestUtils.setField(executor, "columnMapper", columns);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "coordinator", coordinator);
        var runtime = mock(DbSwitchRuntime.class);
        when(runtime.reader(source, ProductTypeEnum.POSTGRESQL)).thenReturn(operator);
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
    }

    void execute() { executor.execute(run); }
    void outcome(boolean sending, int status) {
        verify(coordinator).finish(eq(run), eq(sending), eq(status), nullable(String.class));
    }
    @Override public void close() { source.close(); }

    static MasterDistributeConfigVO.MqConfig kafka(String address, String topic) {
        var config = new MasterDistributeConfigVO.MqConfig(); config.setMqType("kafka"); config.setFetchSize(1);
        var kafka = new MasterDistributeConfigVO.KafkaConfig(); kafka.setBootstrapServers(address); kafka.setTopic(topic);
        config.setKafka(kafka); return config;
    }

    static MasterDistributeConfigVO.MqConfig rabbit(int port, String exchange, String key) {
        var config = new MasterDistributeConfigVO.MqConfig(); config.setMqType("rabbitmq"); config.setFetchSize(1);
        var rabbit = new MasterDistributeConfigVO.RabbitConfig(); rabbit.setHost("127.0.0.1"); rabbit.setPort(port);
        rabbit.setExchange(exchange); rabbit.setRoutingKey(key); config.setRabbit(rabbit); return config;
    }
}

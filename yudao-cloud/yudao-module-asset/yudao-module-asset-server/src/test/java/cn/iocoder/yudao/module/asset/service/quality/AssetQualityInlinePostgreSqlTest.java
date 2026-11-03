package cn.iocoder.yudao.module.asset.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.quality.*;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.*;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetEvaluationDTO;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实数据库证明同步超时后定义已提交，并可按原任务编号核对重试。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = ".+")
class AssetQualityInlinePostgreSqlTest {
    @Test @Timeout(60)
    void timeoutKeepsCommittedDefinitionAndRetryReusesOriginalTask() throws Exception {
        String control = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertTrue(control.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres"));
        String database = "quality_inline_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(control, "catalog_test", ""); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = control.substring(0, control.lastIndexOf('/') + 1) + database;
        try {
            var source = new UnpooledDataSource("org.postgresql.Driver", url, "catalog_test", "");
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                String audit = ",creator varchar default '',updater varchar default '',create_time timestamp default CURRENT_TIMESTAMP,update_time timestamp default CURRENT_TIMESTAMP,deleted smallint default 0";
                statement.execute("CREATE SEQUENCE dh_asset_quality_task_seq");
                statement.execute("CREATE TABLE dh_asset_quality_task(id bigint primary key,asset_id bigint,quality_task_id bigint,task_name varchar,schedule_type int,cron_expression varchar,enabled int,run_status int,last_run_time timestamp,last_finish_time timestamp,total_count bigint,issue_count bigint,quality_score numeric,delete_pending int,remote_error varchar,remark varchar,evaluation_json text,pending_run_key varchar" + audit + ")");
                statement.execute("CREATE TABLE dh_asset_quality_task_rule(id bigint primary key,task_id bigint,audit_rule_id bigint,quality_rule_id bigint,rule_name varchar,dimension_name varchar,meta_column_id bigint,column_name varchar,quality_engine_task_id bigint,remark varchar" + audit + ")");
            }
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("inline-quality", new SpringManagedTransactionFactory(), source));
            GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()));
            configuration.addMapper(AssetQualityTaskMapper.class); configuration.addMapper(AssetQualityTaskRuleMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
            var tasks = session.getMapper(AssetQualityTaskMapper.class);
            var service = new AssetQualityTaskServiceImpl();
            ReflectionTestUtils.setField(service, "taskMapper", tasks);
            ReflectionTestUtils.setField(service, "taskRuleMapper", session.getMapper(AssetQualityTaskRuleMapper.class));
            ReflectionTestUtils.setField(service, "transactionManager", new DataSourceTransactionManager(source));
            var assets = mock(DataAssetMapper.class);
            when(assets.selectById(1L)).thenReturn(DataAssetDO.builder().id(1L).datasourceId(3L).metaTableId(4L).tableName("actual_table").build());
            ReflectionTestUtils.setField(service, "dataAssetMapper", assets);
            ReflectionTestUtils.setField(service, "catalogService", mock(AssetCatalogService.class));
            var metadata = mock(MetadataApi.class); var table = new MetadataTableRespDTO(); table.setDatasourceId(3L); table.setTableName("actual_table");
            var column = new MetadataColumnRespDTO(); column.setId(7L); column.setColumnName("amount");
            when(metadata.getTable(4L)).thenReturn(CommonResult.success(table)); when(metadata.getColumnList(4L)).thenReturn(CommonResult.success(List.of(column)));
            ReflectionTestUtils.setField(service, "metadataApi", metadata);
            var engine = mock(QualityTaskApi.class); ReflectionTestUtils.setField(service, "qualityTaskApi", engine);
            AtomicLong originalId = new AtomicLong();
            when(engine.syncFromAsset(any())).thenAnswer(invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                try (var connection = source.getConnection(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT id,evaluation_json FROM dh_asset_quality_task WHERE deleted=0")) {
                    assertTrue(result.next()); originalId.set(result.getLong(1)); assertTrue(result.getString(2).contains("9007199254740993.123456789"));
                }
                throw new RuntimeException("timeout");
            });
            var request = new AssetQualityTaskSaveReqVO(); request.setAssetId(1L); request.setTaskName("金额检查"); request.setScheduleType(0); request.setEnabled(1);
            var evaluation = new QualityAssetEvaluationDTO(); evaluation.setEvaluationName("金额边界"); evaluation.setRuleCode("306"); evaluation.setStrategyKey("NUMERIC_RANGE_VALIDATION"); evaluation.setQualityDim("CONSISTENCY");
            evaluation.setColumnNames(List.of("amount")); evaluation.setConfig(Map.of("minValue", "9007199254740993.123456789")); request.setEvaluations(List.of(evaluation));
            assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> service.createTask(request));
            var saved = tasks.selectById(originalId.get()); assertNotNull(saved); assertNull(saved.getQualityTaskId()); assertTrue(saved.getRemoteError().contains("未知"));
            assertTrue(service.getTaskList(1L).getFirst().getPendingSync());
            doAnswer(invocation -> {
                assertEquals(originalId.get(), ((cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO) invocation.getArgument(0)).getAssetTaskId());
                return CommonResult.success(81L);
            }).when(engine).syncFromAsset(any());
            List<String> executionKeys = new ArrayList<>();
            when(engine.validateForRun(eq(81L), any())).thenReturn(CommonResult.success(true));
            when(engine.executeForRun(any())).thenAnswer(invocation -> {
                var run = (cn.iocoder.yudao.module.governance.api.quality.dto.QualityRunReqDTO) invocation.getArgument(0);
                executionKeys.add(run.getRequestKey());
                if (executionKeys.size() == 1) throw new RuntimeException("receipt timeout");
                return CommonResult.success(91L);
            });
            assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> service.executeTask(originalId.get()));
            var unknown = tasks.selectById(originalId.get()); assertEquals(4, unknown.getRunStatus()); assertNotNull(unknown.getPendingRunKey());
            assertEquals(91L, service.executeTask(originalId.get()));
            assertEquals(2, executionKeys.size()); assertEquals(executionKeys.getFirst(), executionKeys.getLast());
            var synchronizedTask = tasks.selectById(originalId.get()); assertEquals(81L, synchronizedTask.getQualityTaskId()); assertNull(synchronizedTask.getRemoteError());
            assertNull(synchronizedTask.getPendingRunKey()); assertEquals(1, synchronizedTask.getRunStatus());
            assertEquals(1L, tasks.selectCount());
        } finally {
            try (var connection = DriverManager.getConnection(control, "catalog_test", ""); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }
}

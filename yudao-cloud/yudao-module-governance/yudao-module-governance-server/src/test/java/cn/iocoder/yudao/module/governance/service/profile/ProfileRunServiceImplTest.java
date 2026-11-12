package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileLatestRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileValueMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_PRODUCT_UNSUPPORTED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_RUN_ACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发起探查的单表并发限制与最新结果组装。
 */
class ProfileRunServiceImplTest {

    private ProfileRunServiceImpl service;
    private ProfileRunMapper runMapper;
    private ProfileColumnMapper columnMapper;
    private ProfileValueMapper valueMapper;
    private ProfileRunExecutor executor;
    private MasterDatabaseResolver.ConnectionInfo conn;
    private ProfileTargetLoader loader;

    @BeforeEach
    void setUp() {
        runMapper = mock(ProfileRunMapper.class);
        columnMapper = mock(ProfileColumnMapper.class);
        valueMapper = mock(ProfileValueMapper.class);
        executor = mock(ProfileRunExecutor.class);
        loader = mock(ProfileTargetLoader.class);
        MetaTableDO table = new MetaTableDO();
        table.setId(10L);
        table.setDatasourceId(3L);
        table.setTableName("orders");
        MetaDbDO db = new MetaDbDO();
        db.setDbName("shop");
        MetaColumnDO column = new MetaColumnDO();
        column.setColumnName("id");
        when(loader.load(10L)).thenReturn(new ProfileTargetLoader.ProfileTarget(table, db, List.of(column)));
        when(loader.requireTable(10L)).thenReturn(table);
        MasterDatabaseResolver resolver = mock(MasterDatabaseResolver.class);
        conn = new MasterDatabaseResolver.ConnectionInfo();
        conn.setProductType(ProductTypeEnum.MYSQL);
        when(resolver.resolve(eq(3L), any())).thenReturn(conn);

        service = new ProfileRunServiceImpl();
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "columnMapper", columnMapper);
        ReflectionTestUtils.setField(service, "valueMapper", valueMapper);
        ReflectionTestUtils.setField(service, "targetLoader", loader);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "executor", executor);
        ReflectionTestUtils.setField(service, "adminUserApi", mock(AdminUserApi.class));
    }

    @Test
    void createInsertsPendingRunAndDispatches() {
        doAnswer(call -> {
            call.<ProfileRunDO>getArgument(0).setId(55L);
            return 1;
        }).when(runMapper).insert(any(ProfileRunDO.class));
        assertThat(service.createRun(10L, 1L)).isEqualTo(55L);
        ArgumentCaptor<ProfileRunDO> captor = ArgumentCaptor.forClass(ProfileRunDO.class);
        verify(runMapper).insert(captor.capture());
        ProfileRunDO run = captor.getValue();
        assertThat(run.getStatus()).isEqualTo(ProfileRunDO.STATUS_PENDING);
        assertThat(run.getSampleLimit()).isEqualTo(100_000);
        assertThat(run.getExecutorUserId()).isEqualTo(1L);
        assertThat(run.getSchemaName()).isEqualTo("shop");
        assertThat(run.getAttemptCount()).isZero();
        verify(executor).dispatch(55L);
    }

    @Test
    void activeRunBlocksANewRun() {
        when(runMapper.selectActive(10L)).thenReturn(new ProfileRunDO());
        assertThatThrownBy(() -> service.createRun(10L, 1L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(PROFILE_RUN_ACTIVE.getCode());
        verify(runMapper, never()).insert(any(ProfileRunDO.class));
        verify(executor, never()).dispatch(anyLong());
    }

    @Test
    void concurrentInsertHittingPartialUniqueIndexBecomesBusinessError() {
        when(runMapper.insert(any(ProfileRunDO.class))).thenThrow(new DuplicateKeyException(
                "uk_dh_governance_profile_run_active"));
        assertThatThrownBy(() -> service.createRun(10L, 1L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(PROFILE_RUN_ACTIVE.getCode());
        verify(executor, never()).dispatch(anyLong());
    }

    @Test
    void unsupportedProductIsRejectedBeforeInsert() {
        conn.setProductType(ProductTypeEnum.SQLSERVER);
        assertThatThrownBy(() -> service.createRun(10L, 1L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(PROFILE_PRODUCT_UNSUPPORTED.getCode());
        verify(runMapper, never()).insert(any(ProfileRunDO.class));
    }

    @Test
    void latestReturnsPollingRunAndLastSuccessWithSuggestionsAsDecimalStrings() {
        ProfileRunDO running = run(9L, ProfileRunDO.STATUS_RUNNING);
        ProfileRunDO success = run(8L, ProfileRunDO.STATUS_SUCCESS);
        success.setSampleRows(100L);
        when(runMapper.selectLatest(10L)).thenReturn(running);
        when(runMapper.selectLatestSuccess(10L)).thenReturn(success);
        ProfileColumnDO column = new ProfileColumnDO();
        column.setId(1L);
        column.setColumnName("status");
        column.setColumnKind("TEXT");
        column.setNonNullCount(100L);
        column.setNullCount(0L);
        column.setNullRate(new BigDecimal("0"));
        column.setDistinctCount(2L);
        column.setUniqueRate(new BigDecimal("0.02"));
        column.setValueMasked(0);
        when(columnMapper.selectListByRunId(8L)).thenReturn(List.of(column));
        ProfileValueDO value = new ProfileValueDO();
        value.setProfileColumnId(1L);
        value.setRankNo(1);
        value.setValueText("A");
        value.setValueCount(60L);
        value.setValueMasked(0);
        when(valueMapper.selectListByRunId(8L)).thenReturn(List.of(value));

        ProfileLatestRespVO latest = service.getLatest(10L);
        assertThat(latest.getLatestRun().getId()).isEqualTo(9L);
        assertThat(latest.getResultRun().getId()).isEqualTo(8L);
        assertThat(latest.getColumns().get(0).getNullRate()).isEqualTo("0.000000");
        assertThat(latest.getColumns().get(0).getUniqueRate()).isEqualTo("0.020000");
        assertThat(latest.getColumns().get(0).getTopValues().get(0).getRatio()).isEqualTo("0.600000");
        assertThat(latest.getSuggestions()).extracting("ruleType").containsExactly("NOT_NULL", "ENUM");
    }

    @Test
    void latestWithoutAnyRunIsEmpty() {
        ProfileLatestRespVO latest = service.getLatest(10L);
        assertThat(latest.getLatestRun()).isNull();
        assertThat(latest.getResultRun()).isNull();
        assertThat(latest.getColumns()).isEmpty();
        assertThat(latest.getSuggestions()).isEmpty();
    }

    private static ProfileRunDO run(Long id, String status) {
        ProfileRunDO run = new ProfileRunDO();
        run.setId(id);
        run.setMetaTableId(10L);
        run.setStatus(status);
        return run;
    }

}

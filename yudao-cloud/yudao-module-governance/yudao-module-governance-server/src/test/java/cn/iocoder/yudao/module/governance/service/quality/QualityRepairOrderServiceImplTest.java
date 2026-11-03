package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRepairOrderDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityCheckObjectMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRepairOrderMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_REPAIR_PRODUCT_UNSUPPORTED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_REPAIR_SQL_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_REPAIR_STATUS_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_REPAIR_UNKNOWN_NO_REPLAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityRepairOrderServiceImplTest {

    @Mock private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime dbSwitchRuntime;
    @org.junit.jupiter.api.BeforeEach void runtime() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "repair-test"),
                QualityRepairOrderDO.class);
        org.mockito.Mockito.lenient().when(dbSwitchRuntime.products())
                .thenReturn(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
    }
    @Mock
    private QualityRepairOrderMapper repairOrderMapper;
    @Mock
    private QualityCheckObjectMapper checkObjectMapper;
    @Mock
    private MasterDatabaseResolver databaseResolver;
    @Mock
    private PlatformTransactionManager transactionManager;
    @InjectMocks
    private QualityRepairOrderServiceImpl service;

    @Test
    void changingSqlAfterApprovalInvalidatesOldApproval() {
        QualityRepairOrderDO exist = approvedOrder("UPDATE orders SET status = 'A' WHERE id = 1");
        when(repairOrderMapper.selectById(8L)).thenReturn(exist);
        when(repairOrderMapper.selectForUpdate(8L)).thenReturn(exist);
        when(transactionManager.getTransaction(any())).thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));
        when(checkObjectMapper.selectById(3L)).thenReturn(object());
        mockProduct();

        QualityRepairOrderSaveReqVO req = new QualityRepairOrderSaveReqVO();
        req.setId(8L);
        req.setOrderTitle("fix");
        req.setRepairType(QualityRepairOrderDO.TYPE_CUSTOM_SQL);
        req.setObjectId(3L);
        req.setTableName("orders");
        req.setRepairConfig("{\"sql\":\"UPDATE orders SET status = 'B' WHERE id = 1\"}");
        req.setRepairSql("UPDATE orders SET status = 'B' WHERE id = 1");

        service.updateRepairOrder(req);

        ArgumentCaptor<QualityRepairOrderDO> captor = ArgumentCaptor.forClass(QualityRepairOrderDO.class);
        verify(repairOrderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getApproveStatus()).isEqualTo(QualityRepairOrderDO.APPROVE_PENDING);
        assertThat(captor.getValue().getApprovedContentHash()).isNull();
        assertThat(captor.getValue().getContentVersion()).isEqualTo(2);
    }

    @Test
    void executeRejectsWhenApprovedSqlNoLongerMatches() {
        QualityRepairOrderDO order = approvedOrder("UPDATE orders SET status = 'A' WHERE id = 1");
        order.setRepairSql("UPDATE orders SET status = 'B' WHERE id = 1");
        when(repairOrderMapper.selectById(8L)).thenReturn(order);

        assertThatThrownBy(() -> service.executeRepairOrder(8L))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH.getCode());
        verify(repairOrderMapper, never()).claimExecution(any(), any(), any(), any());
    }

    @Test
    void unknownResultCannotBeReplayed() {
        QualityRepairOrderDO order = approvedOrder("UPDATE orders SET status = 'A' WHERE id = 1");
        order.setExecStatus(QualityRepairOrderDO.EXEC_UNKNOWN);
        when(repairOrderMapper.selectById(8L)).thenReturn(order);

        assertThatThrownBy(() -> service.executeRepairOrder(8L))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_REPAIR_UNKNOWN_NO_REPLAY.getCode());
        verify(repairOrderMapper, never()).claimExecution(any(), any(), any(), any());
    }

    @Test
    void t05SecondClaimIsRejectedWithoutRemoteExecute() {
        QualityRepairOrderDO order = approvedOrder("UPDATE orders SET status = 'A' WHERE id = 1");
        when(repairOrderMapper.selectById(8L)).thenReturn(order);
        when(checkObjectMapper.selectById(3L)).thenReturn(object());
        mockProduct();
        TransactionStatus tx = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(tx);
        when(repairOrderMapper.selectForUpdate(8L)).thenReturn(order);
        when(checkObjectMapper.selectOne(any())).thenReturn(object());
        when(repairOrderMapper.claimExecution(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.executeRepairOrder(8L))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_REPAIR_STATUS_INVALID.getCode());
        verify(repairOrderMapper, never()).completeIfTokenMatches(any(), any(), any(), any(), any());
    }

    @Test
    void t08UnverifiedProductIsRejectedBeforeExecute() {
        QualityRepairOrderDO order = approvedOrder("UPDATE orders SET status = 'A' WHERE id = 1");
        when(repairOrderMapper.selectById(8L)).thenReturn(order);
        when(checkObjectMapper.selectById(3L)).thenReturn(object());
        MasterDatabaseResolver.ConnectionInfo info = new MasterDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.ORACLE);
        info.setConfigurationRevision("revision-1");
        info.setSchema("public");
        when(databaseResolver.resolve(any(), any())).thenReturn(info);

        assertThatThrownBy(() -> service.executeRepairOrder(8L))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_REPAIR_PRODUCT_UNSUPPORTED.getCode());
        verify(repairOrderMapper, never()).claimExecution(any(), any(), any(), any());
    }

    @Test
    void customSqlCannotTargetAnotherTable() {
        when(checkObjectMapper.selectById(3L)).thenReturn(object());
        mockProduct();
        QualityRepairOrderSaveReqVO req = new QualityRepairOrderSaveReqVO();
        req.setOrderTitle("fix");
        req.setRepairType(QualityRepairOrderDO.TYPE_CUSTOM_SQL);
        req.setObjectId(3L);
        req.setTableName("orders");
        req.setRepairSql("UPDATE secrets SET x = 1");

        assertThatThrownBy(() -> service.createRepairOrder(req))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(QUALITY_REPAIR_SQL_INVALID.getCode());
    }

    @Test
    void rebindSameNamedTableToAnotherSourceCannotUseOldApproval() {
        QualityRepairOrderDO order = approvedOrder("UPDATE orders SET status = 'A' WHERE id = 1");
        when(repairOrderMapper.selectById(8L)).thenReturn(order);
        QualityCheckObjectDO changed = object(); changed.setDatasourceConnId(99L);
        when(checkObjectMapper.selectById(3L)).thenReturn(changed);
        mockProduct();
        assertThatThrownBy(() -> service.executeRepairOrder(8L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH.getCode());
        verify(repairOrderMapper,never()).claimExecution(any(),any(),any(),any());
    }

    @Test
    void changedConfigurationRevisionCannotUseOldApproval() {
        when(repairOrderMapper.selectById(8L)).thenReturn(approvedOrder("UPDATE orders SET status = 'A' WHERE id = 1"));
        when(checkObjectMapper.selectById(3L)).thenReturn(object());
        MasterDatabaseResolver.ConnectionInfo changed=new MasterDatabaseResolver.ConnectionInfo();
        changed.setProductType(ProductTypeEnum.POSTGRESQL);changed.setSchema("public");
        changed.setConfigurationRevision("revision-2");
        when(databaseResolver.resolve(any(),any())).thenReturn(changed);
        assertThatThrownBy(() -> service.executeRepairOrder(8L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH.getCode());
        verify(repairOrderMapper,never()).claimExecution(any(),any(),any(),any());
    }

    @Test
    void explicitOtherSchemaCannotBypassFrozenTarget() {
        when(repairOrderMapper.selectById(8L)).thenReturn(approvedOrder("UPDATE other.orders SET status = 'A' WHERE id = 1"));
        when(checkObjectMapper.selectById(3L)).thenReturn(object()); mockProduct();
        assertThatThrownBy(() -> service.executeRepairOrder(8L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(QUALITY_REPAIR_SQL_INVALID.getCode());
        verify(repairOrderMapper,never()).claimExecution(any(),any(),any(),any());
    }
    private void mockProduct() {
        MasterDatabaseResolver.ConnectionInfo info = new MasterDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setConfigurationRevision("revision-1");
        info.setSchema("public");
        when(databaseResolver.resolve(any(), any())).thenReturn(info);
    }

    private QualityCheckObjectDO object() {
        return QualityCheckObjectDO.builder().id(3L).datasourceConnId(9L).tableName("orders").build();
    }

    private QualityRepairOrderDO approvedOrder(String sql) {
        QualityRepairOrderDO order = new QualityRepairOrderDO();
        order.setId(8L);
        order.setObjectId(3L);
        order.setTableName("orders");
        order.setRepairType(QualityRepairOrderDO.TYPE_CUSTOM_SQL);
        order.setRepairSql(sql);
        order.setRepairConfig("{\"sql\":\"" + sql + "\"}");
        order.setMaxAffectedRows(10000);
        order.setContentVersion(1);
        order.setApproveStatus(QualityRepairOrderDO.APPROVE_PASSED);
        order.setExecStatus(QualityRepairOrderDO.EXEC_PENDING);
        order.setApprovedSql(sql);
        order.setApprovedContentHash(DigestUtil.sha256Hex(String.join("|", "3", "orders",
                QualityRepairOrderDO.TYPE_CUSTOM_SQL, sql, order.getRepairConfig(), "10000")));
        Map<String,Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion",2);snapshot.put("objectId",3L);snapshot.put("datasourceId",9L);
        snapshot.put("metadataTableId",null);snapshot.put("schemaName","public");snapshot.put("tableName","orders");
        snapshot.put("productType","POSTGRESQL");snapshot.put("configurationRevision","revision-1");
        snapshot.put("repairType",order.getRepairType());snapshot.put("repairSql",sql);
        snapshot.put("maxAffectedRows",10000);snapshot.put("contentVersion",1);
        order.setApprovedSnapshot(JsonUtils.toJsonString(snapshot));
        return order;
    }
}

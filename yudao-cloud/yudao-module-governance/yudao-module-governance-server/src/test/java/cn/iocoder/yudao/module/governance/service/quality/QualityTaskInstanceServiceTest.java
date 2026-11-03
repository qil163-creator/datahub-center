package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceEtlBatchVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstancePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityEtlBatchRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskInstanceDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityEtlBatchRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_TASK_INSTANCE_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务实例只读监控：ETL 批次来源回填与敏感字段隔离
 */
class QualityTaskInstanceServiceTest {

    /** 范围清单与业务键信息，不得随实例监控响应泄露 */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "manifestJson", "identityFieldsJson", "identityHash", "batchKey", "physicalBatchValue", "manifestHash");

    private final QualityTaskInstanceServiceImpl service = new QualityTaskInstanceServiceImpl();
    private final QualityTaskInstanceMapper instanceMapper = mock(QualityTaskInstanceMapper.class);
    private final QualityTaskMapper taskMapper = mock(QualityTaskMapper.class);
    private final QualityEtlBatchRunMapper etlBatchRunMapper = mock(QualityEtlBatchRunMapper.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "instanceMapper", instanceMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "etlBatchRunMapper", etlBatchRunMapper);
    }

    @Test
    void detailExposesEtlBatchSourceWithoutScopeOrIdentitySecrets() {
        when(instanceMapper.selectById(30L)).thenReturn(instance(30L, "ETL_TARGET_BATCH"));
        when(taskMapper.selectById(10L)).thenReturn(QualityTaskDO.builder().id(10L).taskName("订单质检").build());
        when(etlBatchRunMapper.selectByInstanceId(30L)).thenReturn(boundRun(30L, 70L, 50L));

        QualityTaskInstanceRespVO vo = service.getInstanceDetail(30L);

        assertThat(vo.getId()).isEqualTo(30L);
        assertThat(vo.getTaskName()).isEqualTo("订单质检");
        QualityTaskInstanceEtlBatchVO etl = vo.getEtlBatch();
        assertThat(etl).isNotNull();
        assertThat(etl.getRecordId()).isEqualTo(70L);
        assertThat(etl.getTargetBatchId()).isEqualTo(50L);
        assertThat(etl.getSinkNodeId()).isEqualTo("sink-1");
        assertThat(etl.getQualityNodeId()).isEqualTo("quality-1");
        assertThat(etl.getQualityBatchId()).isEqualTo("batch-1");
        assertThat(etl.getInlineReportId()).isEqualTo(40L);
        assertThat(etl.getRowCount()).isEqualTo(10L);
        assertThat(etl.getAdmittedRowCount()).isEqualTo(9L);
        assertThat(etl.getAllowWarnings()).isTrue();
        assertThat(etl.getState()).isEqualTo("QUEUED");
        assertThat(etl.getReplayOfBatchId()).isEqualTo(49L);
        assertThat(etl.getReplayOfRecordId()).isEqualTo(69L);
        assertThat(etl.getWorkOrderSubmissionId()).isEqualTo(88L);
        assertThat(etl.getRunId()).isEqualTo("run-1");

        // VO 类型本身不声明范围清单/业务键字段，也没有对应 getter，可从契约层面保证不会被序列化
        List<String> fieldNames = Arrays.stream(QualityTaskInstanceEtlBatchVO.class.getDeclaredFields())
                .map(Field::getName).toList();
        assertThat(fieldNames).doesNotContainAnyElementsOf(SENSITIVE_FIELDS);
        List<String> getterNames = Arrays.stream(QualityTaskInstanceEtlBatchVO.class.getMethods())
                .map(Method::getName).filter(name -> name.startsWith("get")).toList();
        for (String sensitive : SENSITIVE_FIELDS) {
            String getter = "get" + Character.toUpperCase(sensitive.charAt(0)) + sensitive.substring(1);
            assertThat(getterNames).as("不应暴露 %s", getter).doesNotContain(getter);
        }
    }

    @Test
    void detailWithoutEtlBindingLeavesEtlBatchNull() {
        when(instanceMapper.selectById(31L)).thenReturn(instance(31L, "MANUAL"));
        when(taskMapper.selectById(10L)).thenReturn(QualityTaskDO.builder().id(10L).taskName("订单质检").build());
        when(etlBatchRunMapper.selectByInstanceId(31L)).thenReturn(null);

        QualityTaskInstanceRespVO vo = service.getInstanceDetail(31L);

        assertThat(vo.getTaskName()).isEqualTo("订单质检");
        assertThat(vo.getRunType()).isEqualTo("MANUAL");
        assertThat(vo.getEtlBatch()).isNull();
    }

    @Test
    void detailOfMissingInstanceReusesExistingErrorCode() {
        when(instanceMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getInstanceDetail(404L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(QUALITY_TASK_INSTANCE_NOT_EXISTS.getCode());
        verifyNoInteractions(taskMapper, etlBatchRunMapper);
    }

    @Test
    void pageFillsEtlBatchByMatchingInstanceIdInOneBatchQuery() {
        QualityTaskInstancePageReqVO reqVO = new QualityTaskInstancePageReqVO();
        when(instanceMapper.selectPage(reqVO)).thenReturn(new PageResult<>(
                List.of(instance(30L, "MANUAL"), instance(31L, "ETL_TARGET_BATCH"), instance(32L, "ETL_TARGET_BATCH")), 3L));
        when(taskMapper.selectBatchIds(List.of(10L)))
                .thenReturn(List.of(QualityTaskDO.builder().id(10L).taskName("订单质检").build()));
        // 返回顺序故意与实例顺序不同，验证按 instanceId 匹配而不是按位置
        when(etlBatchRunMapper.selectListByInstanceIds(List.of(30L, 31L, 32L)))
                .thenReturn(List.of(boundRun(32L, 72L, 52L), boundRun(31L, 71L, 51L)));

        PageResult<QualityTaskInstanceRespVO> page = service.getInstancePage(reqVO);

        assertThat(page.getTotal()).isEqualTo(3L);
        assertThat(page.getList()).extracting(QualityTaskInstanceRespVO::getTaskName)
                .containsOnly("订单质检");
        QualityTaskInstanceRespVO manual = page.getList().get(0);
        QualityTaskInstanceRespVO first = page.getList().get(1);
        QualityTaskInstanceRespVO second = page.getList().get(2);
        assertThat(manual.getEtlBatch()).isNull();
        assertThat(first.getEtlBatch()).isNotNull();
        assertThat(first.getEtlBatch().getRecordId()).isEqualTo(71L);
        assertThat(first.getEtlBatch().getTargetBatchId()).isEqualTo(51L);
        assertThat(second.getEtlBatch()).isNotNull();
        assertThat(second.getEtlBatch().getRecordId()).isEqualTo(72L);
        assertThat(second.getEtlBatch().getTargetBatchId()).isEqualTo(52L);
        verify(etlBatchRunMapper, never()).selectByInstanceId(anyLong());
    }

    @Test
    void emptyPageSkipsEtlBatchLookup() {
        QualityTaskInstancePageReqVO reqVO = new QualityTaskInstancePageReqVO();
        when(instanceMapper.selectPage(reqVO)).thenReturn(new PageResult<>(List.of(), 0L));

        PageResult<QualityTaskInstanceRespVO> page = service.getInstancePage(reqVO);

        assertThat(page.getList()).isEmpty();
        verify(etlBatchRunMapper, never()).selectListByInstanceIds(anyCollection());
        verify(taskMapper, never()).selectBatchIds(any());
    }

    private static QualityTaskInstanceDO instance(Long id, String runType) {
        return QualityTaskInstanceDO.builder().id(id).taskId(10L).instanceName("订单质检-" + id)
                .runType(runType).instanceStatus(QualityTaskInstanceDO.STATUS_SUCCESS)
                .runCount(1).totalCount(10L).passCount(9L).failCount(1L).build();
    }

    /** 含全部敏感字段的绑定记录，用于证明服务层不会把它们复制到 VO */
    private static QualityEtlBatchRunDO boundRun(Long instanceId, Long recordId, Long targetBatchId) {
        return QualityEtlBatchRunDO.builder().id(900L + instanceId).qualityTaskId(10L).checkObjectId(3L)
                .inlineReportId(40L).qualityNodeId("quality-1").qualityBatchId("batch-1")
                .targetBatchId(targetBatchId).recordId(recordId)
                .replayOfBatchId(49L).replayOfRecordId(69L).workOrderSubmissionId(88L)
                .runId("run-1").sinkNodeId("sink-1")
                .batchKey("a".repeat(64)).physicalBatchValue("b".repeat(64)).manifestHash("c".repeat(64))
                .manifestJson("{\"tableName\":\"target\"}")
                .rowCount(10L).admittedRowCount(9L).allowWarnings(true)
                .identityFieldsJson("[\"id\"]").identityHash("d".repeat(64))
                .instanceId(instanceId).state("QUEUED").build();
    }

}

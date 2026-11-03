package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceEtlBatchVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstancePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityEtlBatchRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskInstanceDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityEtlBatchRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_TASK_INSTANCE_NOT_EXISTS;

/**
 * 数据治理-数据质量-任务实例 Service 实现（只读）
 *
 * @author Li
 */
@Service
@Validated
public class QualityTaskInstanceServiceImpl implements QualityTaskInstanceService {

    @Resource
    private QualityTaskInstanceMapper instanceMapper;

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityEtlBatchRunMapper etlBatchRunMapper;

    /**
     * 获得任务实例。
     */
    @Override
    public QualityTaskInstanceDO getInstance(Long id) {
        return instanceMapper.selectById(id);
    }

    /**
     * 获得任务实例详情，附带任务名称与 ETL 批次来源（仅 ETL 受控批次触发的实例有值）
     *
     *  @param id 实例编号
     *  @return 实例详情；实例不存在时抛出 {@code QUALITY_TASK_INSTANCE_NOT_EXISTS}
     */
    @Override
    public QualityTaskInstanceRespVO getInstanceDetail(Long id) {
        QualityTaskInstanceDO instance = instanceMapper.selectById(id);
        if (instance == null) {
            throw exception(QUALITY_TASK_INSTANCE_NOT_EXISTS);
        }
        QualityTaskInstanceRespVO vo = BeanUtils.toBean(instance, QualityTaskInstanceRespVO.class);
        if (vo.getTaskId() != null) {
            QualityTaskDO task = taskMapper.selectById(vo.getTaskId());
            vo.setTaskName(task == null ? null : task.getTaskName());
        }
        // ETL 受控批次触发的实例才有绑定；instance_id 唯一，最多一条
        vo.setEtlBatch(toEtlBatchVO(etlBatchRunMapper.selectByInstanceId(instance.getId())));
        return vo;
    }

    /**
     * 获得任务实例分页。
     */
    @Override
    public PageResult<QualityTaskInstanceRespVO> getInstancePage(QualityTaskInstancePageReqVO pageReqVO) {
        PageResult<QualityTaskInstanceDO> pageResult = instanceMapper.selectPage(pageReqVO);
        PageResult<QualityTaskInstanceRespVO> result = BeanUtils.toBean(pageResult, QualityTaskInstanceRespVO.class);
        List<Long> taskIds = result.getList().stream()
                .map(QualityTaskInstanceRespVO::getTaskId).filter(Objects::nonNull).distinct().toList();
        if (!taskIds.isEmpty()) {
            Map<Long, String> taskNameMap = taskMapper.selectBatchIds(taskIds).stream()
                    .collect(Collectors.toMap(QualityTaskDO::getId, QualityTaskDO::getTaskName));
            result.getList().forEach(vo -> vo.setTaskName(taskNameMap.get(vo.getTaskId())));
        }
        fillEtlBatch(result.getList());
        return result;
    }

    /** 按本页实例编号批量查询 ETL 批次绑定并回填，避免逐行查询。 */
    private void fillEtlBatch(List<QualityTaskInstanceRespVO> list) {
        List<Long> instanceIds = list.stream()
                .map(QualityTaskInstanceRespVO::getId).filter(Objects::nonNull).distinct().toList();
        if (instanceIds.isEmpty()) {
            return;
        }
        Map<Long, QualityEtlBatchRunDO> runByInstance = etlBatchRunMapper.selectListByInstanceIds(instanceIds).stream()
                .filter(run -> run.getInstanceId() != null)
                .collect(Collectors.toMap(QualityEtlBatchRunDO::getInstanceId, Function.identity(),
                        (first, ignored) -> first));
        list.forEach(vo -> vo.setEtlBatch(toEtlBatchVO(runByInstance.get(vo.getId()))));
    }

    /**
     * 只复制 VO 中声明的监控字段；manifestJson、identityFieldsJson、identityHash、
     * batchKey、physicalBatchValue、manifestHash 等范围与业务键信息不进入响应。
     */
    private static QualityTaskInstanceEtlBatchVO toEtlBatchVO(QualityEtlBatchRunDO run) {
        if (run == null) {
            return null;
        }
        return BeanUtils.toBean(run, QualityTaskInstanceEtlBatchVO.class);
    }

}

package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevDependencyRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 只读解释执行快照中的依赖，绝不把历史实例自动改绑到新的保存版本。 */
@Service
public class DevDependencyService {

    @Resource
    private DevRecordMapper records;

    /**
     * 获取开发依赖的检查。
     *
     * @param record 运行记录
     * @return 开发依赖的检查结果集合
     */
    public List<DevDependencyRespVO> inspect(DevRecordDO record) {
        if (StrUtil.isBlank(record.getSnapshotJson())) {
            return List.of();
        }
        DevTaskSnapshot snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        List<DevDependencyRespVO> result = new ArrayList<>();
        snapshot.getDependencyVersions().forEach((taskId, versionId) -> {
            DevDependencyRespVO item = new DevDependencyRespVO();
            item.setTaskId(taskId);
            item.setTaskName("任务 #" + taskId);
            item.setRequiredVersionId(versionId);
            item.setBusinessDate(record.getBusinessDate());
            // 与执行器判定一致：任何一个同业务日期、冻结版本的合格实例即可满足依赖。
            DevRecordDO accepted = records.selectAcceptedDependency(taskId, versionId, record.getBusinessDate());
            DevRecordDO observed = accepted != null ? accepted
                    : records.selectLatestDependency(taskId, versionId, record.getBusinessDate());
            if (observed == null) {
                observed = records.selectLatestDependency(taskId, null, record.getBusinessDate());
            }
            item.setSatisfied(accepted != null);
            if (observed == null) {
                item.setReason("同业务日期尚无上游实例，请按所需保存版本补跑");
            } else {
                item.setRecordId(observed.getId());
                item.setTaskName(StrUtil.blankToDefault(observed.getTaskName(), item.getTaskName()));
                item.setActualVersionId(observed.getVersionId());
                item.setStatus(observed.getStatus());
                item.setQualityStatus(observed.getQualityStatus());
                item.setReason(accepted != null ? "执行成功且质量已满足"
                        : !Objects.equals(versionId, observed.getVersionId()) ? "已有实例版本不匹配，须核查冻结版本"
                        : !DevRecordDO.STATUS_SUCCESS.equals(observed.getStatus()) ? "等待上游执行成功"
                        : "等待上游质量通过；失败或未知结果需先处理");
            }
            result.add(item);
        });
        return result;
    }
}

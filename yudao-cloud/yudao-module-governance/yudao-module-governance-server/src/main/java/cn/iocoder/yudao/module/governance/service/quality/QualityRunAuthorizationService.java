package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 提交及真正出队执行均校验当前有效身份，防止排队期间授权已撤销。 */
@Service
public class QualityRunAuthorizationService {

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private AdminUserApi userApi;

    @Resource
    private PermissionApi permissionApi;

    /**
     * 读取并校验必要的质量运行授权上下文或记录。
     */
    public QualityTaskDO require(Long taskId, Long userId) {
        if (taskId == null || userId == null) {
            throw exception(QUALITY_RUN_FORBIDDEN);
        }
        userApi.validateUser(userId);
        QualityTaskDO task = taskMapper.selectById(taskId);
        if (task == null) {
            throw exception(QUALITY_TASK_NOT_EXISTS);
        }
        if (!Objects.equals(task.getTaskStatus(), QualityTaskDO.STATUS_ONLINE)) {
            throw exception(QUALITY_RUN_NOT_READY);
        }
        boolean admin = Boolean.TRUE.equals(
                permissionApi.hasAnyRoles(userId, "super_admin", "tenant_admin").getCheckedData());
        if (!Objects.equals(task.getOwnerUserId(), userId) && !admin) {
            throw exception(QUALITY_RUN_FORBIDDEN);
        }
        return task;
    }
}

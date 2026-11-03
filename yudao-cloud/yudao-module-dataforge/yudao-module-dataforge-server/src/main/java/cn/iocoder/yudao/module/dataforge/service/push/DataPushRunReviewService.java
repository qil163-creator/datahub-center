package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushResolveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskLogRespVO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/** 人工核查策略属于推送领域；平台内可核查，独立权限不继承运行权限。 */
@Service
@Validated
public class DataPushRunReviewService {

    @Resource
    private ExecutionAccessService access;

    @Resource
    private DataPushRunCoordinator coordinator;

    /**
     * 解析数据推送运行复核配置或身份。
     *
     * @param request 请求参数
     * @return 数据推送任务日志响应数据
     */
    public DataPushTaskLogRespVO resolve(@jakarta.validation.constraints.NotNull @Valid DataPushResolveReqVO request) {

        Long actor = SecurityFrameworkUtils.getLoginUserId();
        // 权限 RPC 在本地持锁事务之前；不能依赖前端按钮或仅依赖 Controller。
        access.requireUserPermission(actor, "dataforge:data-push:resolve");
        return BeanUtils.toBean(coordinator.resolve(request, actor), DataPushTaskLogRespVO.class);
    }
}

package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemAssigneeUpdateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillWorkItemTransferReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;

import java.util.List;

/**
 * 在线填报-组织填报实例（工作项）Service 接口
 *
 * @author Li
 */
public interface FillWorkItemService {

    /**
     * 我的填报：当前用户作为有效填报人的全部工作项
     */
    List<FillWorkItemRespVO> getMyWorkItems();

    /**
     * 任务下的全部工作项（发起者进度视角，含当前有效填报人）
     */
    List<FillWorkItemRespVO> getWorkItemListByTask(Long taskId);

    /**
     * 工作项详情（校验查看权限：有效填报人 / 任务创建者 / 报送组织负责人）
     */
    FillWorkItemRespVO getWorkItem(Long id);

    /**
     * 部门负责人只读查看本部门工作项；deptId 为空时取当前用户所在部门
     */
    List<FillWorkItemRespVO> getDeptWorkItems(Long deptId);

    /**
     * 发起者/授权管理员调整填报人（新增/移除），实例必须处于可调整状态且始终保留至少一名有效填报人
     */
    void updateAssignees(FillWorkItemAssigneeUpdateReqVO reqVO);

    /**
     * 填报人本人转派：只能转派自己的职责，默认限同组织内有效人员
     */
    void transfer(FillWorkItemTransferReqVO reqVO);

    /**
     * 催办单个工作项（通知当前有效填报人；1 小时频控内重复催办抛异常）
     */
    void urgeWorkItem(Long id);

    /**
     * 任务级一键催办：对全部未完成（待填报/填报中/整改中）工作项发送催办，频控内的自动跳过
     *
     * @return 实际催办的组织数
     */
    int urgeByTask(Long taskId);

    // ==================== 供草稿/协同/提交等服务复用的校验能力 ====================

    /**
     * 获得工作项（不存在抛异常）
     */
    FillWorkItemDO getWorkItemValidated(Long id);

    /**
     * 校验查看权限：有效填报人 / 任务创建者 / 报送组织负责人
     *
     * 显式传 userId：WebSocket 消息线程没有 SecurityContext，协同层从会话中取用户后调用
     */
    void checkViewPermission(FillWorkItemDO item, Long userId);

    /**
     * 判断查看权限（规则同 {@link #checkViewPermission}），不抛异常
     */
    boolean hasViewPermission(FillWorkItemDO item, Long userId);

    /**
     * 校验编辑权限：有效填报人 + 实例处于可编辑状态 + 硬截止未锁定
     */
    void checkEditPermission(FillWorkItemDO item, Long userId);

}

package cn.iocoder.yudao.module.supply.service.app;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiSaveReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiUpdateReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyAppPageReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyAppSaveReqVO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantOpRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.service.app.vo.SupplyAppCreateResult;

import java.util.List;

/**
 * 数据服务-应用 Service 接口
 *
 * @author Li
 */
public interface SupplyAppService {

    /**
     * 创建应用（自动生成应用秘钥）
     *
     * @return 编号
     */
    SupplyAppCreateResult createApp(SupplyAppSaveReqVO createReqVO);

    /**
     * 更新应用（秘钥不随保存修改）
     */
    void updateApp(SupplyAppSaveReqVO updateReqVO);

    /**
     * 重置应用秘钥
     *
     * @return 新秘钥
     */
    String resetAppSecret(Long id);

    /**
     * 删除应用（级联删除 API 授权）
     */
    void deleteApp(Long id);

    /**
     * 获得应用
     */
    SupplyAppDO getApp(Long id);

    /**
     * 获得应用分页
     */
    PageResult<SupplyAppDO> getAppPage(SupplyAppPageReqVO pageReqVO);

    /**
     * 获得应用的 API 授权列表（含 API 信息与有效状态）
     */
    List<AppApiRespVO> getAppApiList(Long appId);

    /** 按授权编号读取平台内应用授权，供编辑前重新获取当前期限、描述和控制版本。 */
    AppApiRespVO getAppApi(Long id);

    /**
     * 管理端创建独立 ADMIN 授权；不接管市场来源或 v2 聚合。
     *
     * @return 编号
     */
    Long createAppApi(AppApiSaveReqVO createReqVO);

    /** 修改普通独立 ADMIN 的期限和描述；不更换授权身份，整体禁用状态保持原值。 */
    void updateAppApi(AppApiUpdateReqVO reqVO);

    /**
     * 整体禁用应用 API 授权；保留来源和历史回执，不逐来源撤销。
     */
    void deleteAppApi(Long id);

    /** 明确重新启用聚合；版本来自最近一次读取，不恢复已撤销来源。 */
    void enableAppApi(Long id, Long expectedRevision);

    /**
     * 聚合整体禁用开关。两个方向都要求 expectedRevision 等于最近一次读取的聚合版本，
     * 避免旧页面覆盖更新的决定；不改变逐来源生命周期。
     */
    void setGrantControl(Long id, Long expectedRevision, boolean disabled);

    /**
     * 永久关闭管理端 ADMIN 授权（终态，不可通过启用恢复）；只作用于独立 ADMIN 聚合，
     * 市场 v2 聚合必须由来源系统逐来源撤销。关闭后该聚合不再阻止 API 退役。
     */
    void revokeAdminAppApi(Long id, Long expectedRevision);

    /**
     * 旧 RPC 写协议已拒绝：缺少冻结字段和来源生命周期，需升级协议。
     */
    Long grantAppApi(AppApiSaveReqVO reqVO);

    /**
     * 旧 RPC 撤销已拒绝：应用 + API 不能证明待撤销来源。
     */
    void revokeAppApi(Long appId, Long apiId);

    /**
     * 旧 RPC 撤销已拒绝；即使带幂等键也不能推断来源生命周期。
     */
    void revokeAppApi(AppApiSaveReqVO reqVO);

    /**
     * 查询授权交付结果
     */
    SupplyGrantOpRespDTO getGrantOperation(String sourceSystem, String operationId);

    /**
     * 启用/暂停/撤销应用，不影响已落库的授权范围
     */
    void updateAppStatus(Long id, Integer appStatus);

    /**
     * 将当前平台的旧协议 UNKNOWN 标为需要人工复核；不猜测历史结果，不处理 v2 操作。
     */
    int reconcileUnknownGrantOps();

    /**
     * 列出指定用户创建的应用。
     */
    List<SupplyAppDO> listAppsByCreator(Long userId);

}

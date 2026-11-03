package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.*;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;

import java.util.List;

/**
 * 数据服务-API 服务 Service 接口
 *
 * @author Li
 */
public interface DataApiService {

    /**
     * 创建 API 服务
     *
     * @return 编号
     */
    Long createDataApi(DataApiSaveReqVO createReqVO);

    /**
     * 更新 API 服务（上线状态不可修改）
     */
    void updateDataApi(DataApiSaveReqVO updateReqVO);

    /**
     * 上线/下线 API 服务
     */
    void updateDataApiStatus(Long id, Integer status, Integer expectedRowVersion);

    /**
     * 删除 API 服务（上线中或已被应用授权不可删除）
     */
    void deleteDataApi(Long id);

    /**
     * 获得 API 服务
     */
    DataApiRespVO getDataApi(Long id);

    /**
     * 获得 API 服务分页（类目过滤含子级）
     */
    PageResult<DataApiRespVO> getDataApiPage(DataApiPageReqVO pageReqVO);

    /**
     * 获得 API 服务精简列表（接口测试/应用授权下拉用）
     *
     * @param purpose test=试调可选择 grant=可授予 query=查看
     */
    List<DataApiSimpleRespVO> getDataApiSimpleList(Integer status, String purpose);

    /**
     * 读取 API 持久化记录；不存在时返回 {@code null}。
     */
    DataApiDO getDataApiEntity(Long id);

    /** 当前发布快照；不把未发布草稿作为授权证据。 */
    DataApiDO getPublishedDataApiEntity(Long id);

    /**
     * 已上线、单表向导、查询类 API，供资产申请核验绑定。
     */
    List<DataApiDO> listOnlineTableQueryApis(Long databaseId);

    /**
     * 数据源精简列表（向导下拉用，不含连接凭据）
     */
    List<SupplyDatabaseSimpleRespVO> getDatabaseList();

    /**
     * 对外网关基址；未配置时返回空，调用方不得猜测。
     */
    String getPublicBaseUrl();

    /**
     * 数据源下的表名列表（向导下拉用）
     */
    List<String> getTableNameList(Long databaseId);

    /**
     * 数据表列元数据（向导选择请求参数/返回字段用）
     */
    List<TableColumnRespVO> getTableColumns(Long databaseId, String tableName);

    /**
     * 草稿只读预览（配置可未保存）
     */
    DataApiExecuteRespVO testDataApi(DataApiTestReqVO testReqVO, String callerIp);

    /**
     * 在线签名。v1 保持旧协议；v2 使用 HMAC-SM3。
     */
    String sign(DataApiSignReqVO reqVO);

    /**
     * 把当前草稿或在线配置发布为不可变快照并切换生效指针。
     */
    Long publishDataApi(Long id, Integer expectedRowVersion);

    /**
     * 在当前合同范围内回滚到既有快照。
     */
    void rollbackDataApi(Long id, Long releaseId, Integer expectedRowVersion);

    /**
     * 发布历史
     */
    List<DataApiReleaseRespVO> listReleases(Long apiId);

}

package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushConfigDO;

import java.util.List;

public interface DataPushConfigService {

    /**
     * 创建推送配置。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    Long createConfig(DataPushConfigSaveReqVO createReqVO);

    /**
     * 更新推送配置。
     *
     * @param updateReqVO 更新参数
     */
    void updateConfig(DataPushConfigSaveReqVO updateReqVO);

    /**
     * 删除推送配置。
     *
     * @param id 记录编号
     */
    void deleteConfig(Long id);

    /**
     * 获得推送配置。
     *
     * @param id 记录编号
     * @return 数据推送配置响应数据
     */
    DataPushConfigRespVO getConfig(Long id);

    /**
     * 获得推送配置分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 推送配置分页结果
     */
    PageResult<DataPushConfigRespVO> getConfigPage(DataPushConfigPageReqVO pageReqVO);

    /**
     * 获得推送配置精简列表。
     *
     * @return 精简列表
     */
    List<DataPushConfigRespVO> getSimpleList();

    /**
     * 测试推送通道连通性。
     *
     * @param reqVO 请求参数
     */
    void testConnection(DataPushConfigSaveReqVO reqVO);

    /**
     * 复制推送配置。
     *
     * @param id 记录编号
     * @return 复制配置数值
     */
    Long copyConfig(Long id);

    /** 在锁外校验任务配置，并仅从该任务原接收端恢复掩码凭据。 */
    DataPushConfigDO prepareTaskConfig(DataPushConfigSaveReqVO reqVO, Long existingConfigId);

    /** 在任务写事务内保存接收端；共享历史配置复制后再编辑。 */
    Long saveTaskConfig(DataPushConfigDO prepared);

    /** 在任务写事务内锁定兼容调用引用的配置，防止引用计数与编辑竞争。 */
    void lockTaskConfig(DataPushConfigDO expected);

    /** 删除任务后，仅清理该任务原接收端中已无引用的配置。 */
    void deleteTaskConfigIfUnused(Long id);

}

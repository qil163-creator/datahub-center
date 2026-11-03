package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.alert.QualityAlertPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAlertDO;

import java.util.Map;

/**
 * 数据治理-数据质量-告警记录 Service 接口
 *
 * @author Li
 */
public interface QualityAlertService {

    /**
     * 获得告警记录。
     */
    QualityAlertDO getAlert(Long id);

    /**
     * 获得告警记录分页。
     */
    PageResult<QualityAlertDO> getAlertPage(QualityAlertPageReqVO pageReqVO);

    /**
     * 处理告警：状态置为已处理
     */
    void processAlert(Long id);

    /**
     * 删除告警记录。
     */
    void deleteAlert(Long id);

    /**
     * 顶部统计卡：危险/严重/一般/未处理/已处理数量
     */
    Map<String, Long> getAlertSummary();

}

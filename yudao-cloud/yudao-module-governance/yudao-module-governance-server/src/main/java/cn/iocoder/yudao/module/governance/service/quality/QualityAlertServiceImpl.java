package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.alert.QualityAlertPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAlertDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAlertMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_ALERT_NOT_EXISTS;

/**
 * 数据治理-数据质量-告警记录 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class QualityAlertServiceImpl implements QualityAlertService {

    @Resource
    private QualityAlertMapper alertMapper;

    /**
     * 获得告警记录。
     */
    @Override
    public QualityAlertDO getAlert(Long id) {
        return alertMapper.selectById(id);
    }

    /**
     * 获得告警记录分页。
     */
    @Override
    public PageResult<QualityAlertDO> getAlertPage(QualityAlertPageReqVO pageReqVO) {
        return alertMapper.selectPage(pageReqVO);
    }

    /**
     * 处理告警：状态置为已处理
     */
    @Override
    public void processAlert(Long id) {
        validateExists(id);
        alertMapper.updateById(QualityAlertDO.builder()
                .id(id).alertStatus(QualityAlertDO.STATUS_PROCESSED).build());
    }

    /**
     * 删除告警记录。
     */
    @Override
    public void deleteAlert(Long id) {
        validateExists(id);
        alertMapper.deleteById(id);
    }

    /**
     * 顶部统计卡：危险/严重/一般/未处理/已处理数量
     */
    @Override
    public Map<String, Long> getAlertSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("dangerCount", alertMapper.selectCountByLevel(QualityAlertDO.LEVEL_DANGER));
        summary.put("seriousCount", alertMapper.selectCountByLevel(QualityAlertDO.LEVEL_SERIOUS));
        summary.put("generalCount", alertMapper.selectCountByLevel(QualityAlertDO.LEVEL_GENERAL));
        summary.put("unprocessedCount", alertMapper.selectCountByStatus(QualityAlertDO.STATUS_UNPROCESSED));
        summary.put("processedCount", alertMapper.selectCountByStatus(QualityAlertDO.STATUS_PROCESSED));
        return summary;
    }

    /**
     * 读取并校验质量告警记录存在，不存在时抛出业务异常。
     */
    private void validateExists(Long id) {
        if (id == null || alertMapper.selectById(id) == null) {
            throw exception(QUALITY_ALERT_NOT_EXISTS);
        }
    }

}

package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.alert.QualityAlertPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAlertDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-告警记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityAlertMapper extends BaseMapperX<QualityAlertDO> {

    /**
     * 查询质量告警分页。
     */
    default PageResult<QualityAlertDO> selectPage(QualityAlertPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityAlertDO>()
                .likeIfPresent(QualityAlertDO::getAlertTitle, reqVO.getAlertTitle())
                .likeIfPresent(QualityAlertDO::getTaskName, reqVO.getTaskName())
                .eqIfPresent(QualityAlertDO::getAlertLevel, reqVO.getAlertLevel())
                .eqIfPresent(QualityAlertDO::getAlertStatus, reqVO.getAlertStatus())
                .orderByDesc(QualityAlertDO::getId));
    }

    /**
     * 按等级查询质量告警数量。
     */
    default Long selectCountByLevel(String alertLevel) {
        return selectCount(new LambdaQueryWrapperX<QualityAlertDO>()
                .eq(QualityAlertDO::getAlertLevel, alertLevel));
    }

    /**
     * 按状态查询质量告警数量。
     */
    default Long selectCountByStatus(String alertStatus) {
        return selectCount(new LambdaQueryWrapperX<QualityAlertDO>()
                .eq(QualityAlertDO::getAlertStatus, alertStatus));
    }

}

package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;

import java.util.List;

/**
 * 数据治理-数据质量-检查对象 Service 接口
 *
 * @author Li
 */
public interface QualityCheckObjectService {

    /**
     * 创建检查对象。
     */
    Long createCheckObject(QualityCheckObjectSaveReqVO createReqVO);

    /**
     * 更新检查对象。
     */
    void updateCheckObject(QualityCheckObjectSaveReqVO updateReqVO);

    /**
     * 删除检查对象。
     */
    void deleteCheckObject(Long id);

    /**
     * 获得检查对象。
     */
    QualityCheckObjectDO getCheckObject(Long id);

    /**
     * 获得检查对象分页。
     */
    PageResult<QualityCheckObjectDO> getCheckObjectPage(QualityCheckObjectPageReqVO pageReqVO);

    /**
     * 获得检查对象精简列表。
     */
    List<QualityCheckObjectDO> getCheckObjectSimpleList();

}

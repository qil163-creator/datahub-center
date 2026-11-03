package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueDataPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueDataRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityIssueDataDO;

import java.util.List;
import java.util.Map;

/**
 * 数据治理-数据质量-问题数据 Service 接口
 *
 * @author Li
 */
public interface QualityIssueDataService {

    /**
     * 获得问题数据。
     */
    QualityIssueDataDO getIssueData(Long id);

    /**
     * 获得问题数据分页。
     */
    PageResult<QualityIssueDataRespVO> getIssueDataPage(QualityIssueDataPageReqVO pageReqVO);

    /**
     * 更新处理状态
     */
    void updateIssueStatus(Long id, String issueStatus);

    /**
     * 删除问题数据。
     */
    void deleteIssueData(Long id);

    /**
     * 查看问题明细：按定位 SQL 实时回源查询（源库已修复的行不会再出现）
     *
     * @return 问题行明细（每行为「列名 → 值」）
     */
    List<Map<String, Object>> getIssueDetail(Long id, Integer limit);

}

package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefGenerateReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityReportLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityReportLogDO;

/**
 * 数据治理-数据质量-质检简报 Service 接口
 *
 * @author Li
 */
public interface QualityBriefService {

    /**
     * 生成简报：按模板段落渲染变量（数据来自机构评分），生成草稿简报并写报告记录
     *
     * @return 简报编号
     */
    Long generateBrief(QualityBriefGenerateReqVO reqVO);

    /**
     * 发布简报
     */
    void publishBrief(Long id);

    /**
     * 删除简报。
     */
    void deleteBrief(Long id);

    /**
     * 获得简报详情（含渲染后内容，供预览）。
     */
    QualityBriefRespVO getBrief(Long id);

    /**
     * 获得简报分页。
     */
    PageResult<QualityBriefRespVO> getBriefPage(QualityBriefPageReqVO pageReqVO);

    /**
     * 获得报告生成记录分页。
     */
    PageResult<QualityReportLogDO> getReportLogPage(QualityReportLogPageReqVO pageReqVO);

    /**
     * 导出简报：按渲染后的简报内容生成 Word（.doc）或 Excel（.xls）文件流
     *
     * @param format WORD 或 EXCEL 格式
     */
    void exportBrief(Long id, String format, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException;

}

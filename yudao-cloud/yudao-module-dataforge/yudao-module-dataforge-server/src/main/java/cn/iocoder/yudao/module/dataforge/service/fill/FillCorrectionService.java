package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionCreateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillCorrectionPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillCorrectionDO;

/**
 * 在线填报-数据更正 Service 接口
 *
 * 最小闭环（V1.0 §21.4）：对已合并实例发起更正 → 实例回到整改中（草稿即上次快照内容）→
 * 填报人修改后重新提交（submissionType=更正）→ 新 BPM 审批 → 通过后按 UPSERT/REPLACE_SCOPE 幂等覆盖。
 * 原快照、原 BPM 历史与原合并批次全部保留，正式数据对普通填报人始终只读
 *
 * @author Li
 */
public interface FillCorrectionService {

    /**
     * 对已合并的提交快照发起数据更正
     *
     * @return 更正实例编号
     */
    Long createCorrection(FillCorrectionCreateReqVO reqVO);

    /**
     * 取消更正（仅草拟中、尚未重新提交时）：实例状态恢复为已合并
     */
    void cancelCorrection(Long id);

    /**
     * 更正分页
     */
    PageResult<FillCorrectionDO> getCorrectionPage(FillCorrectionPageReqVO pageReqVO);

    /**
     * 是否存在草拟中的更正（提交类型需强制为"更正"）
     */
    boolean hasActiveCorrection(Long workItemId);

    /**
     * 提交钩子：工作项存在草拟中的更正时绑定新快照并转审批中
     *
     * @return 是否完成绑定
     */
    boolean bindSubmissionIfCorrecting(Long workItemId, Long submissionId);

    /**
     * 合并成功钩子：更正快照合并完成后闭环更正实例
     */
    void completeBySubmissionId(Long submissionId);

    /**
     * 结束填报时放弃进行中的更正：工作项从整改中恢复为已合并（原已合并数据保持不变），更正实例置为已取消。
     * 调用方须处于事务内；更正已重新提交（工作项不在整改中）时不处理。
     *
     * @param workItemId 工作项编号
     * @return true = 已恢复为已合并；false = 没有可放弃的更正
     */
    boolean abandonForTaskClose(Long workItemId);

}

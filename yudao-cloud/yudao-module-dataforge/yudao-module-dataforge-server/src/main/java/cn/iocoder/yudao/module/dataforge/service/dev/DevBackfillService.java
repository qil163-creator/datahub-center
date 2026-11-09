package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillCreateReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillRespVO;

/**
 * 数据开发-补数据（历史业务日期区间重跑）Service。
 */
public interface DevBackfillService {

    /**
     * 发起补数据批次；同一请求标识重复提交返回同一批次。
     *
     * @param reqVO 请求参数
     * @return 批次编号
     */
    Long createBackfill(DevBackfillCreateReqVO reqVO);

    /**
     * 请求取消批次：未开始的日期立即取消，已提交的运行发出停止请求，引擎确认终态后批次转为已取消。
     *
     * @param id 批次编号
     */
    void cancelBackfill(Long id);

    /**
     * 批次详情（含日期明细）。
     *
     * @param id 批次编号
     * @return 批次详情
     */
    DevBackfillRespVO getBackfill(Long id);

    /**
     * 批次分页（不含日期明细）。
     *
     * @param reqVO 分页参数
     * @return 分页结果
     */
    PageResult<DevBackfillRespVO> getBackfillPage(DevBackfillPageReqVO reqVO);

    /**
     * 推进全部进行中的批次：同步已提交日期的运行终态，任务空闲时登记下一个日期。由 {@code devTaskJob} 周期调用。
     */
    void advance();

    /**
     * 推进单个批次。
     *
     * @param id 批次编号
     */
    void advance(Long id);

}

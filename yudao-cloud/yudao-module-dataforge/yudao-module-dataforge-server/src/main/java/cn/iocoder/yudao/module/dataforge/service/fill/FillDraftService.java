package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDraftSnapshotRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;

import java.util.List;

/**
 * 在线填报-草稿服务接口（多人共享草稿的唯一读写入口，协同层的地基）
 *
 * @author Li
 */
public interface FillDraftService {

    /**
     * 草稿全量快照：工作台进入/断线重连时拉取（含模板 schema、全部行与当前 serverVersion）
     */
    FillDraftSnapshotRespVO getSnapshot(Long workItemId);

    /**
     * 增量拉取操作：afterVersion 之后的操作，按 serverVersion 升序（协同补齐与重放）
     */
    List<FillOperationRespVO> getOperations(Long workItemId, Long afterVersion, Integer limit);

    /**
     * 应用一批领域操作（SET_CELL/ADD_ROW/REMOVE_ROW）
     *
     * 单事务原子完成：状态与 expectedVersion 的工作项 CAS（同时串行化并发批次）、
     * 草稿行 rowVersion CAS、操作日志落盘；clientOpId 唯一约束保证网络重试不重复执行；
     * 事务提交后向房间广播"有新操作"提示
     */
    FillOperationApplyRespVO applyOperations(Long workItemId, FillOperationApplyReqVO reqVO);

    /**
     * 系统带入草稿（上期数据带入）：把来源提交快照的行复制为本工作项的草稿行，并记录 PREFILL 操作日志。
     *
     * 只复制当前模板版本中可编辑、未隐藏、非主键的字段；工作项已被编辑（serverVersion 已变化）或
     * 已不在可编辑状态时跳过。调用方须处于事务内（任务生成事务）。
     *
     * @param item 目标工作项
     * @param sourceSubmissionId 来源提交快照编号
     * @param operatorUserId 记入操作日志的操作人
     * @return 带入的行数，0 表示跳过
     */
    int prefillFromSubmission(FillWorkItemDO item, Long sourceSubmissionId, Long operatorUserId);

}

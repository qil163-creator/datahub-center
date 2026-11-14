package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillReuseReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillReuseSourceRespVO;
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
    /**
     * 可复用的历史数据：同一计划、同一报送组织更早期次中审批通过且非零上报的提交（最近 24 期）。
     *
     * @param workItemId 工作项编号
     * @return 可复用来源
     */
    List<FillReuseSourceRespVO> getReuseSources(Long workItemId);

    /**
     * 复用历史数据：把选中期次的数据行经草稿操作通道写入（追加或整体替换），只带入当前模板中
     * 可编辑、未隐藏、非主键的字段。
     *
     * @param workItemId 工作项编号
     * @param reqVO 请求参数
     * @return 操作应用结果
     */
    FillOperationApplyRespVO reuse(Long workItemId,
            FillReuseReqVO reqVO);

    int prefillFromSubmission(FillWorkItemDO item, Long sourceSubmissionId, Long operatorUserId);

    /**
     * 汇总生成的一行。
     *
     * @param data 行数据
     * @param sourceKind 行来源：1-下级明细 2-下级分组合计
     * @param sourceOrgId 数据最初的报送组织（分组合计行为汇总单位）
     * @param sourceWorkItemId 数据原始录入的实例（分组合计行为汇总单位实例）
     * @param sourceSubmissionId 直接下级审核通过的提交（分组合计行为空）
     * @param sourceRowId 数据原始录入的行（分组合计行为分组键摘要）
     */
    record RollupRow(java.util.Map<String, Object> data, Integer sourceKind, Long sourceOrgId,
                     Long sourceWorkItemId, Long sourceSubmissionId, String sourceRowId) {
    }

    /**
     * 汇总写入：替换此前由汇总生成的行，本单位录入的行不受影响。调用方须处于事务中。
     *
     * @param item 汇总单位的工作项
     * @param rows 汇总生成的行
     * @param expectedVersion 客户端已同步到的服务端版本
     * @param operatorUserId 操作人
     * @return 汇总后的草稿行数
     */
    int replaceRollupRows(FillWorkItemDO item, java.util.List<RollupRow> rows, long expectedVersion,
                          Long operatorUserId);

}

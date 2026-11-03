package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillDraftSnapshotRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationApplyRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillOperationRespVO;

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

}

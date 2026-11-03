package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.*;

import java.util.List;

/**
 * 数据治理-元数据对象浏览 Service（最新元数据：树/表/字段/快照/变更/业务属性维护/删除确认）
 *
 * @author Li
 */
public interface MetaObjectService {

    /**
     * 元数据树：来源系统 → 数据源 → 库（含待确认删除对象，前端打警示标）
     */
    List<MetaObjectTreeNodeVO> getObjectTree();

    /**
     * 表元数据分页（填充库名/类型/来源系统冗余展示信息）
     */
    PageResult<MetaTableRespVO> getTablePage(MetaTablePageReqVO pageReqVO);

    /**
     * 表元数据详情
     */
    MetaTableRespVO getTableDetail(Long id);

    /**
     * 维护表的业务/管理属性（全量覆盖业务属性，逐属性记录手工变更明细；采集属性不可改）
     */
    void updateTableBiz(MetaTableUpdateBizReqVO reqVO);

    /**
     * 确认删除表（仅待确认删除状态可确认；记录与快照永久保留）
     */
    void confirmDeleteTable(Long id);

    /**
     * 删除表。
     */
    void deleteTable(MetaTableStateReqVO request);

    /**
     * 确认删除库（级联其未删除的表与字段；记录与快照永久保留）
     */
    void confirmDeleteDb(Long id);

    /**
     * 发布表：校验必填治理属性（注释、业务负责人），独立更新发布状态并留痕；
     * 目录候选/资产/共享一律引用定版版本，采集流程不感知定版
     */
    void publishTable(Long id, Integer stateRevision);

    /**
     * 取消发布：保留定版指针并留痕；已编目资产不自动删除
     */
    void unpublishTable(Long id, Integer stateRevision);

    /**
     * 库级批量发布：发布本库全部「正常状态且校验通过且未发布」的表；
     * 已发布表跳过，所有定版指针保持不变
     */
    MetaPublishBatchRespVO publishDb(Long dbId);

    /**
     * 字段列表（不含已删除字段）
     */
    List<MetaColumnRespVO> getColumnList(Long tableId);

    /** 当前字段详情，包含已确认落标的固定标准版本信息。 */
    MetaColumnRespVO getColumnDetail(Long id);

    /**
     * 维护字段的业务/管理属性（全量覆盖业务属性，逐属性记录手工变更明细）
     */
    void updateColumnBiz(MetaColumnUpdateBizReqVO reqVO);

    /**
     * 指定结构版本定版，确认时业务属性另存只读镜像
     */
    void finalizeTable(MetaTableFinalizeReqVO request);

    /**
     * 取消定版表。
     */
    void cancelFinalizeTable(MetaTableStateReqVO request);

    /**
     * 读取已定版表分页。
     */
    PageResult<MetaTableRespVO> getFinalizedTablePage(MetaTablePageReqVO request);

    /**
     * 读取已定版表详情。
     */
    MetaFinalizedDetailRespVO getFinalizedTableDetail(Long tableId);

    /**
     * 比对元数据对象的快照。
     */
    MetaSnapshotCompareRespVO compareSnapshots(Long tableId, Long fromSnapshotId, Long toSnapshotId);

    /**
     * 读取快照列表。
     */
    List<MetaSnapshotRespVO> getSnapshotList(Long tableId);

    /**
     * 快照详情（含全结构快照体）
     */
    MetaSnapshotRespVO getSnapshot(Long id);

    /**
     * 变更明细分页
     */
    PageResult<MetaChangeRespVO> getChangePage(MetaChangePageReqVO pageReqVO);

    /**
     * 元数据检索：关键字覆盖表名/字段名/业务名称/注释，支持分层/主题域/发布/认责过滤
     */
    PageResult<MetaSearchHitVO> search(MetaSearchReqVO reqVO);

    /**
     * 简版概览：对象计数、覆盖率、近 30 天变更趋势与最近失败采集
     */
    MetaOverviewRespVO getOverview();

    /**
     * 批量认责：仅覆盖传入的负责人字段，逐表记录手工变更
     */
    void batchAssignOwner(MetaBatchOwnerReqVO reqVO);

}

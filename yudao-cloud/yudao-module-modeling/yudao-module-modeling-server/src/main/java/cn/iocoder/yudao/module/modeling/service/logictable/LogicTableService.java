package cn.iocoder.yudao.module.modeling.service.logictable;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo.*;

import java.util.Collection;
import java.util.List;

/**
 * 逻辑表（贴源/维度/事实/汇总/ADS 应用表）列表工作台 Service
 */
public interface LogicTableService {

    /**
     * 获得逻辑表分页。
     *
     * @param reqVO 请求参数
     * @return 逻辑表分页结果
     */
    PageResult<LogicTableRespVO> getLogicTablePage(LogicTablePageReqVO reqVO);

    /** 左侧类目树：贴源逻辑表按来源系统，其余按数据板块/主题域。 */
    List<LogicTableCatNodeRespVO> getCatTree(String tableType);

    /**
     * 获得启用的来源系统。
     *
     * @return 来源系统列表
     */
    List<SourceSystemSimpleRespVO> getSourceSystemList();

    /**
     * 启用或停用逻辑表。
     *
     * @param id 记录编号
     * @param enabled 启用状态
     */
    void updateEnabled(Long id, Integer enabled);

    /**
     * 批量删除前检查可删除的逻辑表。
     *
     * @param ids 记录编号集合
     * @return 逻辑表删除检查响应数据
     */
    LogicTableDeleteCheckRespVO checkDelete(Collection<Long> ids);

    /** 删除逻辑表；启用中的逻辑表不可删除。 */
    void deleteLogicTables(Collection<Long> ids);

}

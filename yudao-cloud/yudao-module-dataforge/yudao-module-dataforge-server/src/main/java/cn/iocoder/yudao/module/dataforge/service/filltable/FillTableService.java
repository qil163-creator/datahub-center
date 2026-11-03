package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTablePageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableSaveReqVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * 数据填报-填报表（模板）Service 接口
 *
 * 只承担模板定义管理与目标物理表的创建；数据录入一律走在线填报审批链路，旧直写方法已移除
 *
 * @author Li
 */
public interface FillTableService {

    /**
     * 创建填报表。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    Long createFillTable(@Valid FillTableSaveReqVO createReqVO);

    /**
     * 更新填报表（已发布需先取消发布）。
     *
     * @param updateReqVO 更新参数
     */
    void updateFillTable(@Valid FillTableSaveReqVO updateReqVO);

    /**
     * 删除填报表（级联删除字段配置）。
     *
     * @param id 记录编号
     */
    void deleteFillTable(Long id);

    /**
     * 获得填报表。
     *
     * @param id 记录编号
     * @return 填报表响应数据
     */
    FillTableRespVO getFillTable(Long id);

    /**
     * 获得填报表分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 填报表分页结果
     */
    PageResult<FillTableRespVO> getFillTablePage(FillTablePageReqVO pageReqVO);

    /**
     * 发布：按字段配置在目标数据源建物理表
     */
    void releaseFillTable(Long id);

    /**
     * 取消发布：仅将模板置回未发布，不删除物理表及其数据；
     * 物理表退役属于独立的高权限操作，需检查活动任务、历史合并与下游依赖
     */
    void cancelFillTable(Long id);

    /**
     * 下载 Excel 模板（含必填标记与选择值下拉校验）
     */
    void downloadImportTemplate(Long id, HttpServletResponse response);

}

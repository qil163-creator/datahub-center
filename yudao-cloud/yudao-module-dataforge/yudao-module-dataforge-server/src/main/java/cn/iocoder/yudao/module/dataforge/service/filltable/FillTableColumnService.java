package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnSaveReqVO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据填报-填报表字段 Service 接口
 *
 * @author Li
 */
public interface FillTableColumnService {

    /**
     * 创建填报表字段。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    Long createColumn(@Valid FillTableColumnSaveReqVO createReqVO);

    /**
     * 更新填报表字段。
     *
     * @param updateReqVO 更新参数
     */
    void updateColumn(@Valid FillTableColumnSaveReqVO updateReqVO);

    /**
     * 删除填报表字段。
     *
     * @param id 记录编号
     */
    void deleteColumn(Long id);

    /**
     * 获得填报表的字段列表（按排序）。
     *
     * @param tableId 表编号
     * @return 填报表的字段列表（按排序）结果集合
     */
    List<FillTableColumnRespVO> getColumnListByTableId(Long tableId);

}

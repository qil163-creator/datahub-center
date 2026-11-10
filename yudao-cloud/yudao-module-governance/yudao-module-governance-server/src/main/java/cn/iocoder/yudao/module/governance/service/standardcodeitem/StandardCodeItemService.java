package cn.iocoder.yudao.module.governance.service.standardcodeitem;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据标准--码值 Service 接口
 *
 * @author Li
 */
public interface StandardCodeItemService {

    /**
     * 创建码值
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Integer createStandardCodeItem(@Valid StandardCodeItemSaveReqVO createReqVO);

    /**
     * 更新码值
     *
     * @param updateReqVO 更新信息
     */
    void updateStandardCodeItem(@Valid StandardCodeItemSaveReqVO updateReqVO);

    /**
     * 删除码值
     *
     * @param id 编号
     */
    void deleteStandardCodeItem(Integer id);

    /**
     * 批量删除码值
     *
     * @param ids 编号
     */
    void deleteStandardCodeItemListByIds(List<Integer> ids);

    /**
     * 获得码值
     *
     * @param id 编号
     * @return 码值
     */
    StandardCodeItemDO getStandardCodeItem(Integer id);

    /**
     * 获得码值分页
     *
     * @param pageReqVO 分页查询
     * @return 码值分页
     */
    PageResult<StandardCodeItemDO> getStandardCodeItemPage(StandardCodeItemPageReqVO pageReqVO);


}

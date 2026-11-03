package cn.iocoder.yudao.module.governance.service.level;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据分级 Service 接口
 *
 * @author Li
 */
public interface SafetyLevelService {

    /**
     * 创建数据分级
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createLevel(@Valid SafetyLevelSaveReqVO createReqVO);

    /**
     * 更新数据分级
     *
     * @param updateReqVO 更新信息
     */
    void updateLevel(@Valid SafetyLevelSaveReqVO updateReqVO);

    /**
     * 删除数据分级
     *
     * @param id 编号
     */
    void deleteLevel(Long id);

    /**
     * 批量删除数据分级
     *
     * @param ids 编号
     */
    void deleteLevelListByIds(List<Long> ids);

    /**
     * 获得数据分级
     *
     * @param id 编号
     * @return 数据分级
     */
    SafetyLevelDO getLevel(Long id);

    /**
     * 获得数据分级分页
     *
     * @param pageReqVO 分页查询
     * @return 数据分级分页
     */
    PageResult<SafetyLevelDO> getLevelPage(SafetyLevelPageReqVO pageReqVO);

}

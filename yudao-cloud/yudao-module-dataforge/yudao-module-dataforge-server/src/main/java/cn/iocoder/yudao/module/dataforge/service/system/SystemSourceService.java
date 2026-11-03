package cn.iocoder.yudao.module.dataforge.service.system;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.system.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.systemsource.SystemSourceDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 来源系统 Service 接口
 *
 * @author Li
 */
public interface SystemSourceService {

    /**
     * 创建来源系统
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createSystemSource(@Valid SystemSourceSaveReqVO createReqVO);

    /**
     * 更新来源系统
     *
     * @param updateReqVO 更新信息
     */
    void updateSystemSource(@Valid SystemSourceSaveReqVO updateReqVO);

    /**
     * 删除来源系统
     *
     * @param id 编号
     */
    void deleteSystemSource(Long id);

    /**
     * 批量删除来源系统
     *
     * @param ids 编号
     */
    void deleteSystemSourceListByIds(List<Long> ids);

    /**
     * 获得来源系统
     *
     * @param id 编号
     * @return 来源系统
     */
    SystemSourceDO getSystemSource(Long id);

    /**
     * 获得来源系统分页
     *
     * @param pageReqVO 分页查询
     * @return 来源系统分页
     */
    PageResult<SystemSourceDO> getSystemSourcePage(SystemSourcePageReqVO pageReqVO);

}

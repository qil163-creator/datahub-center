package cn.iocoder.yudao.module.dataforge.service.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.driver.DatasourceDriverDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据源驱动管理 Service 接口
 *
 * @author Li
 */
public interface DatasourceDriverService {

    /**
     * 创建数据源驱动管理
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createDatasourceDriver(@Valid DatasourceDriverSaveReqVO createReqVO);

    /**
     * 更新数据源驱动管理
     *
     * @param updateReqVO 更新信息
     */
    void updateDatasourceDriver(@Valid DatasourceDriverSaveReqVO updateReqVO);

    /**
     * 删除数据源驱动管理
     *
     * @param id 编号
     */
    void deleteDatasourceDriver(Long id);

    /**
     * 批量删除数据源驱动管理
     *
     * @param ids 编号
     */
    void deleteDatasourceDriverListByIds(List<Long> ids);

    /**
     * 获得数据源驱动管理
     *
     * @param id 编号
     * @return 数据源驱动管理
     */
    DatasourceDriverDO getDatasourceDriver(Long id);

    /**
     * 获得数据源驱动管理分页
     *
     * @param pageReqVO 分页查询
     * @return 数据源驱动管理分页
     */
    PageResult<DatasourceDriverDO> getDatasourceDriverPage(DatasourceDriverPageReqVO pageReqVO);

}

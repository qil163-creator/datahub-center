package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据库类型定义 Service 接口
 *
 * @author Li
 */
public interface DatabaseTypeService {

    /**
     * 创建数据库类型定义
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createDatabaseType(@Valid DatabaseTypeSaveReqVO createReqVO);

    /**
     * 更新数据库类型定义
     *
     * @param updateReqVO 更新信息
     */
    void updateDatabaseType(@Valid DatabaseTypeSaveReqVO updateReqVO);

    /**
     * 更新数据库类型状态。
     *
     * @param request 状态更新请求
     */
    void updateDatabaseTypeStatus(@Valid DatabaseTypeStatusReqVO request);

    /**
     * 删除数据库类型定义
     *
     * @param id 编号
     */
    void deleteDatabaseType(Long id);

    /**
     * 批量删除数据库类型定义
     *
     * @param ids 编号
     */
    void deleteDatabaseTypeListByIds(List<Long> ids);

    /**
     * 获得数据库类型定义
     *
     * @param id 编号
     * @return 数据库类型定义
     */
    DatabaseTypeDO getDatabaseType(Long id);

    /**
     * 获得数据库类型定义分页
     *
     * @param pageReqVO 分页查询
     * @return 数据库类型定义分页
     */
    PageResult<DatabaseTypeDO> getDatabaseTypePage(DatabaseTypePageReqVO pageReqVO);

    /**
     * 获得数据库类型定义列表
     *
     * @param listReqVO 列表查询
     * @return 数据库类型定义列表
     */
    List<DatabaseTypeDO> getDatabaseTypeList(DatabaseTypeListReqVO listReqVO);

}

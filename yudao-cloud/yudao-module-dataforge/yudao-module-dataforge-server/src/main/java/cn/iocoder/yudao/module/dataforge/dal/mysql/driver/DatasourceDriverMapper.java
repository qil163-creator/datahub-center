package cn.iocoder.yudao.module.dataforge.dal.mysql.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.driver.DatasourceDriverDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据源驱动管理 Mapper
 *
 * @author Li
 */
@Mapper
public interface DatasourceDriverMapper extends BaseMapperX<DatasourceDriverDO> {

    /**
     * 按查询条件分页读取数据源驱动。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据源驱动结果
     */
    default PageResult<DatasourceDriverDO> selectPage(DatasourceDriverPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DatasourceDriverDO>()
                .likeIfPresent(DatasourceDriverDO::getDriverName, reqVO.getDriverName())
                .eqIfPresent(DatasourceDriverDO::getProductType, reqVO.getProductType())
                .eqIfPresent(DatasourceDriverDO::getDriverSource, reqVO.getDriverSource())
                .eqIfPresent(DatasourceDriverDO::getStatus, reqVO.getStatus())
                .orderByDesc(DatasourceDriverDO::getId));
    }

}
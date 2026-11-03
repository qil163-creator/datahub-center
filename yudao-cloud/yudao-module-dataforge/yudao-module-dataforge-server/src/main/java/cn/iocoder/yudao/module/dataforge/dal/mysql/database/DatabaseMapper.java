package cn.iocoder.yudao.module.dataforge.dal.mysql.database;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 数据源中心--数据库信息 Mapper
 *
 * @author Li
 */
@Mapper
public interface DatabaseMapper extends BaseMapperX<DatabaseDO> {

    /** 使用平台数据库时钟排序测试，避免多个服务实例的时钟差导致旧结果覆盖。 */
    @Select("SELECT clock_timestamp()::timestamp")
    LocalDateTime selectDiagnosticStartTime();

    /**
     * 按查询条件分页读取数据源。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据源结果
     */
    default PageResult<DatabaseDO> selectPage(DatabasePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DatabaseDO>()
                .likeIfPresent(DatabaseDO::getName, reqVO.getName())
                .eqIfPresent(DatabaseDO::getStatus, reqVO.getStatus())
                .eqIfPresent(DatabaseDO::getUsageType, reqVO.getUsageType())
                .orderByDesc(DatabaseDO::getId));
    }

}

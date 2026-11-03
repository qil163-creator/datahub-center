package cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据库类型定义 Mapper
 *
 * @author Li
 */
@Mapper
public interface DatabaseTypeMapper extends BaseMapperX<DatabaseTypeDO> {

    /**
     * 按查询条件分页读取数据库类型。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据库类型结果
     */
    default PageResult<DatabaseTypeDO> selectPage(DatabaseTypePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DatabaseTypeDO>()
                .eqIfPresent(DatabaseTypeDO::getTypeCode, reqVO.getTypeCode())
                .likeIfPresent(DatabaseTypeDO::getTypeName, reqVO.getTypeName())
                .eqIfPresent(DatabaseTypeDO::getCategoryCode, reqVO.getCategoryCode())
                .eqIfPresent(DatabaseTypeDO::getProtocolType, reqVO.getProtocolType())
                .eqIfPresent(DatabaseTypeDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(DatabaseTypeDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(DatabaseTypeDO::getId));
    }

    /**
     * 按查询条件读取数据库类型列表。
     *
     * @param reqVO 请求参数
     * @return 按查询条件读取数据库类型列表结果集合
     */
    default List<DatabaseTypeDO> selectList(DatabaseTypeListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<DatabaseTypeDO>()
                .likeIfPresent(DatabaseTypeDO::getTypeName, reqVO.getTypeName())
                .eqIfPresent(DatabaseTypeDO::getCategoryCode, reqVO.getCategoryCode())
                .eqIfPresent(DatabaseTypeDO::getStatus, reqVO.getStatus())
                .orderByAsc(DatabaseTypeDO::getSort)
                .orderByAsc(DatabaseTypeDO::getId));
    }

}

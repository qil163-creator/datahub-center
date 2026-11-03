package cn.iocoder.yudao.module.dataforge.dal.mysql.filltable;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTablePageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据填报-填报表定义 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillTableMapper extends BaseMapperX<FillTableDO> {

    /**
     * 按查询条件分页读取填报表。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取填报表结果
     */
    default PageResult<FillTableDO> selectPage(FillTablePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FillTableDO>()
                .likeIfPresent(FillTableDO::getTableEngName, reqVO.getTableEngName())
                .likeIfPresent(FillTableDO::getTableCnName, reqVO.getTableCnName())
                .eqIfPresent(FillTableDO::getStatus, reqVO.getStatus())
                .eqIfPresent(FillTableDO::getOrgId, reqVO.getOrgId())
                .orderByDesc(FillTableDO::getId));
    }

    /**
     * 按英文名称查询填报表。
     *
     * @param tableEngName 表英文名称
     * @return 填报表
     */
    default FillTableDO selectByEngName(String tableEngName) {
        return selectOne(FillTableDO::getTableEngName, tableEngName);
    }

}

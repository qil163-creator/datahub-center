package cn.iocoder.yudao.module.dataforge.dal.mysql.push;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushConfigPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushConfigDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataPushConfigMapper extends BaseMapperX<DataPushConfigDO> {

    /**
     * 按查询条件分页读取数据推送配置。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据推送配置结果
     */
    default PageResult<DataPushConfigDO> selectPage(DataPushConfigPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataPushConfigDO>()
                .likeIfPresent(DataPushConfigDO::getName, reqVO.getName())
                .eqIfPresent(DataPushConfigDO::getChannelType, reqVO.getChannelType())
                .eqIfPresent(DataPushConfigDO::getOrgId, reqVO.getOrgId())
                .orderByDesc(DataPushConfigDO::getId));
    }

}

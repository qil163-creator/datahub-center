package cn.iocoder.yudao.module.supply.dal.mysql.apilog;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogPageReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;

/**
 * 数据服务-API 调用记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface ApiLogMapper extends BaseMapperX<ApiLogDO> {

    /**
     * 按查询条件分页读取API日志。
     *
     * @param reqVO 请求参数
     * @param categoryIds 分类编号集合
     * @return 按查询条件分页读取API日志结果
     */
    default PageResult<ApiLogDO> selectPage(ApiLogPageReqVO reqVO, Collection<Long> categoryIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ApiLogDO>()
                .likeIfPresent(ApiLogDO::getApiName, reqVO.getApiName())
                .eqIfPresent(ApiLogDO::getStatus, reqVO.getStatus())
                .eqIfPresent(ApiLogDO::getRejectStage, reqVO.getRejectStage())
                .eqIfPresent(ApiLogDO::getTraceId, reqVO.getTraceId())
                .inIfPresent(ApiLogDO::getCategoryId, categoryIds)
                .betweenIfPresent(ApiLogDO::getCallTime, reqVO.getCallTime())
                .orderByDesc(ApiLogDO::getId));
    }

}

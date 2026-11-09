package cn.iocoder.yudao.module.supply.dal.mysql.apilog;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.controller.admin.apilog.vo.ApiLogPageReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apilog.ApiLogDO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCallStatRespDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

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


    /**
     * 按 API 汇总开放网关真实调用（APP_GATEWAY，排除测试运行）；走 (api_id, call_time) 索引。
     *
     * @param apiIds API 编号
     * @param since 统计起始时间（含）
     * @return 每个有调用记录的 API 一行
     */
    @Select({"<script>",
            "SELECT api_id AS apiId, count(*) AS callCount,",
            "       count(*) FILTER (WHERE status = 1) AS successCount,",
            "       count(DISTINCT app_id) AS appCount, max(call_time) AS lastCallTime",
            "  FROM dh_supply_api_log",
            " WHERE deleted = 0 AND call_mode = 'APP_GATEWAY' AND test_run_no IS NULL",
            "   AND call_time &gt;= #{since}",
            "   AND api_id IN <foreach collection='apiIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " GROUP BY api_id",
            "</script>"})
    List<SupplyApiCallStatRespDTO> selectCallStatistics(@Param("apiIds") Collection<Long> apiIds,
            @Param("since") LocalDateTime since);

}

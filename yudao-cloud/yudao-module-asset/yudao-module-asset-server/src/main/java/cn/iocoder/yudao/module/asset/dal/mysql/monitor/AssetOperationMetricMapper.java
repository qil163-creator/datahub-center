package cn.iocoder.yudao.module.asset.dal.mysql.monitor;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetOperationMetricDO;
import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AssetOperationMetricMapper extends BaseMapperX<AssetOperationMetricDO> {

    /** 事务级咨询锁：同一时刻只允许一个节点重算快照，事务结束自动释放 */
    @Select("SELECT pg_try_advisory_xact_lock(#{key})")
    boolean tryRefreshLock(@Param("key") long key);

    /** 最近一个已有快照的统计日期 */
    @Select("SELECT max(stat_date) FROM dh_asset_operation_metric WHERE deleted = 0")
    LocalDate selectLatestStatDate();

    /** 同一天重算前物理清除旧快照（快照可完全重建，无需保留软删除行） */
    @Delete("DELETE FROM dh_asset_operation_metric WHERE stat_date = #{statDate}")
    int deleteByStatDate(@Param("statDate") LocalDate statDate);

    /** 清理早于保留期的快照 */
    @Delete("DELETE FROM dh_asset_operation_metric WHERE stat_date < #{before}")
    int deleteBefore(@Param("before") LocalDate before);

    default List<AssetOperationMetricDO> selectListByStatDate(LocalDate statDate) {
        return selectList(AssetOperationMetricDO::getStatDate, statDate);
    }

    default List<AssetOperationMetricDO> selectTrend(Long assetId, LocalDate from) {
        return selectList(new LambdaQueryWrapperX<AssetOperationMetricDO>()
                .eq(AssetOperationMetricDO::getAssetId, assetId)
                .ge(AssetOperationMetricDO::getStatDate, from)
                .orderByAsc(AssetOperationMetricDO::getStatDate));
    }

    default PageResult<AssetOperationMetricDO> selectPage(LocalDate statDate, AssetOperationPageReqVO reqVO) {
        LambdaQueryWrapperX<AssetOperationMetricDO> query = new LambdaQueryWrapperX<AssetOperationMetricDO>();
        query.eq(AssetOperationMetricDO::getStatDate, statDate)
                .eqIfPresent(AssetOperationMetricDO::getAssetType, reqVO.getAssetType())
                .eqIfPresent(AssetOperationMetricDO::getAssetStatus, reqVO.getAssetStatus())
                .eqIfPresent(AssetOperationMetricDO::getCategoryId, reqVO.getCategoryId())
                .eqIfPresent(AssetOperationMetricDO::getValueLevel, reqVO.getValueLevel())
                .eqIfPresent(AssetOperationMetricDO::getSuggestion, reqVO.getSuggestion());
        if (StrUtil.isNotBlank(reqVO.getKeyword())) {
            String keyword = reqVO.getKeyword().trim();
            query.and(q -> q.like(AssetOperationMetricDO::getAssetName, keyword)
                    .or().like(AssetOperationMetricDO::getAssetCode, keyword));
        }
        boolean asc = "asc".equalsIgnoreCase(reqVO.getSortOrder());
        switch (StrUtil.blankToDefault(reqVO.getSortField(), "valueScore")) {
            case "heatScore" -> query.orderBy(true, asc, AssetOperationMetricDO::getHeatScore);
            case "idleDays" -> query.orderBy(true, asc, AssetOperationMetricDO::getIdleDays);
            case "usageCount" -> query.orderBy(true, asc, AssetOperationMetricDO::getUsageCount);
            case "apiCallCount" -> query.orderBy(true, asc, AssetOperationMetricDO::getApiCallCount);
            default -> query.orderBy(true, asc, AssetOperationMetricDO::getValueScore);
        }
        query.orderByAsc(AssetOperationMetricDO::getAssetId);
        return selectPage(reqVO, query);
    }
}

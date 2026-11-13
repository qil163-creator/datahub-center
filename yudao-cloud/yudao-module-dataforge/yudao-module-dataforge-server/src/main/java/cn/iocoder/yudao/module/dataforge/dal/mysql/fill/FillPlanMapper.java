package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPlanStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-采集计划 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillPlanMapper extends BaseMapperX<FillPlanDO> {

    /**
     * 按查询条件分页读取填报计划。
     *
     * @param reqVO 请求参数
     * @param categoryIds 分类编号集合
     * @return 按查询条件分页读取填报计划结果
     */
    default PageResult<FillPlanDO> selectPage(FillPlanPageReqVO reqVO, List<Long> categoryIds) {
        LambdaQueryWrapperX<FillPlanDO> query = new LambdaQueryWrapperX<FillPlanDO>()
                .likeIfPresent(FillPlanDO::getName, reqVO.getName())
                .inIfPresent(FillPlanDO::getCategoryId, categoryIds)
                .eqIfPresent(FillPlanDO::getStatus, reqVO.getStatus())
                .eqIfPresent(FillPlanDO::getTableId, reqVO.getTableId())
                .eqIfPresent(FillPlanDO::getPeriodType, reqVO.getPeriodType())
                .orderByDesc(FillPlanDO::getId);
        if (Long.valueOf(0).equals(reqVO.getCategoryId())) {
            query.isNull(FillPlanDO::getCategoryId);
        }
        return selectPage(reqVO, query);
    }

    /**
     * 按分类编号查询填报计划数量。
     *
     * @param categoryId 分类编号
     * @return SELECT数量按分类编号数值
     */
    default Long selectCountByCategoryId(Long categoryId) {
        return selectCount(FillPlanDO::getCategoryId, categoryId);
    }

    /** 显式写入空分类，避免影响发布/任务生成等只更新部分字段的操作。 */
    default int updateCategoryId(Long id, Long categoryId) {
        return update(null, new LambdaUpdateWrapper<FillPlanDO>()
                .eq(FillPlanDO::getId, id).set(FillPlanDO::getCategoryId, categoryId));
    }

    /** 周期任务生成 Job 扫描：启用中且生成时间已到 */
    default List<FillPlanDO> selectListToGenerate(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<FillPlanDO>()
                .eq(FillPlanDO::getStatus, FillPlanStatusEnum.ENABLED.getStatus())
                .isNotNull(FillPlanDO::getNextGenerateTime)
                .le(FillPlanDO::getNextGenerateTime, now)
                .orderByAsc(FillPlanDO::getNextGenerateTime)
                .last("LIMIT " + limit));
    }

    /**
     * 基于旧的 nextGenerateTime 做 CAS 更新生成位点，防止多实例重复生成
     *
     * @return 影响行数，0 表示被其他实例抢占
     */
    default int compareAndSetGeneratePoint(Long id, LocalDateTime oldNextGenerateTime,
            LocalDateTime newNextGenerateTime, String lastPeriodKey) {
        FillPlanDO update = new FillPlanDO();
        update.setNextGenerateTime(newNextGenerateTime);
        update.setLastPeriodKey(lastPeriodKey);
        return update(update, new LambdaQueryWrapperX<FillPlanDO>()
                .eq(FillPlanDO::getId, id)
                .eq(FillPlanDO::getNextGenerateTime, oldNextGenerateTime));
    }

    /**
     * 任务生成失败时把生成时点退回，下一轮重试同一期，不跳期。
     * 仅当生成时点仍是本次推进后的值时生效（期间被停用、重新发布等并发修改不覆盖）。
     *
     * @param id 计划编号
     * @param advancedNextGenerateTime 本次推进后的下次生成时间（一次性计划为 null）
     * @param generateTime 要退回到的生成时间
     * @param lastPeriodKey 要恢复的最近期次标识
     * @return 影响行数
     */
    @Update("UPDATE dh_dataforge_fill_plan "
            + "SET next_generate_time = #{generateTime}, last_period_key = #{lastPeriodKey,jdbcType=VARCHAR}, "
            + "    update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted = 0 "
            + "  AND next_generate_time IS NOT DISTINCT FROM #{advancedNextGenerateTime,jdbcType=TIMESTAMP}")
    int rollbackGeneratePoint(@Param("id") Long id,
            @Param("advancedNextGenerateTime") LocalDateTime advancedNextGenerateTime,
            @Param("generateTime") LocalDateTime generateTime,
            @Param("lastPeriodKey") String lastPeriodKey);

}

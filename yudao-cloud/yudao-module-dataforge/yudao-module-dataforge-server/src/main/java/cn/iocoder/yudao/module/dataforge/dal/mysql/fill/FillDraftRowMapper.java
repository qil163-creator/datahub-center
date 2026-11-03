package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillDraftRowDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 在线填报-草稿行 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillDraftRowMapper extends BaseMapperX<FillDraftRowDO> {

    /**
     * 按工作项编号查询填报草稿行列表。
     *
     * @param workItemId 工作项编号
     * @return 按工作项编号查询填报草稿行列表结果集合
     */
    default List<FillDraftRowDO> selectListByWorkItemId(Long workItemId) {
        return selectList(new LambdaQueryWrapperX<FillDraftRowDO>()
                .eq(FillDraftRowDO::getWorkItemId, workItemId)
                .orderByAsc(FillDraftRowDO::getRowOrder));
    }

    /**
     * 按工作项编号与行编号查询填报草稿行。
     *
     * @param workItemId 工作项编号
     * @param rowId 行编号
     * @return 填报草稿行
     */
    default FillDraftRowDO selectByWorkItemIdAndRowId(Long workItemId, String rowId) {
        return selectOne(new LambdaQueryWrapperX<FillDraftRowDO>()
                .eq(FillDraftRowDO::getWorkItemId, workItemId)
                .eq(FillDraftRowDO::getRowId, rowId));
    }

    /** 末行（rowOrder 最大），用于追加行的排序值计算 */
    default FillDraftRowDO selectLastByWorkItemId(Long workItemId) {
        return selectOne(new LambdaQueryWrapperX<FillDraftRowDO>()
                .eq(FillDraftRowDO::getWorkItemId, workItemId)
                .orderByDesc(FillDraftRowDO::getRowOrder)
                .last("LIMIT 1"));
    }

    /** 指定排序值之后最近的一行，用于小数间隙法插行 */
    default FillDraftRowDO selectFirstAfterOrder(Long workItemId, java.math.BigDecimal order) {
        return selectOne(new LambdaQueryWrapperX<FillDraftRowDO>()
                .eq(FillDraftRowDO::getWorkItemId, workItemId)
                .gt(FillDraftRowDO::getRowOrder, order)
                .orderByAsc(FillDraftRowDO::getRowOrder)
                .last("LIMIT 1"));
    }

    /**
     * 行数据 CAS 更新：行版本匹配才写入（同一单元格并发的最终防线）
     *
     * @return 影响行数，0 表示行版本已变化
     */
    @Update("UPDATE dh_dataforge_fill_draft_row "
            + "SET data = CAST(#{data} AS jsonb), row_version = row_version + 1, "
            + "    last_modify_user_id = #{userId}, update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND row_version = #{expectedRowVersion} AND deleted = 0")
    int compareAndSetData(@Param("id") Long id,
            @Param("expectedRowVersion") Long expectedRowVersion,
            @Param("data") String data,
            @Param("userId") Long userId);

}

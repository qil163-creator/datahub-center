package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillValidationIssueDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-校验问题 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillValidationIssueMapper extends BaseMapperX<FillValidationIssueDO> {

    /** 草稿校验结果刷新前清空旧问题（submissionId 为空的记录） */
    default void deleteDraftIssuesByWorkItemId(Long workItemId) {
        delete(new LambdaQueryWrapperX<FillValidationIssueDO>()
                .eq(FillValidationIssueDO::getWorkItemId, workItemId)
                .isNull(FillValidationIssueDO::getSubmissionId));
    }

    /**
     * 按工作项编号查询填报校验问题列表。
     *
     * @param workItemId 工作项编号
     * @return 按工作项编号查询填报校验问题列表结果集合
     */
    default List<FillValidationIssueDO> selectListByWorkItemId(Long workItemId) {
        return selectList(FillValidationIssueDO::getWorkItemId, workItemId);
    }

    /**
     * 按提交编号查询填报校验问题列表。
     *
     * @param submissionId 提交编号
     * @return 按提交编号查询填报校验问题列表结果集合
     */
    default List<FillValidationIssueDO> selectListBySubmissionId(Long submissionId) {
        return selectList(FillValidationIssueDO::getSubmissionId, submissionId);
    }

}

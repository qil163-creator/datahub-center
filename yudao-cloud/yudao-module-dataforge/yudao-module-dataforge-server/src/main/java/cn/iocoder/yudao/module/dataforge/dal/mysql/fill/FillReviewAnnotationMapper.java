package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillReviewAnnotationDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-审批批注 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillReviewAnnotationMapper extends BaseMapperX<FillReviewAnnotationDO> {

    /**
     * 按提交编号查询填报复核注解列表。
     *
     * @param submissionId 提交编号
     * @return 按提交编号查询填报复核注解列表结果集合
     */
    default List<FillReviewAnnotationDO> selectListBySubmissionId(Long submissionId) {
        return selectList(FillReviewAnnotationDO::getSubmissionId, submissionId);
    }

}

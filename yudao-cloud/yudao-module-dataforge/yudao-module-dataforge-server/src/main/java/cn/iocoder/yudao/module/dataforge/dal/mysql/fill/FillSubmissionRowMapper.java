package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillSubmissionRowDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-提交快照行 Mapper（不可变，只有插入与按快照查询）
 *
 * @author Li
 */
@Mapper
public interface FillSubmissionRowMapper extends BaseMapperX<FillSubmissionRowDO> {

    /**
     * 按提交编号查询填报提交行列表。
     *
     * @param submissionId 提交编号
     * @return 按提交编号查询填报提交行列表结果集合
     */
    default List<FillSubmissionRowDO> selectListBySubmissionId(Long submissionId) {
        return selectList(new LambdaQueryWrapperX<FillSubmissionRowDO>()
                .eq(FillSubmissionRowDO::getSubmissionId, submissionId)
                .orderByAsc(FillSubmissionRowDO::getRowOrder));
    }

}

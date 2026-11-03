package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillMergeDetailDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-合并明细（血缘）Mapper
 *
 * @author Li
 */
@Mapper
public interface FillMergeDetailMapper extends BaseMapperX<FillMergeDetailDO> {

    /**
     * 按合并批次编号查询填报合并详情列表。
     *
     * @param mergeBatchId 合并批次编号
     * @return 按合并批次编号查询填报合并详情列表结果集合
     */
    default List<FillMergeDetailDO> selectListByMergeBatchId(Long mergeBatchId) {
        return selectList(FillMergeDetailDO::getMergeBatchId, mergeBatchId);
    }

}

package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlNodeRecordDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 可视化 ETL 节点执行记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface EtlNodeRecordMapper extends BaseMapperX<EtlNodeRecordDO> {

    /**
     * 按记录编号查询ETL节点记录列表。
     *
     * @param recordId 记录编号
     * @return 按记录编号查询ETL节点记录列表结果集合
     */
    default List<EtlNodeRecordDO> selectListByRecordId(Long recordId) {
        return selectList(new LambdaQueryWrapperX<EtlNodeRecordDO>()
                .eq(EtlNodeRecordDO::getRecordId, recordId)
                .orderByAsc(EtlNodeRecordDO::getId));
    }

    /**
     * 按记录编号删除ETL节点记录。
     *
     * @param recordId 记录编号
     */
    default void deleteByRecordId(Long recordId) {
        delete(EtlNodeRecordDO::getRecordId, recordId);
    }

}

package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillOperationDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 在线填报-草稿操作日志 Mapper（只追加）
 *
 * @author Li
 */
@Mapper
public interface FillOperationMapper extends BaseMapperX<FillOperationDO> {

    /** 协同增量拉取：取某版本之后的操作（含重连补齐） */
    default List<FillOperationDO> selectListAfterVersion(Long workItemId, Long afterVersion, int limit) {
        return selectList(new LambdaQueryWrapperX<FillOperationDO>()
                .eq(FillOperationDO::getWorkItemId, workItemId)
                .gt(FillOperationDO::getServerVersion, afterVersion)
                .orderByAsc(FillOperationDO::getServerVersion)
                .last("LIMIT " + limit));
    }

    /**
     * 按客户端操作编号查询填报操作。
     *
     * @param workItemId 工作项编号
     * @param clientOpId 客户端操作编号
     * @return 填报操作
     */
    default FillOperationDO selectByClientOpId(Long workItemId, String clientOpId) {
        return selectOne(new LambdaQueryWrapperX<FillOperationDO>()
                .eq(FillOperationDO::getWorkItemId, workItemId)
                .eq(FillOperationDO::getClientOpId, clientOpId));
    }

}

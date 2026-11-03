package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaChangePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaChangeDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-元数据-变更明细 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaChangeMapper extends BaseMapperX<MetaChangeDO> {

    /**
     * 查询元数据变更分页。
     */
    default PageResult<MetaChangeDO> selectPage(MetaChangePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MetaChangeDO>()
                .eqIfPresent(MetaChangeDO::getTableId, reqVO.getTableId())
                .eqIfPresent(MetaChangeDO::getDbId, reqVO.getDbId())
                .eqIfPresent(MetaChangeDO::getInstanceId, reqVO.getInstanceId())
                .eqIfPresent(MetaChangeDO::getObjectType, reqVO.getObjectType())
                .eqIfPresent(MetaChangeDO::getChangeType, reqVO.getChangeType())
                .eqIfPresent(MetaChangeDO::getChangeSource, reqVO.getChangeSource())
                .eqIfPresent(MetaChangeDO::getProperty, reqVO.getProperty())
                .betweenIfPresent(MetaChangeDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(MetaChangeDO::getId));
    }

    /**
     * 按实例编号查询元数据变更列表。
     */
    default List<MetaChangeDO> selectListByInstanceId(Long instanceId) {
        return selectList(new LambdaQueryWrapperX<MetaChangeDO>()
                .eq(MetaChangeDO::getInstanceId, instanceId)
                .orderByAsc(MetaChangeDO::getId));
    }

    /**
     * 按表编号查询元数据变更列表。
     */
    default List<MetaChangeDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<MetaChangeDO>()
                .eq(MetaChangeDO::getTableId, tableId)
                .orderByDesc(MetaChangeDO::getId));
    }

}

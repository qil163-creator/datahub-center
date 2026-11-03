package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-检查对象 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityCheckObjectMapper extends BaseMapperX<QualityCheckObjectDO> {

    /**
     * 查询质量检查对象分页。
     */
    default PageResult<QualityCheckObjectDO> selectPage(QualityCheckObjectPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityCheckObjectDO>()
                .likeIfPresent(QualityCheckObjectDO::getObjectName, reqVO.getObjectName())
                .likeIfPresent(QualityCheckObjectDO::getTableName, reqVO.getTableName())
                .eqIfPresent(QualityCheckObjectDO::getDatasourceConnId, reqVO.getDatasourceConnId())
                .eqIfPresent(QualityCheckObjectDO::getDatasourceType, reqVO.getDatasourceType())
                .orderByDesc(QualityCheckObjectDO::getId));
    }

    /**
     * 按名称查询质量检查对象。
     */
    default QualityCheckObjectDO selectByName(String objectName) {
        return selectOne(new LambdaQueryWrapperX<QualityCheckObjectDO>()
                .eq(QualityCheckObjectDO::getObjectName, objectName));
    }

    /**
     * 按数据源与表查询质量检查对象。
     */
    default QualityCheckObjectDO selectByDatasourceAndTable(Long datasourceConnId, String tableName) {
        return selectOne(new LambdaQueryWrapperX<QualityCheckObjectDO>()
                .eq(QualityCheckObjectDO::getDatasourceConnId, datasourceConnId)
                .eq(QualityCheckObjectDO::getTableName, tableName)
                .last("LIMIT 1"));
    }

    /**
     * 按表编号查询质量检查对象列表。
     */
    default java.util.List<QualityCheckObjectDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<QualityCheckObjectDO>()
                .eq(QualityCheckObjectDO::getTableId, tableId)
                .orderByAsc(QualityCheckObjectDO::getId));
    }

}

package cn.iocoder.yudao.module.governance.dal.mysql.identify;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.SafetyFieldTagPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * 敏感字段打标 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyFieldTagMapper extends BaseMapperX<SafetyFieldTagDO> {

    /**
     * 查询安全字段标签分页。
     */
    default PageResult<SafetyFieldTagDO> selectPage(SafetyFieldTagPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyFieldTagDO>()
                .eqIfPresent(SafetyFieldTagDO::getDatabaseId, reqVO.getDatabaseId())
                .likeIfPresent(SafetyFieldTagDO::getTableName, reqVO.getTableName())
                .likeIfPresent(SafetyFieldTagDO::getColumnName, reqVO.getColumnName())
                .eqIfPresent(SafetyFieldTagDO::getCategoryId, reqVO.getCategoryId())
                .eqIfPresent(SafetyFieldTagDO::getTagStatus, reqVO.getTagStatus())
                .eqIfPresent(SafetyFieldTagDO::getSource, reqVO.getSource())
                .eqIfPresent(SafetyFieldTagDO::getScanRunId, reqVO.getScanRunId())
                .orderByDesc(SafetyFieldTagDO::getId));
    }

    /**
     * 按字段查询安全字段标签。
     */
    default SafetyFieldTagDO selectByColumn(Long databaseId, String tableName, String columnName) {
        return selectOne(new LambdaQueryWrapperX<SafetyFieldTagDO>()
                .eq(SafetyFieldTagDO::getDatabaseId, databaseId)
                .eq(SafetyFieldTagDO::getTableName, tableName)
                .eq(SafetyFieldTagDO::getColumnName, columnName)
                .last("LIMIT 1"));
    }

    /**
     * 按状态查询安全字段标签数量。
     */
    default Long selectCountByStatus(String tagStatus) {
        return selectCount(SafetyFieldTagDO::getTagStatus, tagStatus);
    }

    /**
     * 已确认打标按分级分组统计
     */
    default List<Map<String, Object>> selectConfirmedGroupByLevel() {
        return selectMaps(new QueryWrapper<SafetyFieldTagDO>()
                .select("level_id AS levelId", "COUNT(*) AS cnt")
                .eq("tag_status", SafetyFieldTagDO.TAG_STATUS_CONFIRMED)
                .groupBy("level_id"));
    }

}

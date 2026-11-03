package cn.iocoder.yudao.module.governance.dal.mysql.category;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/**
 * 数据安全--数据分类信息 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyCategoryMapper extends BaseMapperX<SafetyCategoryDO> {

    /** 分类与目录写入共用事务锁，保护跨行目录结构和引用不变量。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(1900010000)")
    Integer lockDefinitions();

    /**
     * 按指定条件读取并锁定安全分类记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_safety_category WHERE id=#{id} AND deleted=0 FOR UPDATE")
    SafetyCategoryDO selectForUpdate(@Param("id") Long id);

    /**
     * 统计按目录。
     */
    default Long countByDirectory(Long id) {
        return selectCount(SafetyCategoryDO::getCategoryId, id);
    }

    /**
     * 统计按规则。
     */
    default Long countByRule(Long id) {
        return selectCount(SafetyCategoryDO::getRuleId, id);
    }

    /**
     * 查询安全分类分页。
     */
    default PageResult<SafetyCategoryDO> selectPage(SafetyCategoryPageReqVO reqVO) {
        var query = new QueryWrapper<SafetyCategoryDO>()
                .in(reqVO.getCategoryIds() != null && !reqVO.getCategoryIds().isEmpty(), "category_id",
                        reqVO.getCategoryIds())
                .like(reqVO.getName() != null && !reqVO.getName().isEmpty(), "name", reqVO.getName())
                .like(reqVO.getShortName() != null && !reqVO.getShortName().isEmpty(), "short_name",
                        reqVO.getShortName())
                .eq(reqVO.getDataLevelId() != null, "data_level_id", reqVO.getDataLevelId())
                .eq(reqVO.getStatus() != null, "status", reqVO.getStatus());
        boolean ascending = "asc".equals(reqVO.getSortOrder());
        String order = "createTime".equals(reqVO.getSortField())
                ? "CASE WHEN source_create_time IS NOT NULL THEN source_create_time ELSE create_time END"
                : "CASE WHEN source_record_id ~ '^[0-9]{1,64}$' THEN source_record_id::numeric ELSE id::numeric END";
        query.orderBy(true, ascending, order).orderBy(true, ascending, "id");
        return selectPage(reqVO, query);
    }

}

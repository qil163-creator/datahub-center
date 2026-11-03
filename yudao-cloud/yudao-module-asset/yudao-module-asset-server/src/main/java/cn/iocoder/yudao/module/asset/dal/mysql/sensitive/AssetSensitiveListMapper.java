package cn.iocoder.yudao.module.asset.dal.mysql.sensitive;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.sensitive.vo.AssetSensitivePageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.sensitive.AssetSensitiveListDO;
import org.apache.ibatis.annotations.*;

import java.util.Collection;

@Mapper
public interface AssetSensitiveListMapper extends BaseMapperX<AssetSensitiveListDO> {

    /**
     * 按指定条件读取并锁定资产敏感字段列表记录，供事务内一致性校验或修改使用。
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_asset_sensitive_list WHERE id=#{id} AND deleted=0 FOR UPDATE")
    AssetSensitiveListDO selectForUpdate(@Param("id") Long id);

    /**
     * 按筛选条件分页查询资产敏感字段列表。
     */
    default PageResult<AssetSensitiveListDO> page(AssetSensitivePageReqVO req, Collection<Long> categoryIds,
            Long userId, Long deptId, boolean all) {
        var query = new LambdaQueryWrapperX<AssetSensitiveListDO>()
                .eqIfPresent(AssetSensitiveListDO::getStatus, req.getStatus())
                .eqIfPresent(AssetSensitiveListDO::getAssetId, req.getAssetId())
                .eqIfPresent(AssetSensitiveListDO::getRuleId, req.getRuleId())
                .inIfPresent(AssetSensitiveListDO::getCategoryId, categoryIds);
        String scope = "EXISTS(SELECT 1 FROM dh_asset_data_asset a WHERE a.id=asset_id AND a.deleted=0";
        if (all) {
            query.apply(scope + ")");
        } else {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("清单查询缺少登录身份");
            }
            String owner = " AND (a.tech_leader_user_id={0} OR a.business_leader_user_id={0} OR a.creator={1}";
            if (deptId == null) {
                query.apply(scope + owner + "))", userId, String.valueOf(userId));
            } else {
                query.apply(scope + owner + " OR a.owner_dept_id={2} OR a.provider_dept_id={2}))",
                        userId, String.valueOf(userId), deptId);
            }
        }
        if (req.getKeyword() != null && !req.getKeyword().isBlank()) {
            query.apply("EXISTS(SELECT 1 FROM dh_asset_data_asset s WHERE s.id=asset_id AND s.deleted=0"
                    + " AND s.asset_name ILIKE {0})", "%" + req.getKeyword() + "%");
        }
        boolean asc = "asc".equals(req.getSortOrder());
        if ("createTime".equals(req.getSortField())) {
            query.orderBy(true, asc, AssetSensitiveListDO::getCreateTime);
        }
        return selectPage(req, query.orderBy(true, asc, AssetSensitiveListDO::getId));
    }

    /**
     * 更新定义。
     */
    @Update("""
            UPDATE dh_asset_sensitive_list SET asset_id=#{row.assetId},column_id=#{row.columnId},column_name=#{row.columnName},
              category_id=#{row.categoryId},rule_id=#{row.ruleId},classification_name=#{row.classificationName},level_name=#{row.levelName},
              level_color=#{row.levelColor},rule_name=#{row.ruleName},description=#{row.description},status=#{row.status},revision=revision+1,
              updater=#{actor},source_updater_name=NULL,update_time=CURRENT_TIMESTAMP
            WHERE id=#{row.id} AND revision=#{revision} AND status=1 AND deleted=0
            """)
    int updateDefinition(@Param("row") AssetSensitiveListDO row, @Param("revision") Integer revision,
            @Param("actor") String actor);

    /**
     * 按业务条件更新资产敏感字段列表的变更状态或数据，返回受影响行数。
     */
    @Update("""
            UPDATE dh_asset_sensitive_list SET status=#{status},revision=revision+1,updater=#{actor},
              source_updater_name=NULL,update_time=CURRENT_TIMESTAMP
            WHERE id=#{id} AND revision=#{revision} AND deleted=0
            """)
    int changeStatus(@Param("id") Long id, @Param("revision") Integer revision,
            @Param("status") Integer status, @Param("actor") String actor);

    /**
     * 删除修订号。
     */
    @Update("""
            UPDATE dh_asset_sensitive_list SET deleted=1,revision=revision+1,updater=#{actor},update_time=CURRENT_TIMESTAMP
            WHERE id=#{id} AND revision=#{revision} AND status=1 AND deleted=0
            """)
    int deleteRevision(@Param("id") Long id, @Param("revision") Integer revision, @Param("actor") String actor);
}

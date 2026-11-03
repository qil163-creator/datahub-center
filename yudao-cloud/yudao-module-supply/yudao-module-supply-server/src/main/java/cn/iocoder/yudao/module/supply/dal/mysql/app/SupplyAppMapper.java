package cn.iocoder.yudao.module.supply.dal.mysql.app;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyAppPageReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据服务-应用 Mapper
 *
 * @author Li
 */
@Mapper
public interface SupplyAppMapper extends BaseMapperX<SupplyAppDO> {

    /**
     * 查询供应应用的授权控制。
     *
     * @param appId 应用编号
     * @return 供应应用
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @org.apache.ibatis.annotations.Select("""
            SELECT * FROM dh_supply_app WHERE id=#{appId} AND deleted=0
            """)
    SupplyAppDO selectGrantControl(@org.apache.ibatis.annotations.Param("appId") long appId);

    /** 首次聚合尚无行可锁，使用稳定父对象串行化同一应用下的来源命令。 */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @org.apache.ibatis.annotations.Select("""
            SELECT * FROM dh_supply_app WHERE id=#{appId} FOR UPDATE
            """)
    SupplyAppDO selectGrantParentForUpdate(@org.apache.ibatis.annotations.Param("appId") long appId);

    /**
     * 按查询条件分页读取供应应用。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取供应应用结果
     */
    default PageResult<SupplyAppDO> selectPage(SupplyAppPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SupplyAppDO>()
                .likeIfPresent(SupplyAppDO::getName, reqVO.getName())
                .eqIfPresent(SupplyAppDO::getAppType, reqVO.getAppType())
                .eqIfPresent(SupplyAppDO::getPublicFlag, reqVO.getPublicFlag())
                .orderByDesc(SupplyAppDO::getId));
    }

    /**
     * 按名称查询供应应用。
     *
     * @param name 名称
     * @return 供应应用
     */
    default SupplyAppDO selectByName(String name) {
        return selectOne(new LambdaQueryWrapperX<SupplyAppDO>()
                .eq(SupplyAppDO::getName, name)
                .last("LIMIT 1"));
    }

    /**
     * 按应用标识查询供应应用。
     *
     * @param appKey 应用标识
     * @return 供应应用
     */
    default SupplyAppDO selectByAppKey(String appKey) {
        return selectOne(new LambdaQueryWrapperX<SupplyAppDO>()
                .eq(SupplyAppDO::getAppKey, appKey)
                .last("LIMIT 1"));
    }

    /**
     * 按创建人查询供应应用列表。
     *
     * @param userId 用户编号
     * @return 按创建人查询供应应用列表结果集合
     */
    default java.util.List<SupplyAppDO> selectListByCreator(Long userId) {
        if (userId == null) {
            return java.util.List.of();
        }
        return selectList(new LambdaQueryWrapperX<SupplyAppDO>()
                .eq(SupplyAppDO::getCreator, String.valueOf(userId))
                .orderByDesc(SupplyAppDO::getId));
    }

}

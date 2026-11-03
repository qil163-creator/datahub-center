package cn.iocoder.yudao.module.supply.dal.mysql.dataapi;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiPageReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 数据服务-API 服务 Mapper
 *
 * @author Li
 */
@Mapper
public interface DataApiMapper extends BaseMapperX<DataApiDO> {

    /**
     * 查询数据 API的授权控制。
     *
     * @param apiId API 编号
     * @return 数据 API
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_supply_api WHERE id=#{apiId} AND deleted=0
            """)
    DataApiDO selectGrantControl(@Param("apiId") long apiId);

    /** 仅持本地共享锁，阻止提交授权期间发布合同被切换。 */
    @Select("""
            SELECT * FROM dh_supply_api WHERE id=#{apiId} AND deleted=0 FOR SHARE
            """)
    DataApiDO selectGrantContractForShare(@Param("apiId") long apiId);

    /**
     * 锁定并读取数据 API记录。
     *
     * @param id 记录编号
     * @return 数据 API
     */
    @Select("SELECT * FROM dh_supply_api WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    DataApiDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 更新数据 API的清理草稿JSON。
     *
     * @param id 记录编号
     * @return 清理草稿JSON数值
     */
    @Update("UPDATE dh_supply_api SET draft_json = NULL WHERE id = #{id} AND deleted = 0")
    int clearDraftJson(@Param("id") Long id);

    /**
     * 按查询条件分页读取数据 API。
     *
     * @param reqVO 请求参数
     * @param categoryIds 分类编号集合
     * @return 按查询条件分页读取数据 API结果
     */
    default PageResult<DataApiDO> selectPage(DataApiPageReqVO reqVO, Collection<Long> categoryIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DataApiDO>()
                .likeIfPresent(DataApiDO::getName, reqVO.getName())
                .eqIfPresent(DataApiDO::getStatus, reqVO.getStatus())
                .inIfPresent(DataApiDO::getCategoryId, categoryIds)
                .betweenIfPresent(DataApiDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(DataApiDO::getId));
    }

    /**
     * 按路径与版本查询数据 API。
     *
     * @param path 路径
     * @param version 版本
     * @return 数据 API
     */
    default DataApiDO selectByPathAndVersion(String path, String version) {
        return selectOne(new LambdaQueryWrapperX<DataApiDO>()
                .eq(DataApiDO::getPath, path)
                .eq(DataApiDO::getVersion, version)
                .last("LIMIT 1"));
    }

    /**
     * 按路径与版本查询数据 API列表。
     *
     * @param path 路径
     * @param version 版本
     * @return 按路径与版本查询数据 API列表结果集合
     */
    default List<DataApiDO> selectListByPathAndVersion(String path, String version) {
        return selectList(new LambdaQueryWrapperX<DataApiDO>()
                .eq(DataApiDO::getPath, path)
                .eq(DataApiDO::getVersion, version)
                .orderByAsc(DataApiDO::getId));
    }

    /**
     * 查询精简列表。
     *
     * @param status 目标状态
     * @return 精简列表结果集合
     */
    default List<DataApiDO> selectSimpleList(Integer status) {
        LambdaQueryWrapperX<DataApiDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.select(DataApiDO::getId, DataApiDO::getName, DataApiDO::getCategoryId,
                DataApiDO::getVersion, DataApiDO::getStatus, DataApiDO::getMethod,
                DataApiDO::getPath, DataApiDO::getProviderDeptId, DataApiDO::getActiveReleaseId,
                DataApiDO::getDraftRevision, DataApiDO::getDraftJson);
        return selectList(wrapper
                .eqIfPresent(DataApiDO::getStatus, status)
                .orderByDesc(DataApiDO::getId));
    }

    /**
     * 查询上线表查询列表。
     *
     * @param databaseId 数据源编号
     * @return 上线表查询列表结果集合
     */
    default List<DataApiDO> selectOnlineTableQueryList(Long databaseId) {
        return selectList(new LambdaQueryWrapperX<DataApiDO>()
                .eq(DataApiDO::getDatabaseId, databaseId)
                .eq(DataApiDO::getStatus, DataApiDO.STATUS_ONLINE)
                .eq(DataApiDO::getApiType, DataApiDO.API_TYPE_QUERY)
                .eq(DataApiDO::getConfigType, DataApiDO.CONFIG_TYPE_TABLE)
                .orderByDesc(DataApiDO::getId));
    }

    /**
     * 按分类编号查询数据 API数量。
     *
     * @param categoryId 分类编号
     * @return SELECT数量按分类编号数值
     */
    default Long selectCountByCategoryId(Long categoryId) {
        return selectCount(DataApiDO::getCategoryId, categoryId);
    }

}

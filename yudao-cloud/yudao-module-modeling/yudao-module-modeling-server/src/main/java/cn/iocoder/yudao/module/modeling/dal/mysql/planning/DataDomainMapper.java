package cn.iocoder.yudao.module.modeling.dal.mysql.planning;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据分域 Mapper
 *
 * @author Li
 */
@Mapper
public interface DataDomainMapper extends BaseMapperX<DataDomainDO> {

    /**
     * 锁定并读取数据域记录。
     *
     * @param id 记录编号
     * @return 数据域
     */
    @Select("SELECT * FROM dh_modeling_data_domain WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    @org.apache.ibatis.annotations.ResultMap("mybatis-plus_DataDomainDO")
    DataDomainDO selectForUpdate(@Param("id") Long id);

    /**
     * 更新数据域的命名规则。
     *
     * @param id 记录编号
     * @param rules 规则
     * @param updater 更新器
     * @return 符合条件或受影响的记录数量
     */
    @org.apache.ibatis.annotations.Update("UPDATE dh_modeling_data_domain SET naming_rules = CAST(#{rules} AS jsonb), updater = #{updater}, update_time = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = 0")
    int updateNamingRules(@Param("id") Long id, @Param("rules") String rules, @Param("updater") String updater);

    /**
     * 按查询条件分页读取数据域。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取数据域结果
     */
    default PageResult<DataDomainDO> selectPage(DataDomainPageReqVO reqVO) {
        var query = new LambdaQueryWrapperX<DataDomainDO>()
                .likeIfPresent(DataDomainDO::getCode, reqVO.getCode())
                .eqIfPresent(DataDomainDO::getLeaderUserId, reqVO.getLeaderUserId())
                .eqIfPresent(DataDomainDO::getStatus, reqVO.getStatus());
        if (org.springframework.util.StringUtils.hasText(reqVO.getName())) {
            query.and(q -> q.like(DataDomainDO::getName, reqVO.getName())
                    .or().like(DataDomainDO::getCode, reqVO.getName()));
        }
        boolean ascending = "asc".equals(reqVO.getSortOrder());
        if ("id".equals(reqVO.getSortField())) {
            query.orderBy(true, ascending, DataDomainDO::getId);
        } else {
            query.orderBy(true, ascending, DataDomainDO::getCreateTime).orderByDesc(DataDomainDO::getId);
        }
        return selectPage(reqVO, query);
    }

    /**
     * 查询精简列表。
     *
     * @return 精简列表结果集合
     */
    default List<DataDomainDO> selectSimpleList() {
        return selectList(new LambdaQueryWrapperX<DataDomainDO>()
                .orderByDesc(DataDomainDO::getCreateTime).orderByDesc(DataDomainDO::getId));
    }

    /**
     * 按名称查询数据域。
     *
     * @param name 名称
     * @return 数据域
     */
    default DataDomainDO selectByName(String name) {
        // 用 selectFirstOne（内部 selectList）避开 MyBatis-Plus selectOne 的 Cursor 路径
        //（Cursor + Druid PS 缓存 + PostgreSQL 会复用已关闭 Statement，报「statement 已经被关闭」）
        return selectFirstOne(DataDomainDO::getName, name);
    }

    /**
     * 按编码查询数据域。
     *
     * @param code 编码
     * @return 数据域
     */
    default DataDomainDO selectByCode(String code) {
        var rows = selectList(
                new LambdaQueryWrapperX<DataDomainDO>().apply("lower(code) = lower({0})", code).last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

}

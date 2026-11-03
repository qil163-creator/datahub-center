package cn.iocoder.yudao.module.modeling.dal.mysql.businessentity;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityRelationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BusinessEntityRelationMapper extends BaseMapperX<BusinessEntityRelationDO> {

    /**
     * 查询业务实体关系的按板块。
     *
     * @param boardId 板块编号
     * @return 业务实体关系的按板块结果集合
     */
    @Select("SELECT r.* FROM dh_modeling_business_entity_relation r JOIN dh_modeling_business_entity e ON e.id = r.source_entity_id AND e.deleted = 0 WHERE r.deleted = 0 AND e.data_domain_id = #{boardId} ORDER BY r.sort_order, r.id")
    List<BusinessEntityRelationDO> selectByBoard(@Param("boardId") Long boardId);
}

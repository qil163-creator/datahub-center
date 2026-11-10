package cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.collection.CollectionUtils;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 数据标准--码值 Mapper
 *
 * @author Li
 */
@Mapper
public interface StandardCodeItemMapper extends BaseMapperX<StandardCodeItemDO> {

    /**
     * 按指定条件读取并锁定标准代码项记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_standard_code_item "
            + "WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    StandardCodeItemDO selectByIdForUpdate(@Param("id") Integer id);

    /**
     * 按字典类型与值查询标准代码项。
     */
    default StandardCodeItemDO selectByDictTypeAndValue(String dictType, String value) {
        return selectOne(StandardCodeItemDO::getDictType, dictType, StandardCodeItemDO::getValue, value);
    }

    /**
     * 按编码集合编号与值查询标准代码项。
     */
    default StandardCodeItemDO selectByCodeSetIdAndValue(Integer codeSetId, String value) {
        return selectOne(StandardCodeItemDO::getCodeSetId, codeSetId, StandardCodeItemDO::getValue, value);
    }

    /**
     * 按编码集合编号查询标准代码项列表。
     */
    default List<StandardCodeItemDO> selectListByCodeSetId(Integer codeSetId) {
        return selectList(new LambdaQueryWrapperX<StandardCodeItemDO>()
                .eq(StandardCodeItemDO::getCodeSetId, codeSetId)
                .orderByAsc(StandardCodeItemDO::getSort)
                .orderByAsc(StandardCodeItemDO::getId));
    }

    /** 查询码表内已启用的码值。 */
    default List<StandardCodeItemDO> selectEnabledByCodeSetId(Integer codeSetId) {
        return selectList(new LambdaQueryWrapperX<StandardCodeItemDO>()
                .eq(StandardCodeItemDO::getCodeSetId, codeSetId)
                .eq(StandardCodeItemDO::getStatus, (short) 0)
                .orderByAsc(StandardCodeItemDO::getSort)
                .orderByAsc(StandardCodeItemDO::getId));
    }

    /**
     * 按字典类型查询数量映射。
     */
    default Map<String, Integer> selectCountMapByDictTypes(Collection<String> dictTypes) {
        if (CollUtil.isEmpty(dictTypes)) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> result = selectMaps(new QueryWrapper<StandardCodeItemDO>()
                .select("COUNT(id) AS item_count, dict_type")
                .in("dict_type", dictTypes)
                .groupBy("dict_type"));
        if (CollUtil.isEmpty(result)) {
            return Collections.emptyMap();
        }
        return CollectionUtils.convertMap(result,
                record -> MapUtil.getStr(record, "dict_type"),
                record -> MapUtil.getInt(record, "item_count"));
    }

    /**
     * 按字典类型查询标准代码项列表。
     */
    default List<StandardCodeItemDO> selectListByDictType(String dictType) {
        return selectList(new LambdaQueryWrapperX<StandardCodeItemDO>()
                .eq(StandardCodeItemDO::getDictType, dictType)
                .orderByAsc(StandardCodeItemDO::getSort));
    }

    /**
     * 按字典类型删除标准代码项记录。
     */
    default void deleteByDictTypes(Collection<String> dictTypes) {
        delete(new LambdaQueryWrapperX<StandardCodeItemDO>()
                .inIfPresent(StandardCodeItemDO::getDictType, dictTypes));
    }

    /**
     * 查询标准代码项分页。
     */
    default PageResult<StandardCodeItemDO> selectPage(StandardCodeItemPageReqVO reqVO) {
        LambdaQueryWrapperX<StandardCodeItemDO> wrapper = new LambdaQueryWrapperX<StandardCodeItemDO>()
                .likeIfPresent(StandardCodeItemDO::getLabel, reqVO.getLabel())
                .eqIfPresent(StandardCodeItemDO::getDictType, reqVO.getDictType())
                .eqIfPresent(StandardCodeItemDO::getCodeSetId, reqVO.getCodeSetId())
                .eqIfPresent(StandardCodeItemDO::getStatus, reqVO.getStatus());
        if ("id".equals(reqVO.getSortField())) {
            wrapper.orderBy(true, "asc".equals(reqVO.getSortOrder()), StandardCodeItemDO::getId);
        } else {
            wrapper.orderBy(true, "asc".equals(reqVO.getSortOrder()), StandardCodeItemDO::getCreateTime)
                    .orderByDesc(StandardCodeItemDO::getId);
        }
        return selectPage(reqVO, wrapper);
    }

}

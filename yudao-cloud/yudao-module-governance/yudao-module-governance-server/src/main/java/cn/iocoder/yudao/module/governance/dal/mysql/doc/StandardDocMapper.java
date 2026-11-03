package cn.iocoder.yudao.module.governance.dal.mysql.doc;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;

import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 标准文件登记 Mapper
 *
 * @author Li
 */
@Mapper
public interface StandardDocMapper extends BaseMapperX<StandardDocDO> {

    /**
     * 查询标准文档分页。
     */
    default PageResult<StandardDocDO> selectPage(StandardDocPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<StandardDocDO>()
                .likeIfPresent(StandardDocDO::getStdCode, reqVO.getStdCode())
                .likeIfPresent(StandardDocDO::getNameCn, reqVO.getNameCn())
                .eqIfPresent(StandardDocDO::getNameEn, reqVO.getNameEn())
                .eqIfPresent(StandardDocDO::getStdType, reqVO.getStdType())
                .inIfPresent(StandardDocDO::getCategoryId, reqVO.getCategoryIds())
                .likeIfPresent(StandardDocDO::getPublishOrg, reqVO.getPublishOrg())
                .likeIfPresent(StandardDocDO::getRegistryNo, reqVO.getRegistryNo())
                .betweenIfPresent(StandardDocDO::getRegistryTime, reqVO.getRegistryTime())
                .eqIfPresent(StandardDocDO::getImplementStatus, reqVO.getImplementStatus())
                .eqIfPresent(StandardDocDO::getStatus, reqVO.getStatus())
                .orderByAsc(StandardDocDO::getSort)
                .orderByDesc(StandardDocDO::getId));
    }

    /**
     * 按登记表编号查询标准文档数量。
     */
    default Long selectCountByRegistryNo(String registryNo, Long excludeId) {
        return selectCount(new LambdaQueryWrapperX<StandardDocDO>()
                .eq(StandardDocDO::getRegistryNo, registryNo)
                .neIfPresent(StandardDocDO::getId, excludeId));
    }

    /**
     * 查询指定前缀下最大的入库编号（用于按日流水生成）。
     */
    default String selectMaxRegistryNoByPrefix(String prefix) {
        StandardDocDO doc = selectOne(new LambdaQueryWrapperX<StandardDocDO>()
                .likeRight(StandardDocDO::getRegistryNo, prefix)
                .orderByDesc(StandardDocDO::getRegistryNo)
                .last("LIMIT 1"));
        return doc != null ? doc.getRegistryNo() : null;
    }

    /**
     * 按替换文档编号查询标准文档数量。
     */
    default Long selectCountByReplaceDocId(Long docId) {
        return selectCount(new LambdaQueryWrapperX<StandardDocDO>()
                .eq(StandardDocDO::getReplaceDocId, docId)
                .ne(StandardDocDO::getId, docId));
    }

    /**
     * 锁定有效标准记录。
     *
     * <p>所有写入替代关系或 {@code dh_governance_standard_element.document_id} 的服务，
     * 都必须在同一事务内先调用本方法锁定被引用标准，再写入引用。</p>
     */
    @Select("SELECT * FROM dh_governance_standard_doc " +
            "WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    StandardDocDO selectByIdForUpdate(@Param("id") Long id);

    /**
     * 按指定条件统计标准文档的查询数据元引用数量记录数。
     */
    @Select("SELECT COUNT(*) FROM dh_governance_standard_element " +
            "WHERE document_id = #{docId} AND deleted = 0")
    Long selectElementReferenceCount(@Param("docId") Long docId);

    /**
     * 按指定条件统计标准文档的查询编码集合引用数量记录数。
     */
    @Select("SELECT COUNT(*) FROM dh_governance_standard_code_set " +
            "WHERE document_id = #{docId} AND deleted = 0")
    Long selectCodeSetReferenceCount(@Param("docId") Long docId);

    /**
     * 查询简要列表。
     */
    default List<StandardDocDO> selectSimpleList(Long excludeId) {
        return selectList(new LambdaQueryWrapperX<StandardDocDO>()
                .neIfPresent(StandardDocDO::getId, excludeId)
                .orderByAsc(StandardDocDO::getSort)
                .orderByDesc(StandardDocDO::getId));
    }

}

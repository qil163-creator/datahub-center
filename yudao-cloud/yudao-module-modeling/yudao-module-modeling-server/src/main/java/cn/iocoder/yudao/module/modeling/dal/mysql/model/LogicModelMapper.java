package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.LogicModelPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据建模-逻辑模型 Mapper
 *
 * @author Li
 */
@Mapper
public interface LogicModelMapper extends BaseMapperX<LogicModelDO> {

    /**
     * 按查询条件分页读取逻辑模型。
     *
     * @param reqVO 请求参数
     * @return 按查询条件分页读取逻辑模型结果
     */
    default PageResult<LogicModelDO> selectPage(LogicModelPageReqVO reqVO) {
        LambdaQueryWrapperX<LogicModelDO> wrapper = new LambdaQueryWrapperX<LogicModelDO>()
                .eqIfPresent(LogicModelDO::getCreateMode, reqVO.getCreateMode())
                .eqIfPresent(LogicModelDO::getStatus, reqVO.getStatus())
                .eqIfPresent(LogicModelDO::getLayerId, reqVO.getLayerId())
                .eqIfPresent(LogicModelDO::getSubjectDomainId, reqVO.getSubjectDomainId())
                .eqIfPresent(LogicModelDO::getDataDomainId, reqVO.getDataDomainId())
                .eqIfPresent(LogicModelDO::getBizCategoryId, reqVO.getBizCategoryId())
                .eqIfPresent(LogicModelDO::getDocumentId, reqVO.getDocumentId())
                .eqIfPresent(LogicModelDO::getTableType, reqVO.getTableType())
                .eqIfPresent(LogicModelDO::getClassificationStatus, reqVO.getClassificationStatus())
                .likeIfPresent(LogicModelDO::getName, reqVO.getName())
                .likeIfPresent(LogicModelDO::getCode, reqVO.getCode());
        wrapper.and(reqVO.getKeyword() != null && !reqVO.getKeyword().isEmpty(),
                        inner -> inner.like(LogicModelDO::getName, reqVO.getKeyword())
                                .or().like(LogicModelDO::getCode, reqVO.getKeyword()))
                .orderByDesc(LogicModelDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按名称或编码查询逻辑模型。
     *
     * @param name 名称
     * @param code 编码
     * @return 逻辑模型
     */
    default LogicModelDO selectByNameOrCode(String name, String code) {
        return selectOne(new LambdaQueryWrapperX<LogicModelDO>()
                .and(wrapper -> {
                    wrapper.eq(LogicModelDO::getName, name);
                    if (code != null && !code.isEmpty()) {
                        wrapper.or().eq(LogicModelDO::getCode, code);
                    }
                })
                .last("LIMIT 1"));
    }

    /** 先取得与指标/业务限定相同的事务锁，再锁当前模型，保持跨入口锁顺序一致。 */
    @org.apache.ibatis.annotations.Select("SELECT true FROM (SELECT pg_advisory_xact_lock(hashtextextended('modeling-ownership-global', 0))) guard")
    Boolean lockReferences();

    /** 当前设计写入统一先锁引用域，再锁模型头。 */
    default LogicModelDO selectByIdForUpdate(Long id) {
        lockReferences();
        return selectOne(new LambdaQueryWrapperX<LogicModelDO>()
                .eq(LogicModelDO::getId, id)
                .last("FOR UPDATE"));
    }

    /**
     * 按状态查询逻辑模型数量。
     *
     * @param status 目标状态
     * @return SELECT数量按状态数值
     */
    default Long selectCountByStatus(String status) {
        return selectCount(LogicModelDO::getStatus, status);
    }

    /** 统计状态不等于指定值的模型数（如排除已废弃） */
    default Long selectCountByStatusNe(String status) {
        return selectCount(new LambdaQueryWrapperX<LogicModelDO>()
                .ne(LogicModelDO::getStatus, status));
    }


    /**
     * 按业务分类编号查询逻辑模型数量。
     *
     * @param bizCategoryId 业务分类编号
     * @return SELECT数量按业务分类编号数值
     */
    default Long selectCountByBizCategoryId(Long bizCategoryId) {
        return selectCount(LogicModelDO::getBizCategoryId, bizCategoryId);
    }

    /**
     * 按分层查询逻辑模型列表。
     *
     * @param layerId 分层编号
     * @return 按分层查询逻辑模型列表结果集合
     */
    default List<LogicModelDO> selectListByLayer(Long layerId) {
        return selectList(new LambdaQueryWrapperX<LogicModelDO>()
                .eqIfPresent(LogicModelDO::getLayerId, layerId)
                .orderByDesc(LogicModelDO::getId));
    }
}

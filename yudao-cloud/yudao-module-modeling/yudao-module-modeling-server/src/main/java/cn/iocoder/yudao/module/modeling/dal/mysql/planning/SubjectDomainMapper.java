package cn.iocoder.yudao.module.modeling.dal.mysql.planning;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainListReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 主题域 Mapper
 *
 * @author Li
 */
@Mapper
public interface SubjectDomainMapper extends BaseMapperX<SubjectDomainDO> {

    /**
     * 按查询条件读取主题域列表。
     *
     * @param reqVO 请求参数
     * @return 按查询条件读取主题域列表结果集合
     */
    default List<SubjectDomainDO> selectList(SubjectDomainListReqVO reqVO) {
        var query = new LambdaQueryWrapperX<SubjectDomainDO>()
                .eqIfPresent(SubjectDomainDO::getDataDomainId, reqVO.getDataDomainId())
                .eqIfPresent(SubjectDomainDO::getParentId, reqVO.getParentId())
                .eqIfPresent(SubjectDomainDO::getStatus, reqVO.getStatus())
                .orderByDesc(SubjectDomainDO::getCreateTime).orderByDesc(SubjectDomainDO::getId);
        if (org.springframework.util.StringUtils.hasText(reqVO.getName())) {
            query.and(q -> q.like(SubjectDomainDO::getName, reqVO.getName())
                    .or().like(SubjectDomainDO::getCode, reqVO.getName())
                    .or().like(SubjectDomainDO::getAbbreviation, reqVO.getName()));
        }
        return selectList(query);
    }

    /**
     * 按父级编号查询主题域数量。
     *
     * @param parentId 父级编号
     * @return SELECT数量按父级编号数值
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(SubjectDomainDO::getParentId, parentId);
    }

    /**
     * 按域与父级与名称查询主题域。
     *
     * @param dataDomainId 数据域编号
     * @param parentId 父级编号
     * @param name 名称
     * @return 主题域
     */
    default SubjectDomainDO selectByDomainAndParentAndName(Long dataDomainId, Long parentId, String name) {
        // 用 selectFirstOne（内部 selectList）避开 selectOne 的 Cursor 路径，PG 下会报 statement 已关闭
        return selectFirstOne(SubjectDomainDO::getDataDomainId, dataDomainId,
                SubjectDomainDO::getParentId, parentId, SubjectDomainDO::getName, name);
    }

}

package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;

import java.util.List;

/**
 * 主题域 Service 接口
 *
 * @author Li
 */
public interface SubjectDomainService {

    /**
     * 创建主题域
     *
     * @return 编号
     */
    Long createSubjectDomain(SubjectDomainSaveReqVO createReqVO);

    /**
     * 更新主题域
     */
    void updateSubjectDomain(SubjectDomainSaveReqVO updateReqVO);

    /**
     * 更新主题域状态
     */
    void updateSubjectDomainStatus(Long id, Integer status);

    /**
     * 删除主题域（存在子级不可删除）
     */
    void deleteSubjectDomain(Long id);

    /**
     * 获得主题域
     */
    SubjectDomainDO getSubjectDomain(Long id);

    /**
     * 获得主题域列表（树形数据平铺返回）
     */
    List<SubjectDomainDO> getSubjectDomainList(SubjectDomainListReqVO listReqVO);

}

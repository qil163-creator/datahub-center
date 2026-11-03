package cn.iocoder.yudao.module.governance.service.standardelement;

import java.util.*;

import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementEnumCheckRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImpactRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImportExcelVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementImportRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementMappedColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementModelFieldRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.StandardElementStatisticsRespVO;
import jakarta.validation.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;

/**
 * 数据标准--标准数据元 Service 接口
 *
 * @author Li
 */
public interface StandardElementService {

    /**
     * 切换标准启用状态。
     */
    void updateStatus(Integer id, Short status, Long revision);

    /**
     * 创建数据标准--标准数据元
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Integer createStandardElement(@Valid StandardElementSaveReqVO createReqVO);

    /**
     * 更新数据标准--标准数据元
     *
     * @param updateReqVO 更新信息
     */
    void updateStandardElement(@Valid StandardElementSaveReqVO updateReqVO);

    /**
     * 删除数据标准--标准数据元
     *
     * @param id 编号
     */
    void deleteStandardElement(Integer id);

    /**
     * 批量删除数据标准--标准数据元
     *
     * @param ids 编号
     */
    void deleteStandardElementListByIds(List<Integer> ids);

    /**
     * 绑定标准码表
     *
     * @param id 数据元编号
     * @param codeSetId 标准码表编号
     */
    void bindCodeSet(Integer id, Integer codeSetId);

    /**
     * 发布标准数据元（草稿 → 已发布）
     *
     * @param id 数据元编号
     */
    void publishStandardElement(Integer id);

    /**
     * 标准数据元影响分析：直接落标表
     *
     * @param id 数据元编号
     * @return 影响分析结果
     */
    StandardElementImpactRespVO getImpactAnalysis(Integer id);

    /**
     * 为绑定码表的数据元生成落标字段的码值内容稽核 SQL
     * （统计非法码值行数，可在质量模块或数据源直接执行）
     *
     * @param id 数据元编号
     * @return 每个落标字段一条稽核 SQL
     */
    List<StandardElementEnumCheckRespVO> getEnumCheckSqls(Integer id);

    /**
     * 获得标准数据元的设计态落标（建模模块中绑定该标准的模型字段），
     * 并同步 MODEL_FIELD 类型的落标映射事实（按 fieldUid 对账）。
     *
     * @param id 数据元编号
     * @return 绑定该标准的模型字段列表
     */
    List<StandardElementModelFieldRespVO> getModelFields(Integer id);

    /**
     * 全局同步建模字段的 MODEL_FIELD 落标事实：
     * 补齐缺失事实，字段删除或改绑其它标准的活动事实置为 STALE。
     *
     * @return 变更的事实数量
     */
    int syncModelFieldMappings();

    /**
     * 撤销落标映射（同时解除元数据字段与该标准的绑定）
     *
     * @param mappingId 落标映射编号
     */
    void revokeMapping(Long mappingId);

    /**
     * 废止标准数据元（已发布 → 已废止）
     *
     * @param id 数据元编号
     */
    void deprecateStandardElement(Integer id);

    /**
     * 获得标准数据元的落标字段列表（被哪些元数据字段引用）
     *
     * @param id 数据元编号
     * @return 落标字段列表
     */
    List<StandardElementMappedColumnRespVO> getMappedColumns(Integer id);

    /**
     * 获得标准数据元统计概览（总数、生命周期分布、落标率、码表绑定数）
     *
     * @return 统计概览
     */
    StandardElementStatisticsRespVO getStatistics();

    /**
     * 批量导入标准数据元
     *
     * @param importElements 导入数据元列表
     * @param updateSupport 是否支持更新（按英文名称匹配已有数据元）
     * @return 导入结果
     */
    StandardElementImportRespVO importStandardElementList(List<StandardElementImportExcelVO> importElements,
            boolean updateSupport);

    /**
     * 获得数据标准--标准数据元
     *
     * @param id 编号
     * @return 数据标准--标准数据元
     */
    StandardElementDO getStandardElement(Integer id);

    /**
     * 获得数据标准--标准数据元分页
     *
     * @param pageReqVO 分页查询
     * @return 数据标准--标准数据元分页
     */
    PageResult<StandardElementDO> getStandardElementPage(StandardElementPageReqVO pageReqVO);

}

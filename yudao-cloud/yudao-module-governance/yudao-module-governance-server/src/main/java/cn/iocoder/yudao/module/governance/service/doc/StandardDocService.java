package cn.iocoder.yudao.module.governance.service.doc;

import cn.iocoder.yudao.framework.common.pojo.PageResult;

import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.doc.StandardDocDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardversion.StandardObjectVersionDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据标准--标准文件登记 Service 接口
 *
 * @author Li
 */
public interface StandardDocService {

    /**
     * 创建数据标准--标准文件登记
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createDoc(@Valid StandardDocSaveReqVO createReqVO);

    /** 按登记工作台要求校验类目、原文和标准编号后创建。 */
    Long registerDoc(@Valid StandardDocSaveReqVO createReqVO);

    /** 登记工作台提交完整元信息，允许清空版本号、废止日期。 */
    void updateRegisteredDoc(@Valid StandardDocSaveReqVO updateReqVO);

    /**
     * 更新数据标准--标准文件登记
     *
     * @param updateReqVO 更新信息
     */
    void updateDoc(@Valid StandardDocSaveReqVO updateReqVO);

    /**
     * 删除数据标准--标准文件登记
     *
     * @param id 编号
     */
    void deleteDoc(Long id);

    /**
     * 批量删除数据标准--标准文件登记
     *
     * @param ids 编号
     */
    void deleteDocListByIds(List<Long> ids);

    /**
     * 获得数据标准--标准文件登记
     *
     * @param id 编号
     * @return 数据标准--标准文件登记
     */
    StandardDocDO getDoc(Long id);

    /**
     * 获得标准原文的最新可访问 URL
     *
     * @param id 标准文件编号
     * @return 最新可访问 URL
     */
    String getDocPreviewUrl(Long id);

    /**
     * 获得数据标准--标准文件登记分页
     *
     * @param pageReqVO 分页查询
     * @return 数据标准--标准文件登记分页
     */
    PageResult<StandardDocDO> getDocPage(StandardDocPageReqVO pageReqVO);

    /** 填充平台共享标准目录的类目和创建人名称。 */
    void fillDisplayNames(List<StandardDocRespVO> rows);

    /**
     * 获得替代标准候选精简列表
     *
     * @param excludeId 排除的标准文件编号
     * @return 标准文件候选列表
     */
    List<StandardDocDO> getDocSimpleList(Long excludeId);

    /**
     * 生成标准文件版本快照（版本号按文件递增）
     *
     * @param id            标准文件编号
     * @param changeSummary 变更说明
     * @return 新版本号
     */
    Integer publishDocVersion(Long id, String changeSummary);

    /**
     * 获得标准文件版本快照列表（按版本号降序）
     *
     * @param id 标准文件编号
     * @return 版本快照列表
     */
    List<StandardObjectVersionDO> getDocVersionList(Long id);

}

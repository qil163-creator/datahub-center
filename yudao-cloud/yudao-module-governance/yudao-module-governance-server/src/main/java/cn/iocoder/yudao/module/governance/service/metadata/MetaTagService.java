package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagBindReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagBindingRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagSaveReqVO;

import java.util.List;

/**
 * 数据治理-元数据标签 Service
 *
 * @author Li
 */
public interface MetaTagService {

    /**
     * 创建元数据标签。
     */
    Long createTag(MetaTagSaveReqVO createReqVO);

    /**
     * 更新元数据标签。
     */
    void updateTag(MetaTagSaveReqVO updateReqVO);

    /**
     * 删除元数据标签。
     */
    void deleteTag(Long id);

    /**
     * 获得元数据标签。
     */
    MetaTagRespVO getTag(Long id);

    /**
     * 获得元数据标签分页。
     */
    PageResult<MetaTagRespVO> getTagPage(MetaTagPageReqVO pageReqVO);

    /**
     * 获得元数据标签精简列表。
     */
    List<MetaTagRespVO> getTagSimpleList();

    /**
     * 全量覆盖对象已绑标签
     */
    void bindTags(MetaTagBindReqVO reqVO);

    /**
     * 解绑单个标签。
     */
    void unbindTag(Long tagId, Integer objectType, Long objectId, Integer revision);

    /**
     * 获得对象标签集合及修订号。
     */
    MetaTagBindingRespVO getBindingSnapshot(Integer objectType, Long objectId);

    /**
     * 获得对象已绑标签。
     */
    List<MetaTagRespVO> getTagsByObject(Integer objectType, Long objectId);

}

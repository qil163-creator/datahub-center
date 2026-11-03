package cn.iocoder.yudao.module.governance.service.master;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterCatalogRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterCatalogSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterCatalogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCatalogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 主数据目录 Service 实现类
 *
 * 参考 srt-cloud 的 DataGovernanceMasterDataCatalog，并修正其缺陷：
 * 重命名时级联更新子孙路径、删除前校验模型存在、同级重名校验
 *
 * @author Li
 */
@Service
@Validated
public class MasterCatalogServiceImpl implements MasterCatalogService {

    @Resource
    private MasterCatalogMapper catalogMapper;

    @Resource
    private MasterModelMapper modelMapper;

    /**
     * 创建主数据目录节点
     */
    @Override
    public Long createCatalog(MasterCatalogSaveReqVO createReqVO) {
        MasterCatalogDO parent = validateParent(createReqVO.getParentId());
        // 根节点只能是普通目录，主数据节点必须挂在目录下
        if (parent == null && createReqVO.getType() != MasterCatalogDO.TYPE_DIR) {
            throw exception(MASTER_CATALOG_PARENT_TYPE_ERROR);
        }
        validateNameUnique(createReqVO.getParentId(), createReqVO.getName(), null);
        MasterCatalogDO catalog = BeanUtils.toBean(createReqVO, MasterCatalogDO.class);
        catalog.setPath(buildPath(parent, createReqVO.getName()));
        if (catalog.getOrderNo() == null) {
            catalog.setOrderNo(0);
        }
        catalogMapper.insert(catalog);
        return catalog.getId();
    }

    /**
     * 更新主数据目录节点（父级与类型不可变更）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCatalog(MasterCatalogSaveReqVO updateReqVO) {
        MasterCatalogDO old = validateCatalogExists(updateReqVO.getId());
        if (!Objects.equals(old.getType(), updateReqVO.getType())) {
            throw exception(MASTER_CATALOG_TYPE_CANNOT_CHANGE);
        }
        validateNameUnique(old.getParentId(), updateReqVO.getName(), updateReqVO.getId());
        MasterCatalogDO parent = MasterCatalogDO.PARENT_ID_ROOT.equals(old.getParentId())
                ? null : catalogMapper.selectById(old.getParentId());
        MasterCatalogDO update = BeanUtils.toBean(updateReqVO, MasterCatalogDO.class);
        update.setParentId(old.getParentId());
        update.setPath(buildPath(parent, updateReqVO.getName()));
        catalogMapper.updateById(update);
        // 重命名时级联刷新子孙路径（修正 srt 不级联的问题）
        if (!Objects.equals(old.getName(), updateReqVO.getName())) {
            refreshChildrenPath(update.getId(), update.getPath());
        }
        // 主数据节点的机构变更同步到模型（模型机构继承目录）
        if (old.getType() == MasterCatalogDO.TYPE_MASTER
                && !Objects.equals(old.getOrgId(), updateReqVO.getOrgId())) {
            modelMapper.update(null, Wrappers.lambdaUpdate(MasterModelDO.class)
                    .set(MasterModelDO::getOrgId, updateReqVO.getOrgId())
                    .eq(MasterModelDO::getCatalogId, update.getId()));
        }
    }

    /**
     * 删除主数据目录节点（存在子节点或已建模型时拒绝）
     */
    @Override
    public void deleteCatalog(Long id) {
        MasterCatalogDO catalog = validateCatalogExists(id);
        if (catalogMapper.selectCountByParentId(id) > 0) {
            throw exception(MASTER_CATALOG_EXITS_CHILDREN);
        }
        if (catalog.getType() == MasterCatalogDO.TYPE_MASTER
                && modelMapper.selectByCatalogId(id) != null) {
            throw exception(MASTER_CATALOG_EXISTS_MODEL);
        }
        catalogMapper.deleteById(id);
    }

    /**
     * 获得主数据目录平铺列表（含主数据节点的模型状态），前端组树
     */
    @Override
    public List<MasterCatalogRespVO> getCatalogList() {
        List<MasterCatalogDO> list = catalogMapper.selectListOrderByNo();
        if (CollUtil.isEmpty(list)) {
            return List.of();
        }
        Map<Long, Integer> modelStatusMap = convertMap(modelMapper.selectList(),
                MasterModelDO::getCatalogId, MasterModelDO::getStatus);
        return convertList(list, catalog -> {
            MasterCatalogRespVO vo = BeanUtils.toBean(catalog, MasterCatalogRespVO.class);
            if (catalog.getType() != null && catalog.getType() == MasterCatalogDO.TYPE_MASTER) {
                vo.setModelStatus(modelStatusMap.get(catalog.getId()));
            }
            return vo;
        });
    }

    // ==================== 内部方法 ====================

    /**
     * 校验目录记录存在，不存在时抛出业务异常。
     */
    private MasterCatalogDO validateCatalogExists(Long id) {
        MasterCatalogDO catalog = catalogMapper.selectById(id);
        if (catalog == null) {
            throw exception(MASTER_CATALOG_NOT_EXISTS);
        }
        return catalog;
    }

    /**
     * 校验父节点：根返回 null；父节点必须存在且为普通目录
     */
    private MasterCatalogDO validateParent(Long parentId) {
        if (parentId == null || MasterCatalogDO.PARENT_ID_ROOT.equals(parentId)) {
            return null;
        }
        MasterCatalogDO parent = catalogMapper.selectById(parentId);
        if (parent == null) {
            throw exception(MASTER_CATALOG_PARENT_NOT_EXISTS);
        }
        if (parent.getType() != MasterCatalogDO.TYPE_DIR) {
            throw exception(MASTER_CATALOG_PARENT_TYPE_ERROR);
        }
        return parent;
    }

    /**
     * 校验主数据目录名称在指定范围内唯一。
     */
    private void validateNameUnique(Long parentId, String name, Long selfId) {
        MasterCatalogDO exists = catalogMapper.selectByParentIdAndName(parentId, name);
        if (exists != null && !exists.getId().equals(selfId)) {
            throw exception(MASTER_CATALOG_NAME_DUPLICATE);
        }
    }

    /**
     * 构造主数据目录的路径。
     */
    private String buildPath(MasterCatalogDO parent, String name) {
        return parent == null ? name : parent.getPath() + "/" + name;
    }

    /**
     * 递归刷新子孙节点路径
     */
    private void refreshChildrenPath(Long parentId, String parentPath) {
        List<MasterCatalogDO> children = catalogMapper.selectList(MasterCatalogDO::getParentId, parentId);
        for (MasterCatalogDO child : children) {
            String path = parentPath + "/" + child.getName();
            catalogMapper.updateById(MasterCatalogDO.builder().id(child.getId()).path(path).build());
            refreshChildrenPath(child.getId(), path);
        }
    }

}

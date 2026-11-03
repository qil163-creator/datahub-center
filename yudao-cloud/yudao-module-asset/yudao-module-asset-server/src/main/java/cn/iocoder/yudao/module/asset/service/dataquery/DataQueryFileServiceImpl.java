package cn.iocoder.yudao.module.asset.service.dataquery;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryFileRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryFileSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataquery.DataQueryFileDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataquery.DataQueryFileMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.asset.dal.dataobject.dataquery.DataQueryFileDO.NODE_TYPE_FILE;
import static cn.iocoder.yudao.module.asset.dal.dataobject.dataquery.DataQueryFileDO.NODE_TYPE_FOLDER;
import static cn.iocoder.yudao.module.asset.dal.dataobject.dataquery.DataQueryFileDO.PARENT_ID_ROOT;
import static cn.iocoder.yudao.module.asset.dal.dataobject.dataquery.DataQueryFileDO.VISIBILITY_PRIVATE;
import static cn.iocoder.yudao.module.asset.dal.dataobject.dataquery.DataQueryFileDO.VISIBILITY_PUBLIC;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

@Service
@Validated
public class DataQueryFileServiceImpl implements DataQueryFileService {

    @Resource
    private DataQueryFileMapper dataQueryFileMapper;

    /**
     * 创建目录或 SQL 脚本。
     */
    @Override
    public Long createFile(DataQueryFileSaveReqVO reqVO) {
        validateNodeType(reqVO.getNodeType());
        validateParent(null, reqVO.getParentId());
        validateNameUnique(null, reqVO.getParentId(), reqVO.getName());
        DataQueryFileDO file = BeanUtils.toBean(reqVO, DataQueryFileDO.class);
        normalizeByType(file, reqVO.getNodeType());
        if (file.getSort() == null) {
            file.setSort(0);
        }
        if (file.getVisibility() == null) {
            file.setVisibility(Objects.equals(reqVO.getNodeType(), NODE_TYPE_FILE)
                    ? VISIBILITY_PRIVATE : VISIBILITY_PUBLIC);
        }
        validateVisibility(file.getVisibility());
        dataQueryFileMapper.insert(file);
        return file.getId();
    }

    /**
     * 更新目录或 SQL 脚本。
     */
    @Override
    public void updateFile(DataQueryFileSaveReqVO reqVO) {
        DataQueryFileDO exists = validateExists(reqVO.getId());
        validateOwner(exists);
        if (!Objects.equals(exists.getNodeType(), reqVO.getNodeType())) {
            throw exception(DATA_QUERY_FILE_TYPE_IMMUTABLE);
        }
        validateParent(reqVO.getId(), reqVO.getParentId());
        validateNameUnique(reqVO.getId(), reqVO.getParentId(), reqVO.getName());
        DataQueryFileDO update = BeanUtils.toBean(reqVO, DataQueryFileDO.class);
        if (Objects.equals(exists.getNodeType(), NODE_TYPE_FILE)
                && reqVO.getSqlText() == null) {
            update.setSqlText(exists.getSqlText());
        }
        if (Objects.equals(exists.getNodeType(), NODE_TYPE_FILE)
                && reqVO.getDatasourceId() == null) {
            update.setDatasourceId(exists.getDatasourceId());
        }
        if (Objects.equals(exists.getNodeType(), NODE_TYPE_FILE)
                && reqVO.getQueryLimit() == null) {
            update.setQueryLimit(exists.getQueryLimit());
        }
        normalizeByType(update, exists.getNodeType());
        if (update.getSort() == null) {
            update.setSort(exists.getSort());
        }
        if (update.getVisibility() == null) {
            update.setVisibility(exists.getVisibility());
        }
        validateVisibility(update.getVisibility());
        dataQueryFileMapper.updateById(update);
    }

    /**
     * 删除目录或 SQL 脚本。
     */
    @Override
    public void deleteFile(Long id) {
        DataQueryFileDO exists = validateExists(id);
        validateOwner(exists);
        if (dataQueryFileMapper.selectCountByParentId(id) > 0) {
            throw exception(DATA_QUERY_FILE_HAS_CHILDREN);
        }
        dataQueryFileMapper.deleteById(id);
    }

    /**
     * 获得目录或 SQL 脚本详情。
     */
    @Override
    public DataQueryFileRespVO getFile(Long id) {
        DataQueryFileDO file = validateExists(id);
        if (!canView(file)) {
            throw exception(DATA_QUERY_FILE_NOT_EXISTS);
        }
        DataQueryFileRespVO respVO = BeanUtils.toBean(file, DataQueryFileRespVO.class);
        respVO.setOwned(isOwner(file));
        return respVO;
    }

    /**
     * 获得目录树扁平列表，不含 SQL 正文。
     */
    @Override
    public List<DataQueryFileRespVO> getFileList() {
        List<DataQueryFileDO> visible = filterVisible(dataQueryFileMapper.selectListOrderBySort());
        Map<Long, DataQueryFileDO> byId = visible.stream()
                .collect(Collectors.toMap(DataQueryFileDO::getId, Function.identity()));
        List<DataQueryFileRespVO> list = BeanUtils.toBean(visible, DataQueryFileRespVO.class);
        list.forEach(item -> {
            item.setSqlText(null);
            item.setOwned(isOwner(byId.get(item.getId())));
        });
        return list;
    }

    /**
     * 读取并校验数据查询文件记录存在，不存在时抛出业务异常。
     */
    private DataQueryFileDO validateExists(Long id) {
        DataQueryFileDO file = id == null ? null : dataQueryFileMapper.selectById(id);
        if (file == null) {
            throw exception(DATA_QUERY_FILE_NOT_EXISTS);
        }
        return file;
    }

    /**
     * 校验当前用户具有数据查询文件对象的所属人权限。
     */
    private void validateOwner(DataQueryFileDO file) {
        if (!isOwner(file)) {
            throw exception(DATA_QUERY_FILE_FORBIDDEN);
        }
    }

    /**
     * 校验数据查询文件的可见性，不满足约束时拒绝当前操作。
     */
    private void validateVisibility(Integer visibility) {
        if (!Objects.equals(visibility, VISIBILITY_PUBLIC)
                && !Objects.equals(visibility, VISIBILITY_PRIVATE)) {
            throw exception(DATA_QUERY_FILE_VISIBILITY_INVALID);
        }
    }

    /**
     * 判断当前用户是否可访问数据查询文件的视图。
     */
    private boolean canView(DataQueryFileDO file) {
        if (file == null) {
            return false;
        }
        if (Objects.equals(file.getNodeType(), NODE_TYPE_FOLDER)
                || !Objects.equals(file.getVisibility(), VISIBILITY_PRIVATE)) {
            return true;
        }
        return isOwner(file);
    }

    /**
     * 判断当前用户是否为对象所属人。
     */
    private boolean isOwner(DataQueryFileDO file) {
        if (file == null) {
            return false;
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId != null && Objects.equals(String.valueOf(userId), file.getCreator());
    }

    /**
     * 筛选可见。
     */
    private List<DataQueryFileDO> filterVisible(List<DataQueryFileDO> all) {
        List<DataQueryFileDO> result = new ArrayList<>();
        Set<Long> keepIds = new HashSet<>();
        Map<Long, DataQueryFileDO> byId = all.stream()
                .collect(Collectors.toMap(DataQueryFileDO::getId, Function.identity()));
        for (DataQueryFileDO node : all) {
            if (canView(node)) {
                keepIds.add(node.getId());
            }
        }
        // 私有脚本的祖先目录一并保留，避免树断掉
        for (Long id : new HashSet<>(keepIds)) {
            DataQueryFileDO cursor = byId.get(id);
            while (cursor != null && cursor.getParentId() != null
                    && !PARENT_ID_ROOT.equals(cursor.getParentId())) {
                keepIds.add(cursor.getParentId());
                cursor = byId.get(cursor.getParentId());
            }
        }
        for (DataQueryFileDO node : all) {
            if (keepIds.contains(node.getId())) {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * 校验数据查询文件的节点类型，不满足约束时拒绝当前操作。
     */
    private void validateNodeType(Integer nodeType) {
        if (!Objects.equals(nodeType, NODE_TYPE_FOLDER) && !Objects.equals(nodeType, NODE_TYPE_FILE)) {
            throw exception(DATA_QUERY_FILE_TYPE_INVALID);
        }
    }

    /**
     * 校验数据查询文件的父级，不满足约束时拒绝当前操作。
     */
    private void validateParent(Long id, Long parentId) {
        if (parentId == null || PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        if (Objects.equals(id, parentId)) {
            throw exception(DATA_QUERY_FILE_PARENT_ERROR);
        }
        DataQueryFileDO parent = dataQueryFileMapper.selectById(parentId);
        if (parent == null) {
            throw exception(DATA_QUERY_FILE_PARENT_NOT_EXISTS);
        }
        if (!Objects.equals(parent.getNodeType(), NODE_TYPE_FOLDER)) {
            throw exception(DATA_QUERY_FILE_PARENT_MUST_FOLDER);
        }
        if (id == null) {
            return;
        }
        DataQueryFileDO cursor = parent;
        for (int i = 0; i < Short.MAX_VALUE && cursor != null; i++) {
            Long ancestorId = cursor.getParentId();
            if (Objects.equals(id, ancestorId)) {
                throw exception(DATA_QUERY_FILE_PARENT_IS_CHILD);
            }
            if (ancestorId == null || PARENT_ID_ROOT.equals(ancestorId)) {
                break;
            }
            cursor = dataQueryFileMapper.selectById(ancestorId);
        }
    }

    /**
     * 校验数据查询文件名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, Long parentId, String name) {
        DataQueryFileDO exists = dataQueryFileMapper.selectByParentIdAndName(parentId, name);
        if (exists != null && !Objects.equals(exists.getId(), id)) {
            throw exception(DATA_QUERY_FILE_NAME_DUPLICATE);
        }
    }

    /**
     * 规范化数据查询文件的按类型。
     */
    private void normalizeByType(DataQueryFileDO file, Integer nodeType) {
        if (Objects.equals(nodeType, NODE_TYPE_FOLDER)) {
            file.setSqlText(null);
            file.setDatasourceId(null);
            file.setQueryLimit(null);
            file.setVisibility(VISIBILITY_PUBLIC);
            return;
        }
        if (file.getSqlText() != null) {
            file.setSqlText(StrUtil.trim(file.getSqlText()));
        }
    }

}

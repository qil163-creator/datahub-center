package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir.DevDirSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevDirDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据开发-任务目录 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DevDirServiceImpl implements DevDirService {

    @Resource
    private DevDirMapper dirMapper;

    @Resource
    private DevTaskMapper taskMapper;

    /**
     * 创建开发目录。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDir(DevDirSaveReqVO createReqVO) {
        dirMapper.lockTree();
        Long parentId = createReqVO.getParentId() == null ? DevDirDO.PARENT_ROOT : createReqVO.getParentId();
        if (!DevDirDO.PARENT_ROOT.equals(parentId)) {
            validateDirExists(parentId);
        }
        DevDirDO dir = BeanUtils.toBean(createReqVO, DevDirDO.class);
        dir.setParentId(parentId);
        if (dir.getSort() == null) {
            dir.setSort(0);
        }
        if (dir.getStatus() == null) {
            dir.setStatus(0);
        }
        try {
            dirMapper.insert(dir);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "同一上级下已存在同名数据开发类目");
        }
        return dir.getId();
    }

    /**
     * 更新开发目录。
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDir(DevDirSaveReqVO updateReqVO) {
        dirMapper.lockTree();
        DevDirDO old = dirMapper.selectById(updateReqVO.getId());
        if (old == null) {
            throw exception(DEV_DIR_NOT_EXISTS);
        }
        Long parentId = updateReqVO.getParentId() == null ? old.getParentId() : updateReqVO.getParentId();
        if (!DevDirDO.PARENT_ROOT.equals(parentId)) {
            validateDirExists(parentId);
            if (Objects.equals(parentId, updateReqVO.getId()) || isDescendant(updateReqVO.getId(), parentId)) {
                throw exception(DEV_DIR_PARENT_ERROR);
            }
        }
        DevDirDO dir = BeanUtils.toBean(updateReqVO, DevDirDO.class);
        dir.setParentId(parentId);
        try {
            dirMapper.updateById(dir);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "同一上级下已存在同名数据开发类目");
        }
    }

    /**
     * 更新目录状态。
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDirStatus(Long id, Integer status) {
        dirMapper.lockTree();
        if (dirMapper.selectById(id) == null) {
            throw exception(DEV_DIR_NOT_EXISTS);
        }
        if (status == null || (status != 0 && status != 1)) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "类目状态无效");
        }
        var change = new DevDirDO();
        change.setId(id);
        change.setStatus(status);
        if (dirMapper.updateById(change) != 1) {
            throw exception(DEV_DIR_NOT_EXISTS);
        }
    }

    /**
     * 删除开发目录。
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDir(Long id) {
        dirMapper.lockTree();
        DevDirDO dir = dirMapper.selectById(id);
        if (dir == null) {
            throw exception(DEV_DIR_NOT_EXISTS);
        }
        if (dirMapper.selectCountByParentId(id) > 0) {
            throw exception(DEV_DIR_HAS_CHILDREN);
        }
        if (taskMapper.selectCountByDirId(id) > 0) {
            throw exception(DEV_DIR_HAS_TASK);
        }
        dirMapper.deleteById(id);
    }

    /**
     * 获得开发目录列表。
     *
     * @return 目录列表
     */
    @Override
    public List<DevDirDO> getDirList() {
        return dirMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<DevDirDO>lambdaQuery()
                .orderByAsc(DevDirDO::getSort));
    }

    /**
     * 在任务本地写入事务中锁定类目树并再次验证，避免逻辑删除与任务引用并发。
     *
     * @param dirId 目录编号
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void lockAndValidateDirExists(Long dirId) {
        dirMapper.lockTree();
        validateDirExists(dirId);
    }

    /**
     * 校验目录存在（dirId 为空或 0 时跳过）
     *
     * @param dirId 目录编号
     */
    @Override
    public void validateDirExists(Long dirId) {
        if (dirId == null || DevDirDO.PARENT_ROOT.equals(dirId)) {
            return;
        }
        DevDirDO dir = dirMapper.selectById(dirId);
        if (dir == null) {
            throw exception(DEV_DIR_NOT_EXISTS);
        }
        int depth = 0;
        while (dir != null && depth++ < 64) {
            if (Integer.valueOf(1).equals(dir.getStatus())) {
                throw exception(DEV_TASK_DEPENDENCY_INVALID, "数据开发类目或其上级已禁用，请选择启用的类目");
            }
            if (dir.getParentId() == null || DevDirDO.PARENT_ROOT.equals(dir.getParentId())) {
                return;
            }
            dir = dirMapper.selectById(dir.getParentId());
        }
        throw exception(DEV_DIR_PARENT_ERROR);
    }

    /**
     * 判断 candidate 是否为 dirId 的子孙目录
     */
    private boolean isDescendant(Long dirId, Long candidate) {
        List<DevDirDO> all = dirMapper.selectListAll();
        Long current = candidate;
        int depth = 0;
        while (current != null && !DevDirDO.PARENT_ROOT.equals(current) && depth++ < 64) {
            if (Objects.equals(current, dirId)) {
                return true;
            }
            Long finalCurrent = current;
            current = all.stream()
                    .filter(dir -> Objects.equals(dir.getId(), finalCurrent))
                    .map(DevDirDO::getParentId)
                    .findFirst().orElse(null);
        }
        return false;
    }

}

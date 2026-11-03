package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir.DevDirSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevDirDO;

import java.util.List;

/**
 * 数据开发-任务目录 Service 接口
 *
 * @author Li
 */
public interface DevDirService {

    /**
     * 创建开发目录。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    Long createDir(DevDirSaveReqVO createReqVO);

    /**
     * 更新开发目录。
     *
     * @param updateReqVO 更新参数
     */
    void updateDir(DevDirSaveReqVO updateReqVO);

    /**
     * 更新目录状态。
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    void updateDirStatus(Long id, Integer status);

    /**
     * 删除开发目录。
     *
     * @param id 记录编号
     */
    void deleteDir(Long id);

    /**
     * 获得开发目录列表。
     *
     * @return 目录列表
     */
    List<DevDirDO> getDirList();

    /**
     * 校验目录存在（dirId 为空或 0 时跳过）
     */
    void validateDirExists(Long dirId);

    /** 在任务本地写入事务中锁定类目树并再次验证，避免逻辑删除与任务引用并发。 */
    void lockAndValidateDirExists(Long dirId);

}

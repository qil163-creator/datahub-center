package cn.iocoder.yudao.module.dataforge.service.system;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.system.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.systemsource.SystemSourceDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.systemsource.SystemSourceMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 来源系统 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class SystemSourceServiceImpl implements SystemSourceService {

    @Resource
    private SystemSourceMapper systemSourceMapper;

    /**
     * 创建来源系统
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createSystemSource(SystemSourceSaveReqVO createReqVO) {
        // 插入
        SystemSourceDO systemSource = BeanUtils.toBean(createReqVO, SystemSourceDO.class);
        systemSourceMapper.insert(systemSource);

        // 返回
        return systemSource.getId();
    }

    /**
     * 更新来源系统
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateSystemSource(SystemSourceSaveReqVO updateReqVO) {
        // 校验存在
        validateSystemSourceExists(updateReqVO.getId());
        // 更新
        SystemSourceDO updateObj = BeanUtils.toBean(updateReqVO, SystemSourceDO.class);
        systemSourceMapper.updateById(updateObj);
    }

    /**
     * 删除来源系统
     *
     * @param id 记录编号
     */
    @Override
    public void deleteSystemSource(Long id) {
        // 校验存在
        validateSystemSourceExists(id);
        // 删除
        systemSourceMapper.deleteById(id);
    }

    /**
     * 批量删除来源系统
     *
     * @param ids 记录编号集合
     */
    @Override
    public void deleteSystemSourceListByIds(List<Long> ids) {
        // 删除
        systemSourceMapper.deleteByIds(ids);
    }


    /**
     * 校验业务系统存在性。
     *
     * @param id 记录编号
     */
    private void validateSystemSourceExists(Long id) {
        if (systemSourceMapper.selectById(id) == null) {
            throw exception(SYSTEM_SOURCE_NOT_EXISTS);
        }
    }

    /**
     * 获得来源系统
     *
     * @param id 记录编号
     * @return 业务系统
     */
    @Override
    public SystemSourceDO getSystemSource(Long id) {
        return systemSourceMapper.selectById(id);
    }

    /**
     * 获得来源系统分页
     *
     * @param pageReqVO 分页查询条件
     * @return 来源系统分页结果
     */
    @Override
    public PageResult<SystemSourceDO> getSystemSourcePage(SystemSourcePageReqVO pageReqVO) {
        return systemSourceMapper.selectPage(pageReqVO);
    }

}

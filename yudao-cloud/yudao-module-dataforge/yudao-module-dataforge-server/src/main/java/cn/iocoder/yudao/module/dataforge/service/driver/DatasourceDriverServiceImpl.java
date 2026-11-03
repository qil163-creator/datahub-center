package cn.iocoder.yudao.module.dataforge.service.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.driver.DatasourceDriverDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.driver.DatasourceDriverMapper;
import cn.iocoder.yudao.module.dataforge.service.driver.DatasourceDriverService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据源驱动管理 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DatasourceDriverServiceImpl implements DatasourceDriverService {

    @Resource
    private DatasourceDriverMapper datasourceDriverMapper;

    /**
     * 创建数据源驱动管理
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createDatasourceDriver(DatasourceDriverSaveReqVO createReqVO) {
        // 插入
        DatasourceDriverDO datasourceDriver = BeanUtils.toBean(createReqVO, DatasourceDriverDO.class);
        datasourceDriverMapper.insert(datasourceDriver);

        // 返回
        return datasourceDriver.getId();
    }

    /**
     * 更新数据源驱动管理
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateDatasourceDriver(DatasourceDriverSaveReqVO updateReqVO) {
        // 校验存在
        validateDatasourceDriverExists(updateReqVO.getId());
        // 更新
        DatasourceDriverDO updateObj = BeanUtils.toBean(updateReqVO, DatasourceDriverDO.class);
        datasourceDriverMapper.updateById(updateObj);
    }

    /**
     * 删除数据源驱动管理
     *
     * @param id 记录编号
     */
    @Override
    public void deleteDatasourceDriver(Long id) {
        // 校验存在
        validateDatasourceDriverExists(id);
        // 删除
        datasourceDriverMapper.deleteById(id);
    }

    /**
     * 批量删除数据源驱动管理
     *
     * @param ids 记录编号集合
     */
    @Override
    public void deleteDatasourceDriverListByIds(List<Long> ids) {
        // 删除
        datasourceDriverMapper.deleteByIds(ids);
    }


    /**
     * 校验数据源驱动存在性。
     *
     * @param id 记录编号
     */
    private void validateDatasourceDriverExists(Long id) {
        if (datasourceDriverMapper.selectById(id) == null) {
            throw exception(DATASOURCE_DRIVER_NOT_EXISTS);
        }
    }

    /**
     * 获得数据源驱动管理
     *
     * @param id 记录编号
     * @return 数据源驱动
     */
    @Override
    public DatasourceDriverDO getDatasourceDriver(Long id) {
        return datasourceDriverMapper.selectById(id);
    }

    /**
     * 获得数据源驱动管理分页
     *
     * @param pageReqVO 分页查询条件
     * @return 数据源驱动管理分页结果
     */
    @Override
    public PageResult<DatasourceDriverDO> getDatasourceDriverPage(DatasourceDriverPageReqVO pageReqVO) {
        return datasourceDriverMapper.selectPage(pageReqVO);
    }

}

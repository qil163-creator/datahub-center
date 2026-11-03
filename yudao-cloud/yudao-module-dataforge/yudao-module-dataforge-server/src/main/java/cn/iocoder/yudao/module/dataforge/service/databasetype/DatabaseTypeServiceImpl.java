package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据库类型定义 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DatabaseTypeServiceImpl implements DatabaseTypeService {

    @Resource
    private DatabaseTypeMapper databaseTypeMapper;

    /**
     * 创建数据库类型定义
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createDatabaseType(DatabaseTypeSaveReqVO createReqVO) {
        // 插入
        DatabaseTypeDO databaseType = BeanUtils.toBean(createReqVO, DatabaseTypeDO.class);
        databaseTypeMapper.insert(databaseType);

        // 返回
        return databaseType.getId();
    }

    /**
     * 更新数据库类型定义
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateDatabaseType(DatabaseTypeSaveReqVO updateReqVO) {
        // 校验存在
        validateDatabaseTypeExists(updateReqVO.getId());
        // 更新
        DatabaseTypeDO updateObj = BeanUtils.toBean(updateReqVO, DatabaseTypeDO.class);
        databaseTypeMapper.updateById(updateObj);
    }

    @Override
    public void updateDatabaseTypeStatus(DatabaseTypeStatusReqVO request) {
        DatabaseTypeDO update = DatabaseTypeDO.builder()
                .id(request.getId())
                .status(request.getStatus())
                .build();
        if (databaseTypeMapper.updateById(update) == 0) {
            throw exception(DATABASE_TYPE_NOT_EXISTS);
        }
    }

    /**
     * 删除数据库类型定义
     *
     * @param id 记录编号
     */
    @Override
    public void deleteDatabaseType(Long id) {
        // 校验存在
        validateDatabaseTypeExists(id);
        // 删除
        databaseTypeMapper.deleteById(id);
    }

    /**
     * 批量删除数据库类型定义
     *
     * @param ids 记录编号集合
     */
    @Override
    public void deleteDatabaseTypeListByIds(List<Long> ids) {
        // 删除
        databaseTypeMapper.deleteByIds(ids);
    }


    /**
     * 校验数据库类型存在性。
     *
     * @param id 记录编号
     */
    private void validateDatabaseTypeExists(Long id) {
        if (databaseTypeMapper.selectById(id) == null) {
            throw exception(DATABASE_TYPE_NOT_EXISTS);
        }
    }

    /**
     * 获得数据库类型定义
     *
     * @param id 记录编号
     * @return 数据库类型
     */
    @Override
    public DatabaseTypeDO getDatabaseType(Long id) {
        return databaseTypeMapper.selectById(id);
    }

    /**
     * 获得数据库类型定义分页
     *
     * @param pageReqVO 分页查询条件
     * @return 数据库类型定义分页结果
     */
    @Override
    public PageResult<DatabaseTypeDO> getDatabaseTypePage(DatabaseTypePageReqVO pageReqVO) {
        return databaseTypeMapper.selectPage(pageReqVO);
    }

    /**
     * 获得数据库类型定义列表
     *
     * @param listReqVO 列表请求参数
     * @return 数据库类型定义列表结果集合
     */
    @Override
    public List<DatabaseTypeDO> getDatabaseTypeList(DatabaseTypeListReqVO listReqVO) {
        if (listReqVO.getStatus() == null) {
            listReqVO.setStatus(CommonStatusEnum.ENABLE.getStatus().shortValue());
        }
        return databaseTypeMapper.selectList(listReqVO);
    }

}

package cn.iocoder.yudao.module.governance.service.level;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据分级 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class SafetyLevelServiceImpl implements SafetyLevelService {

    @Resource
    private SafetyLevelMapper levelMapper;

    /**
     * 创建数据分级
     *
     *  @param createReqVO 创建信息
     *  @return 编号
     */
    @Override
    public Long createLevel(SafetyLevelSaveReqVO createReqVO) {
        // 校验安全等级唯一
        validateSafetyLevelUnique(null, createReqVO.getSafetyLevel());
        // 插入
        SafetyLevelDO level = BeanUtils.toBean(createReqVO, SafetyLevelDO.class);
        level.setSort(createReqVO.getSort() == null ? 0 : createReqVO.getSort());
        levelMapper.insert(level);

        // 返回
        return level.getId();
    }

    /**
     * 更新数据分级
     *
     *  @param updateReqVO 更新信息
     */
    @Override
    public void updateLevel(SafetyLevelSaveReqVO updateReqVO) {
        // 校验存在
        validateLevelExists(updateReqVO.getId());
        // 校验安全等级唯一
        validateSafetyLevelUnique(updateReqVO.getId(), updateReqVO.getSafetyLevel());
        // 更新
        SafetyLevelDO updateObj = BeanUtils.toBean(updateReqVO, SafetyLevelDO.class);
        levelMapper.updateById(updateObj);
    }

    /**
     * 校验安全等级的安全等级在指定范围内唯一。
     */
    private void validateSafetyLevelUnique(Long id, String safetyLevel) {
        SafetyLevelDO level = levelMapper.selectBySafetyLevel(safetyLevel);
        if (level != null && !level.getId().equals(id)) {
            throw exception(LEVEL_CODE_DUPLICATE, safetyLevel);
        }
    }

    /**
     * 删除数据分级
     *
     *  @param id 编号
     */
    @Override
    public void deleteLevel(Long id) {
        // 校验存在
        validateLevelExists(id);
        // 删除
        levelMapper.deleteById(id);
    }

    /**
     * 批量删除数据分级
     *
     *  @param ids 编号
     */
    @Override
    public void deleteLevelListByIds(List<Long> ids) {
        // 删除
        levelMapper.deleteByIds(ids);
    }


    /**
     * 校验等级记录存在，不存在时抛出业务异常。
     */
    private void validateLevelExists(Long id) {
        if (levelMapper.selectById(id) == null) {
            throw exception(LEVEL_NOT_EXISTS);
        }
    }

    /**
     * 获得数据分级
     *
     *  @param id 编号
     *  @return 数据分级
     */
    @Override
    public SafetyLevelDO getLevel(Long id) {
        return levelMapper.selectById(id);
    }

    /**
     * 获得数据分级分页
     *
     *  @param pageReqVO 分页查询
     *  @return 数据分级分页
     */
    @Override
    public PageResult<SafetyLevelDO> getLevelPage(SafetyLevelPageReqVO pageReqVO) {
        return levelMapper.selectPage(pageReqVO);
    }

}

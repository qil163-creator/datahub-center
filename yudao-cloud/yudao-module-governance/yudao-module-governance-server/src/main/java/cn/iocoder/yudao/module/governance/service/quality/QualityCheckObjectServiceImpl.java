package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityCheckObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NAME_DUPLICATE;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;

/**
 * 数据治理-数据质量-检查对象 Service 实现
 *
 * @author Li
 */
@Service
@Validated
public class QualityCheckObjectServiceImpl implements QualityCheckObjectService {

    @Resource
    private QualityCheckObjectMapper checkObjectMapper;

    /**
     * 创建检查对象。
     */
    @Override
    public Long createCheckObject(QualityCheckObjectSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getObjectName());
        QualityCheckObjectDO checkObject = BeanUtils.toBean(createReqVO, QualityCheckObjectDO.class);
        checkObjectMapper.insert(checkObject);
        return checkObject.getId();
    }

    /**
     * 更新检查对象。
     */
    @Override
    public void updateCheckObject(QualityCheckObjectSaveReqVO updateReqVO) {
        validateExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getObjectName());
        checkObjectMapper.updateById(BeanUtils.toBean(updateReqVO, QualityCheckObjectDO.class));
    }

    /**
     * 删除检查对象。
     */
    @Override
    public void deleteCheckObject(Long id) {
        validateExists(id);
        checkObjectMapper.deleteById(id);
    }

    /**
     * 获得检查对象。
     */
    @Override
    public QualityCheckObjectDO getCheckObject(Long id) {
        return checkObjectMapper.selectById(id);
    }

    /**
     * 获得检查对象分页。
     */
    @Override
    public PageResult<QualityCheckObjectDO> getCheckObjectPage(QualityCheckObjectPageReqVO pageReqVO) {
        return checkObjectMapper.selectPage(pageReqVO);
    }

    /**
     * 获得检查对象精简列表。
     */
    @Override
    public List<QualityCheckObjectDO> getCheckObjectSimpleList() {
        return checkObjectMapper.selectList(new LambdaQueryWrapperX<QualityCheckObjectDO>()
                .orderByDesc(QualityCheckObjectDO::getId));
    }

    /**
     * 读取并校验质量检查对象记录存在，不存在时抛出业务异常。
     */
    private void validateExists(Long id) {
        if (id == null || checkObjectMapper.selectById(id) == null) {
            throw exception(QUALITY_CHECK_OBJECT_NOT_EXISTS);
        }
    }

    /**
     * 校验质量检查对象名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, String objectName) {
        QualityCheckObjectDO existing = checkObjectMapper.selectByName(objectName);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(QUALITY_CHECK_OBJECT_NAME_DUPLICATE);
        }
    }

}

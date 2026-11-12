package cn.iocoder.yudao.module.governance.dal.mysql.security;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyMaskSceneDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 动态脱敏场景开关 Mapper
 */
@Mapper
public interface SafetyMaskSceneMapper extends BaseMapperX<SafetyMaskSceneDO> {

    /**
     * 按场景编码读取。
     */
    default SafetyMaskSceneDO selectByScene(String sceneCode) {
        return selectOne(new LambdaQueryWrapperX<SafetyMaskSceneDO>().eq(SafetyMaskSceneDO::getSceneCode, sceneCode));
    }

    /**
     * 按修订号 CAS 更新开关、豁免口径与备注。备注列非空，空值写为空串。
     */
    default int updateByRevision(Long id, Integer expectedRevision, Boolean enabled, String whitelistScene,
                                 String remark) {
        return update(null, new LambdaUpdateWrapper<SafetyMaskSceneDO>()
                .set(SafetyMaskSceneDO::getEnabled, enabled)
                .set(SafetyMaskSceneDO::getWhitelistScene, whitelistScene)
                .set(SafetyMaskSceneDO::getRemark, remark == null ? "" : remark)
                .setSql("revision = revision + 1")
                .eq(SafetyMaskSceneDO::getId, id)
                .eq(SafetyMaskSceneDO::getRevision, expectedRevision));
    }
}

package cn.iocoder.yudao.module.governance.dal.mysql.security;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyKeyRefDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 密钥引用 Mapper
 */
@Mapper
public interface SafetyKeyRefMapper extends BaseMapperX<SafetyKeyRefDO> {

    /**
     * 按编码读取。
     */
    default SafetyKeyRefDO selectByCode(String keyCode) {
        return selectOne(new LambdaQueryWrapperX<SafetyKeyRefDO>().eq(SafetyKeyRefDO::getKeyCode, keyCode));
    }

    /**
     * 全部引用，按编号倒序。
     */
    default List<SafetyKeyRefDO> selectAllOrdered() {
        return selectList(new LambdaQueryWrapperX<SafetyKeyRefDO>().orderByDesc(SafetyKeyRefDO::getId));
    }
}

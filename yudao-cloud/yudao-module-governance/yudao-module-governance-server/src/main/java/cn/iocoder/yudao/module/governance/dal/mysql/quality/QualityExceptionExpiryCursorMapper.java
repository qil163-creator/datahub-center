package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryCursorDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QualityExceptionExpiryCursorMapper extends BaseMapperX<QualityExceptionExpiryCursorDO> {

    /**
     * 按业务条件新增质量例外到期游标的初始化单例状态或数据，返回受影响行数。
     */
    @Insert("INSERT INTO dh_governance_quality_exception_expiry_cursor(id) "
            + "VALUES(1) ON CONFLICT (id) DO NOTHING")
    int initializeSingleton();

    /**
     * 按指定条件读取并锁定质量例外到期游标记录，供事务内一致性校验或修改使用。
     */
    default QualityExceptionExpiryCursorDO selectSingletonForUpdate() {
        return selectOne(new LambdaQueryWrapperX<QualityExceptionExpiryCursorDO>()
                .eq(QualityExceptionExpiryCursorDO::getId, 1L).last("FOR UPDATE"));
    }
}

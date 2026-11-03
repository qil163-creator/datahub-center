package cn.iocoder.yudao.module.governance.dal.mysql.subject;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.subject.SafetyWhitelistSubjectDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 脱敏白名单生效主体 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyWhitelistSubjectMapper extends BaseMapperX<SafetyWhitelistSubjectDO> {

    /**
     * 按白名单编号查询脱敏白名单主体列表。
     */
    default List<SafetyWhitelistSubjectDO> selectListByWhitelistId(Long whitelistId) {
        return selectList(SafetyWhitelistSubjectDO::getWhitelistId, whitelistId);
    }

    /**
     * 按白名单编号删除脱敏白名单主体记录。
     */
    default int deleteByWhitelistId(Long whitelistId) {
        return delete(SafetyWhitelistSubjectDO::getWhitelistId, whitelistId);
    }

    /**
     * 按白名单编号删除脱敏白名单主体记录。
     */
    default int deleteByWhitelistIds(List<Long> whitelistIds) {
        return deleteBatch(SafetyWhitelistSubjectDO::getWhitelistId, whitelistIds);
    }

}

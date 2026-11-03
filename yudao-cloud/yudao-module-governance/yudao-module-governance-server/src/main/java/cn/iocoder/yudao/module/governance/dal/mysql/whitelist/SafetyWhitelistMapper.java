package cn.iocoder.yudao.module.governance.dal.mysql.whitelist;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 脱敏白名单 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyWhitelistMapper extends BaseMapperX<SafetyWhitelistDO> {

    /**
     * 按指定条件读取并锁定脱敏白名单记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_safety_whitelist WHERE id=#{id} AND deleted=0 FOR UPDATE")
    SafetyWhitelistDO selectForUpdate(@Param("id") Long id);

    /**
     * 查询脱敏白名单分页。
     */
    default PageResult<SafetyWhitelistDO> selectPage(SafetyWhitelistPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyWhitelistDO>()
                .likeIfPresent(SafetyWhitelistDO::getName, reqVO.getName())
                .eqIfPresent(SafetyWhitelistDO::getSubjectType, reqVO.getSubjectType())
                .eqIfPresent(SafetyWhitelistDO::getStatus, reqVO.getStatus())
                .eqIfPresent(SafetyWhitelistDO::getApprovalStatus, reqVO.getApprovalStatus())
                .orderByDesc(SafetyWhitelistDO::getId));
    }

    /**
     * 查询生效中的白名单（已审批 + 启用 + 有效期内），按场景过滤（ALL 场景通配）
     */
    default List<SafetyWhitelistDO> selectActiveList(String scene, LocalDateTime now) {
        return selectList(new LambdaQueryWrapperX<SafetyWhitelistDO>()
                .and(w -> w.eq(SafetyWhitelistDO::getApprovalStatus, SafetyWhitelistDO.APPROVAL_STATUS_APPROVED)
                        .or().eq(SafetyWhitelistDO::getApprovalStatus, SafetyWhitelistDO.APPROVAL_STATUS_AUTHORIZED)
                        .in(SafetyWhitelistDO::getAuthorizationBasis,
                                List.of("USER_CONFIRMED_IMPORT", "LOCAL_POLICY_AUTHORIZATION"))
                        .apply("EXISTS (SELECT 1 FROM dh_governance_safety_whitelist_approval a WHERE a.whitelist_id=dh_governance_safety_whitelist.id AND a.deleted=0 AND a.action=CASE WHEN dh_governance_safety_whitelist.authorization_basis='USER_CONFIRMED_IMPORT' THEN 'IMPORT_AUTHORIZATION' ELSE 'LOCAL_POLICY_AUTHORIZATION' END)"))
                .eq(SafetyWhitelistDO::getStatus, SafetyWhitelistDO.STATUS_ENABLE)
                .in(SafetyWhitelistDO::getScene, List.of(SafetyWhitelistDO.SCENE_ALL, scene))
                .and(w -> w.isNull(SafetyWhitelistDO::getEffectiveTime)
                        .or().le(SafetyWhitelistDO::getEffectiveTime, now))
                .and(w -> w.isNull(SafetyWhitelistDO::getExpireTime)
                        .or().ge(SafetyWhitelistDO::getExpireTime, now)));
    }

    /**
     * 统计生效中的白名单数量（已审批 + 启用 + 有效期内）
     */
    default Long selectActiveCount(LocalDateTime now) {
        return selectCount(new LambdaQueryWrapperX<SafetyWhitelistDO>()
                .and(w -> w.eq(SafetyWhitelistDO::getApprovalStatus, SafetyWhitelistDO.APPROVAL_STATUS_APPROVED)
                        .or().eq(SafetyWhitelistDO::getApprovalStatus, SafetyWhitelistDO.APPROVAL_STATUS_AUTHORIZED)
                        .in(SafetyWhitelistDO::getAuthorizationBasis,
                                List.of("USER_CONFIRMED_IMPORT", "LOCAL_POLICY_AUTHORIZATION"))
                        .apply("EXISTS (SELECT 1 FROM dh_governance_safety_whitelist_approval a WHERE a.whitelist_id=dh_governance_safety_whitelist.id AND a.deleted=0 AND a.action=CASE WHEN dh_governance_safety_whitelist.authorization_basis='USER_CONFIRMED_IMPORT' THEN 'IMPORT_AUTHORIZATION' ELSE 'LOCAL_POLICY_AUTHORIZATION' END)"))
                .eq(SafetyWhitelistDO::getStatus, SafetyWhitelistDO.STATUS_ENABLE)
                .and(w -> w.isNull(SafetyWhitelistDO::getEffectiveTime)
                        .or().le(SafetyWhitelistDO::getEffectiveTime, now))
                .and(w -> w.isNull(SafetyWhitelistDO::getExpireTime)
                        .or().ge(SafetyWhitelistDO::getExpireTime, now)));
    }

}

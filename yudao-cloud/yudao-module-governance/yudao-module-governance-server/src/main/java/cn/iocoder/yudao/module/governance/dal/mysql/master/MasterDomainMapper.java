package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDomainPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDomainDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MasterDomainMapper extends BaseMapperX<MasterDomainDO> {

    /**
     * 查询主数据域分页。
     */
    default PageResult<MasterDomainDO> selectPage(MasterDomainPageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<MasterDomainDO>()
                .likeIfPresent(MasterDomainDO::getName, req.getName())
                .likeIfPresent(MasterDomainDO::getCode, req.getCode())
                .eqIfPresent(MasterDomainDO::getStatus, req.getStatus())
                .orderByDesc(MasterDomainDO::getId));
    }

    /** 调用方必须处于真实事务；逻辑删除条件由 MyBatis 拦截器注入。 */
    default MasterDomainDO selectForUpdate(Long id) {
        // 与数据域唯一性查询一样避开 Cursor，主键条件仍只锁当前域。
        return cn.hutool.core.collection.CollUtil.getFirst(selectList(
                new LambdaQueryWrapperX<MasterDomainDO>().eq(MasterDomainDO::getId, id).last("FOR UPDATE")));
    }

    /**
     * 按编码查询主数据域。
     */
    default MasterDomainDO selectByCode(String code) {
        return selectFirstOne(MasterDomainDO::getCode, code);
    }

    /**
     * 按名称查询主数据域。
     */
    default MasterDomainDO selectByName(String name) {
        return selectFirstOne(MasterDomainDO::getName, name);
    }

    /**
     * 查询简要列表。
     */
    default List<MasterDomainDO> selectSimpleList() {
        return selectList(new LambdaQueryWrapperX<MasterDomainDO>()
                .orderByDesc(MasterDomainDO::getId));
    }
}

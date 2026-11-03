package cn.iocoder.yudao.module.governance.dal.mysql.master;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 主数据模型 Mapper
 *
 * @author Li
 */
@Mapper
public interface MasterModelMapper extends BaseMapperX<MasterModelDO> {

    /**
     * 查询主数据模型分页。
     */
    default PageResult<MasterModelDO> selectPage(MasterModelPageReqVO req) {
        return selectPage(req, pageWrapper(req));
    }

    /** 维护入口只展示已发布且结构结果已确认的模型，不能借筛选参数读出草稿。 */
    default PageResult<MasterModelDO> selectMaintenancePage(MasterModelPageReqVO req) {
        return selectPage(req, pageWrapper(req)
                .eq(MasterModelDO::getStatus, MasterModelDO.STATUS_RELEASED)
                .and(w -> w.eq(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_READY)
                        .or().isNull(MasterModelDO::getPublishState)));
    }

    /**
     * 构造主数据模型分页查询条件。
     */
    private LambdaQueryWrapperX<MasterModelDO> pageWrapper(MasterModelPageReqVO req) {
        LambdaQueryWrapperX<MasterModelDO> wrapper = new LambdaQueryWrapperX<MasterModelDO>()
                .eqIfPresent(MasterModelDO::getDomainId, req.getDomainId())
                .eqIfPresent(MasterModelDO::getStatus, req.getStatus())
                .likeIfPresent(MasterModelDO::getTableName, req.getTableName())
                .betweenIfPresent(MasterModelDO::getCreateTime, req.getCreateTime())
                .orderByDesc(MasterModelDO::getId);
        if (cn.hutool.core.util.StrUtil.isNotBlank(req.getName())) {
            wrapper.and(w -> w.like(MasterModelDO::getTableCn, req.getName())
                    .or().like(MasterModelDO::getTableName, req.getName()));
        }
        return wrapper;
    }

    /**
     * 按域编号查询主数据模型数量。
     */
    default Long selectCountByDomainId(Long domainId) {
        return selectCount(MasterModelDO::getDomainId, domainId);
    }

    /**
     * 查询主数据模型统计数量。
     */
    default Long selectStatisticsCount(Long domainId, Integer status) {
        return selectCount(new LambdaQueryWrapperX<MasterModelDO>()
                .eqIfPresent(MasterModelDO::getDomainId, domainId)
                .eqIfPresent(MasterModelDO::getStatus, status));
    }

    /**
     * 按数据库与表名称查询主数据模型。
     */
    default MasterModelDO selectByDatabaseAndTableName(Long databaseId, String tableName) {
        return selectOne(new LambdaQueryWrapperX<MasterModelDO>()
                .eq(MasterModelDO::getDatabaseId, databaseId).eq(MasterModelDO::getTableName, tableName)
                .last("LIMIT 1"));
    }

    /**
     * 按指定条件读取并锁定主数据模型记录，供事务内一致性校验或修改使用。
     */
    default MasterModelDO selectForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MasterModelDO>()
                .eq(MasterModelDO::getId, id).last("FOR UPDATE"));
    }

    /** CAS 领取发布；数据库调用完成后才访问外部数据源，不持有行锁。 */
    default int claimPublish(MasterModelDO model) {
        return update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterModelDO.class)
                .set(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_APPLYING)
                .set(MasterModelDO::getPublishMessage, null)
                .eq(MasterModelDO::getId, model.getId()).eq(MasterModelDO::getStatus, model.getStatus())
                .eq(model.getVersion() != null, MasterModelDO::getVersion, model.getVersion())
                .eq(model.getUpdateTime() != null, MasterModelDO::getUpdateTime, model.getUpdateTime())
                .and(w -> w.eq(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_READY)
                        .or().isNull(MasterModelDO::getPublishState)));
    }

    /**
     * 标记发布未知。
     */
    default int markPublishUnknown(Long id) {
        return update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterModelDO.class)
                .set(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_UNKNOWN)
                .set(MasterModelDO::getPublishMessage, "物理表结果待核查，请确认数据源表结构后核查发布")
                .eq(MasterModelDO::getId, id)
                .eq(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_APPLYING));
    }

    /** 外部事务已确认未提交，保留结构草稿供修改和重试。 */
    default int resetPublishAfterRollback(Long id) {
        return update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterModelDO.class)
                .set(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_READY)
                .set(MasterModelDO::getPublishMessage, null)
                .eq(MasterModelDO::getId, id)
                .eq(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_APPLYING));
    }

    /**
     * 完成发布。
     */
    default int completePublish(Long id, boolean verification) {
        var wrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(MasterModelDO.class)
                .set(MasterModelDO::getStatus, MasterModelDO.STATUS_RELEASED)
                .set(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_READY)
                .set(MasterModelDO::getPublishMessage, null)
                .set(MasterModelDO::getReleaseTime, java.time.LocalDateTime.now())
                .set(MasterModelDO::getReleaseUserId,
                        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId())
                .eq(MasterModelDO::getId, id);
        if (verification) {
            wrapper.in(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_APPLYING, MasterModelDO.PUBLISH_UNKNOWN);
        } else {
            wrapper.eq(MasterModelDO::getPublishState, MasterModelDO.PUBLISH_APPLYING);
        }
        return update(null, wrapper);
    }

    /**
     * 按目录编号查询主数据模型。
     */
    default MasterModelDO selectByCatalogId(Long catalogId) {
        return selectOne(new LambdaQueryWrapperX<MasterModelDO>()
                .eq(MasterModelDO::getCatalogId, catalogId)
                .last("LIMIT 1"));
    }

    /**
     * 按表名称查询主数据模型。
     */
    default MasterModelDO selectByTableName(String tableName) {
        return selectOne(new LambdaQueryWrapperX<MasterModelDO>()
                .eq(MasterModelDO::getTableName, tableName)
                .last("LIMIT 1"));
    }

    /**
     * 按状态查询主数据模型列表。
     */
    default List<MasterModelDO> selectListByStatus(Integer status) {
        return selectList(MasterModelDO::getStatus, status);
    }

}

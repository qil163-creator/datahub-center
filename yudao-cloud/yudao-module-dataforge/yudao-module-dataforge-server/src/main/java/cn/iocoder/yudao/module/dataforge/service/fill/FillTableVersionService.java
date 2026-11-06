package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTableVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;

import java.util.List;

/**
 * 在线填报-模板版本 Service 接口
 *
 * @author Li
 */
public interface FillTableVersionService {

    /**
     * 在目标表建表前，根据本次发布的字段配置构建不可变快照。
     */
    FillTableVersionDO buildReleaseSnapshot(FillTableDO table, List<FillTableColumnDO> columns);

    /**
     * 目标表创建成功后，同一事务内保存快照并发布填报表。
     */
    void completeTableRelease(FillTableVersionDO snapshot);

    /**
     * 获得模板版本（不存在抛异常）
     */
    FillTableVersionDO getTableVersion(Long id);

    /**
     * 获得模板版本，并校验可被新任务引用（不存在或已停用抛异常）
     */
    FillTableVersionDO getActiveTableVersion(Long id);

    /**
     * 获得填报表的版本列表（按版本号倒序）
     */
    List<FillTableVersionDO> getTableVersionListByTableId(Long tableId);

}

package cn.iocoder.yudao.module.dataforge.dal.mysql.fullsync;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fullsync.FullSyncWizardDO;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 整库同步向导 Mapper。
 */
@Mapper
public interface FullSyncWizardMapper extends BaseMapperX<FullSyncWizardDO> {

    /**
     * 锁定读取向导，避免步骤和提交状态并发覆盖。
     *
     * @param id 向导编号
     * @return 向导草稿
     */
    @Select("SELECT * FROM dh_dataforge_full_sync_wizard WHERE id=#{id} AND deleted=0 FOR UPDATE")
    FullSyncWizardDO lock(@Param("id") Long id);
}


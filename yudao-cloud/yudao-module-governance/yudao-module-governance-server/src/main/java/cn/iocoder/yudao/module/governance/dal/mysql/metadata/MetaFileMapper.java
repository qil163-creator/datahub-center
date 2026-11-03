package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaFileDO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file.MetaFilePageReqVO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MetaFileMapper extends BaseMapperX<MetaFileDO> {

    /**
     * 锁定数据源。
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(1900002200, hashtext(CAST(#{id} AS text)))")
    Integer lockDatasource(@Param("id") Long id);

    /**
     * 查询文件元数据分页。
     */
    default PageResult<MetaFileDO> selectPage(MetaFilePageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<MetaFileDO>()
                .likeIfPresent(MetaFileDO::getName, req.getName())
                .eqIfPresent(MetaFileDO::getFileType, req.getFileType())
                .eqIfPresent(MetaFileDO::getDatasourceId, req.getDatasourceId())
                .eqIfPresent(MetaFileDO::getSourceSystemId, req.getSourceSystemId())
                .eqIfPresent(MetaFileDO::getParentPath, req.getPath())
                .eqIfPresent(MetaFileDO::getObjectStatus, req.getObjectStatus())
                .isNull(Boolean.TRUE.equals(req.getSourceSystemMissing()), MetaFileDO::getSourceSystemId)
                .orderByAsc(MetaFileDO::getId));
    }
}

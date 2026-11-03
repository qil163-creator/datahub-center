package cn.iocoder.yudao.module.infra.dal.mysql.file;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.ManagedFileDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ManagedFileMapper extends BaseMapperX<ManagedFileDO> {
    @Select("SELECT * FROM infra_managed_file WHERE id=#{id} AND deleted=0 FOR UPDATE")
    ManagedFileDO lock(@Param("id") Long id);
    default ManagedFileDO find(String owner, String operationKey) {
        return selectOne(new LambdaQueryWrapperX<ManagedFileDO>().eq(ManagedFileDO::getOwnerService, owner)
                .eq(ManagedFileDO::getOperationKey, operationKey));
    }

    @Update("UPDATE infra_managed_file SET state=#{next},update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND state=#{expected} AND deleted=0")
    int transition(@Param("id") Long id, @Param("expected") String expected, @Param("next") String next);

    @Update("UPDATE infra_managed_file SET state='READY',file_id=#{fileId},update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND state='UPLOADING' AND deleted=0")
    int ready(@Param("id") Long id, @Param("fileId") Long fileId);

    @Select("<script>SELECT * FROM infra_managed_file WHERE deleted=0 AND state='DELETED' "
            + "AND update_time &lt; #{cutoff} "
            + "<if test='afterTime != null'>AND (update_time &gt; #{afterTime} OR (update_time=#{afterTime} AND id &gt; #{afterId})) </if>"
            + "ORDER BY update_time,id LIMIT #{limit}</script>")
    List<ManagedFileDO> cleanupCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("afterTime") LocalDateTime afterTime,
                                          @Param("afterId") Long afterId, @Param("limit") int limit);

    @Update("UPDATE infra_managed_file SET update_time=CURRENT_TIMESTAMP WHERE id=#{id} "
            + "AND state='DELETED' AND deleted=0")
    int touchCleanup(@Param("id") Long id);
}

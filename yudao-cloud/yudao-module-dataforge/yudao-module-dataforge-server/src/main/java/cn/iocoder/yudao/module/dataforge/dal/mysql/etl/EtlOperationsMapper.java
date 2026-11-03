package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlOperationsSnapshot;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EtlOperationsMapper {

    /** 控制面跨租户聚合，不返回对象编号，也不执行跨业务操作。 */

    @Select("""
            WITH state AS (
              SELECT 'commit' kind,create_time,commit_status IN ('READY','COMMITTING','UNKNOWN','RETRY') pending,
                     commit_status='FAILED' failed FROM dh_dataforge_etl_target_batch WHERE deleted=0
              UNION ALL SELECT 'quality',create_time,quality_status IN ('PENDING','RUNNING'),
                     quality_status IN ('FAILED','UNAVAILABLE') FROM dh_dataforge_etl_target_batch WHERE deleted=0
              UNION ALL SELECT 'evidence',create_time,registration_status IN ('PENDING','REGISTERING','RETRY'),
                     registration_status='FAILED' FROM dh_dataforge_etl_quality_batch WHERE deleted=0
              UNION ALL SELECT 'recheck',create_time,attach_status IN ('PENDING','ATTACHING','RETRY'),attach_status='FAILED'
                     FROM dh_dataforge_etl_replay WHERE deleted=0
            )
            SELECT kind,count(*) FILTER(WHERE pending) pending,count(*) FILTER(WHERE failed) failed,
                   COALESCE(GREATEST(0,EXTRACT(EPOCH FROM clock_timestamp()-min(create_time) FILTER(WHERE pending))),0) oldest_age
            FROM state GROUP BY kind
            """)
    List<EtlOperationsSnapshot> snapshot();
}

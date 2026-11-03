package cn.iocoder.yudao.module.dataforge.dal.mysql.etl;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlReplayDO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface EtlReplayMapper extends BaseMapperX<EtlReplayDO> {

    /** 全局控制面扫描仅返回标识信息。 */

    @Select("SELECT p.id FROM dh_dataforge_etl_replay p JOIN dh_dataforge_etl_record r "
            + "ON r.id=p.record_id WHERE p.deleted=0 AND r.deleted=0 AND r.run_status=3 "
            + "AND p.attach_status IN ('PENDING','RETRY','ATTACHING') "
            + "AND (p.next_attempt_at IS NULL OR p.next_attempt_at<=clock_timestamp()) "
            + "AND (p.lease_until IS NULL OR p.lease_until<=clock_timestamp()) ORDER BY p.id LIMIT 20")
    List<EtlReplayDO> selectPending();
}

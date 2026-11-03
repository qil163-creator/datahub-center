package cn.iocoder.yudao.module.governance.dal.mysql.permission;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 与策略更新处于同一本地事务；已回滚的修改绝不产生已提交审计记录。 */
@Mapper
public interface SafetyWritePolicyAuditMapper {

    /**
     * 按业务条件新增安全写入策略审计的记录状态或数据，返回受影响行数。
     */
    @Insert("""
            INSERT INTO dh_governance_safety_write_audit
              (database_id, schema_name, table_name, role_id, actor_id, before_policy, after_policy, reason)
            VALUES (#{database}, #{schema}, #{table}, #{role}, #{actor}, #{before}, #{after}, #{reason})
            """)
    int record(@Param("database") Long database, @Param("schema") String schema,
            @Param("table") String table, @Param("role") Long role, @Param("actor") Long actor,
            @Param("before") String before, @Param("after") String after, @Param("reason") String reason);
}

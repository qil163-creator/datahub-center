package cn.iocoder.yudao.module.governance.dal.mysql.whitelist;

import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.SafetyWhitelistWorkbenchPageReqVO;

import java.util.List;

import org.apache.ibatis.annotations.*;

@Mapper
public interface SafetyWhitelistWorkbenchMapper {

    String FROM = " FROM dh_governance_safety_whitelist w LEFT JOIN dh_governance_safety_category c ON c.id=w.object_id AND c.deleted=0 WHERE w.deleted=0 AND w.object_type='CATEGORY' AND w.subject_type='USER' AND w.scene='QUERY' ";
    String FILTER = "<if test='q.name != null and q.name != &quot;&quot;'> AND w.name ILIKE '%' || #{q.name} || '%' </if><if test='q.categoryId != null'> AND w.object_id=#{q.categoryId} </if><if test='q.status != null'> AND w.status=#{q.status} </if>";
    String ID = "CASE WHEN w.source_record_id ~ '^[0-9]{1,64}$' THEN w.source_record_id::numeric ELSE w.id::numeric END";
    String SORT = " ORDER BY <choose><when test='q.sortField == &quot;id&quot;'>" + ID + "</when><otherwise>COALESCE(w.source_create_time,w.create_time)</otherwise></choose> <choose><when test='q.sortOrder == &quot;asc&quot;'>ASC</when><otherwise>DESC</otherwise></choose>, w.id DESC";

    /**
     * 按筛选条件分页查询脱敏白名单工作台。
     */
    @Select("<script>SELECT w.*,c.name AS category_name,c.source_record_id AS category_source_record_id" + FROM + FILTER + SORT + " LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<SafetyWhitelistWorkbenchRow> page(@Param("q") SafetyWhitelistWorkbenchPageReqVO q,
            @Param("offset") long offset);

    /**
     * 按指定条件统计脱敏白名单工作台的记录数。
     */
    @Select("<script>SELECT count(*)" + FROM + FILTER + "</script>")
    long count(@Param("q") SafetyWhitelistWorkbenchPageReqVO q);

    /**
     * 分类白名单详情。
     */
    @Select("SELECT w.*,c.name AS category_name,c.source_record_id AS category_source_record_id" + FROM + " AND w.id=#{id}")
    SafetyWhitelistWorkbenchRow get(@Param("id") Long id);
}

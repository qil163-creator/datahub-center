package cn.iocoder.yudao.module.governance.dal.mysql.rule;

import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.SafetyRuleWorkbenchPageReqVO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 展示掩码工作台的安全读投影；通用算法及密钥配置继续由原规则接口管理。 */
@Mapper
public interface SafetyRuleWorkbenchMapper {

    String FROM = """
            FROM dh_governance_safety_rule r
            JOIN dh_governance_safety_rule_ver v ON v.id=r.current_ver_id AND v.rule_id=r.id AND v.deleted=0 AND v.status='PUBLISHED'
            JOIN dh_governance_safety_category c ON c.rule_id=r.id AND c.deleted=0
            WHERE r.deleted=0 AND r.method_code='MASK'
              AND v.config_json->>'maskType'='2' AND v.config_json->>'replaceRule'='1'
              AND jsonb_typeof(v.config_json->'intervals')='array'
              AND (SELECT count(*) FROM dh_governance_safety_category linked WHERE linked.rule_id=r.id AND linked.deleted=0)=1
            """;
    String FIELDS = """
            r.*,c.id AS category_id,c.revision AS category_revision,c.name AS category_name,
            c.source_record_id AS category_source_record_id,v.version_no AS current_version_no,
            v.config_json::text AS config_json,v.config_checksum
            """;
    String FILTER = """
            <if test="req.ruleName != null and req.ruleName != ''"> AND r.rule_name LIKE CONCAT('%',#{req.ruleName},'%') </if>
            <if test="req.categoryId != null"> AND c.id=#{req.categoryId} </if>
            <if test="req.status != null"> AND r.status=#{req.status} </if>
            <if test="req.maskType != null"> AND v.config_json->>'maskType'=#{req.maskType} </if>
            <if test="req.applicationScene != null">
              AND v.config_json->'applicationScene' @> jsonb_build_array(CAST(#{req.applicationScene} AS text))
            </if>
            """;

    /**
     * 查询脱敏规则工作台分页。
     */
    @Select("<script>SELECT " + FIELDS + FROM + FILTER + """
            ORDER BY
            <choose><when test="req.sortField == 'createTime'">COALESCE(r.source_create_time,r.create_time)</when>
            <otherwise>CASE WHEN r.source_record_id ~ '^[0-9]{1,64}$' THEN r.source_record_id::numeric ELSE r.id::numeric END</otherwise></choose>
            <choose><when test="req.sortOrder == 'asc'">ASC</when><otherwise>DESC</otherwise></choose>,r.id
            <choose><when test="req.sortOrder == 'asc'">ASC</when><otherwise>DESC</otherwise></choose>
            LIMIT #{req.pageSize} OFFSET #{offset}
            </script>
            """)
    List<SafetyRuleWorkbenchRow> selectPage(@Param("req") SafetyRuleWorkbenchPageReqVO request,
            @Param("offset") long offset);

    /**
     * 按指定条件统计脱敏规则工作台的记录数。
     */
    @Select("<script>SELECT count(*) " + FROM + FILTER + "</script>")
    long count(@Param("req") SafetyRuleWorkbenchPageReqVO request);

    /**
     * 获得展示脱敏规则详情。
     */
    @Select("SELECT " + FIELDS + FROM + " AND r.id=#{id}")
    SafetyRuleWorkbenchRow get(@Param("id") Long id);
}

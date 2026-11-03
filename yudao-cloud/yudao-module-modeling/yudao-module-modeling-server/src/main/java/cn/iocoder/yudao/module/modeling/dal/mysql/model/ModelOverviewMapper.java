package cn.iocoder.yudao.module.modeling.dal.mysql.model;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 建模领域聚合查询；与现有模型列表相同，资源查询权限内按平台范围读取。 */
@Mapper
public interface ModelOverviewMapper {

    /**
     * 查询模型概览的概念数量。
     *
     * @param since 起始时间
     * @param until 截止时间
     * @return 模型数量
     */
    @Select("""
            SELECT count(*) AS total, count(*) FILTER (WHERE create_time BETWEEN #{since} AND #{until}) AS recent
            FROM dh_modeling_concept_model WHERE deleted = 0
            """)
    ModelCount selectConceptCounts(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /**
     * 查询模型概览的指标数量。
     *
     * @param since 起始时间
     * @param until 截止时间
     * @return 模型数量
     */
    @Select("""
            SELECT count(*) AS total, count(*) FILTER (WHERE create_time BETWEEN #{since} AND #{until}) AS recent
            FROM dh_modeling_indicator_model WHERE deleted = 0
            """)
    ModelCount selectIndicatorCounts(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Data
    class ModelCount {

        private Long total;

        private Long recent;
    }

    String LAYER_DISTRIBUTION = """
            SELECT CAST(l.id AS VARCHAR) AS key, l.layer_name AS name, l.layer_code AS code,
                   COALESCE(c.count, 0) AS count, COALESCE(l.sort_order, 0) AS sort_order
            FROM dh_modeling_dw_layer l
            LEFT JOIN model_counts c ON c.layer_id = l.id
            WHERE l.deleted = 0 AND l.node_type = 'LAYER'
            UNION ALL
            SELECT 'unassigned', '未分层', NULL, SUM(c.count), 2147483647
            FROM model_counts c
            WHERE NOT EXISTS (
                SELECT 1 FROM dh_modeling_dw_layer l
                WHERE l.id = c.layer_id AND l.deleted = 0 AND l.node_type = 'LAYER'
            )
            HAVING SUM(c.count) > 0
            ORDER BY sort_order, key
            """;

    /**
     * 查询模型概览的分层分布。
     *
     * @return 模型概览的分层分布结果集合
     */
    @Select("""
            WITH model_counts AS (
                SELECT m.layer_id, COUNT(*) AS count FROM dh_modeling_logic_model m
                WHERE m.deleted = 0 AND m.status <> 'DISCARDED'
                GROUP BY m.layer_id
            )
            """ + LAYER_DISTRIBUTION)
    List<LayerCount> selectLayerDistribution();

    /**
     * 查询模型概览的物化分布。
     *
     * @return 模型概览的物化分布结果集合
     */
    @Select("""
            WITH model_counts AS (
                SELECT m.layer_id, COUNT(*) AS count
                FROM dh_modeling_model_materialized p
                JOIN dh_modeling_logic_model m ON m.id = p.model_id
                    AND m.deleted = 0 AND m.status <> 'DISCARDED'
                WHERE p.deleted = 0 AND p.status = 'SUCCESS'
                GROUP BY m.layer_id
            )
            """ + LAYER_DISTRIBUTION)
    List<LayerCount> selectMaterializedDistribution();

    /**
     * 查询模型概览的近期已发布数量。
     *
     * @param since 起始时间
     * @param until 截止时间
     * @return SELECT近期已发布数量数值
     */
    @Select("""
            SELECT COUNT(*) FROM dh_modeling_logic_model m
            WHERE m.deleted = 0 AND m.status = 'PUBLISHED'
                AND m.published_time >= #{since} AND m.published_time <= #{until}
            """)
    Long selectRecentlyPublishedCount(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    /**
     * 查询模型概览的近期活动。
     *
     * @return 模型概览的近期活动结果集合
     */
    @Select("""
            SELECT * FROM (
                (SELECT CONCAT('model-', m.id) AS id, m.id AS model_id,
                    'CREATE' AS type,
                    m.name AS model_name,
                    m.create_time AS occurred_at
                 FROM dh_modeling_logic_model m
                 WHERE m.deleted = 0 AND m.status <> 'DISCARDED'
                    AND m.create_time IS NOT NULL
                 ORDER BY occurred_at DESC, m.id DESC LIMIT 8)
                UNION ALL
                (SELECT CONCAT('publish-', m.id), m.id, 'PUBLISH', m.name, m.published_time
                 FROM dh_modeling_logic_model m
                 WHERE m.deleted = 0 AND m.status = 'PUBLISHED' AND m.published_time IS NOT NULL
                 ORDER BY m.published_time DESC, m.id DESC LIMIT 8)
            ) recent
            ORDER BY occurred_at DESC, id DESC LIMIT 8
            """)
    List<RecentActivity> selectRecentActivities();

    @Data
    class LayerCount {

        private String key;

        private String name;

        private String code;

        private Long count;
    }

    @Data
    class RecentActivity {

        private String id;

        private Long modelId;

        private String type;

        private String modelName;


        private LocalDateTime occurredAt;
    }
}

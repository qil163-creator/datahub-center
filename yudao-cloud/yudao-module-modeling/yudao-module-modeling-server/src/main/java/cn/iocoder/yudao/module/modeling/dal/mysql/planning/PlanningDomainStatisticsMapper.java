package cn.iocoder.yudao.module.modeling.dal.mysql.planning;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 规划对象按平台可见范围汇总，业务实体只统计独立领域记录。 */
@Mapper
public interface PlanningDomainStatisticsMapper {

    /**
     * 查询规划域统计的主体数量。
     *
     * @return 规划域统计的主体数量结果集合
     */
    @Select("SELECT data_domain_id AS id, count(*) AS count FROM dh_modeling_subject_domain WHERE deleted = 0 GROUP BY data_domain_id")
    List<CountRow> selectSubjectCounts();

    /**
     * 查询规划域统计的板块实体数量。
     *
     * @return 规划域统计的板块实体数量结果集合
     */
    @Select("SELECT data_domain_id AS id, count(*) FILTER (WHERE entity_type = 'OBJECT') AS count, "
            + "count(*) FILTER (WHERE entity_type = 'ACTIVITY') AS activity_count "
            + "FROM dh_modeling_business_entity WHERE deleted = 0 GROUP BY data_domain_id")
    List<CountRow> selectBoardEntityCounts();

    /**
     * 查询规划域统计的主体实体数量。
     *
     * @return 规划域统计的主体实体数量结果集合
     */
    @Select("SELECT subject_domain_id AS id, count(*) AS count FROM dh_modeling_business_entity WHERE deleted = 0 AND subject_domain_id IS NOT NULL GROUP BY subject_domain_id")
    List<CountRow> selectSubjectEntityCounts();

    @Data
    class CountRow {

        private Long id;

        private Long count;

        private Long activityCount;
    }
}

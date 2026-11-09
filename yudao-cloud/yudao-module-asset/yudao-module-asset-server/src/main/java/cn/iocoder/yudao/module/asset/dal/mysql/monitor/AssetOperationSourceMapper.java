package cn.iocoder.yudao.module.asset.dal.mysql.monitor;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资产运营指标的只读聚合来源：使用行为日志、集市收藏、申请授权与问题反馈。
 *
 * <p>全部按资产分组一次性汇总，避免逐资产查询；只读取未删除记录。</p>
 */
@Mapper
public interface AssetOperationSourceMapper {

    /** 窗口内按资产、行为汇总次数与去重用户数 */
    @Select("SELECT asset_id AS assetId, action, count(*) AS total, count(DISTINCT user_id) AS users"
            + " FROM dh_asset_usage_log WHERE deleted = 0 AND create_time >= #{since}"
            + " GROUP BY asset_id, action")
    List<ActionRow> selectActionStats(@Param("since") LocalDateTime since);

    /** 每个资产最近一次真实使用（申请、预览/查询；浏览不算使用） */
    @Select("SELECT asset_id AS assetId, max(create_time) AS lastTime FROM dh_asset_usage_log"
            + " WHERE deleted = 0 AND action IN (2, 3) GROUP BY asset_id")
    List<LastRow> selectLastUsed();

    /** 每个资产的累计收藏数 */
    @Select("SELECT asset_id AS assetId, count(*) AS total FROM dh_asset_market_favorite"
            + " WHERE deleted = 0 GROUP BY asset_id")
    List<CountRow> selectFavoriteCounts();

    /** 每个资产当前有效授权（已通过且未过期）的数量与覆盖部门数 */
    @Select("SELECT asset_id AS assetId, count(*) AS total, count(DISTINCT apply_dept_id) AS depts"
            + " FROM dh_market_resource_apply WHERE deleted = 0 AND status = 2"
            + " AND (apply_end_time IS NULL OR apply_end_time > #{now}) GROUP BY asset_id")
    List<GrantRow> selectActiveGrants(@Param("now") LocalDateTime now);

    /** 曾经通过授权的接口资产与数据 API 的对应关系，用于归集开放网关调用 */
    @Select("SELECT DISTINCT asset_id AS assetId, api_id AS apiId FROM dh_market_resource_apply"
            + " WHERE deleted = 0 AND api_id IS NOT NULL AND status IN (2, 5, 6)")
    List<ApiBindingRow> selectApiBindings();

    /** 窗口内问题反馈（核查类评论）数量 */
    @Select("SELECT asset_id AS assetId, count(*) AS total FROM dh_market_resource_comment"
            + " WHERE deleted = 0 AND comment_kind = 2 AND create_time >= #{since} GROUP BY asset_id")
    List<CountRow> selectFeedbackCounts(@Param("since") LocalDateTime since);

    /** 单个资产窗口内每日各行为次数，用于趋势图 */
    @Select("SELECT CAST(create_time AS date) AS day, action, count(*) AS total FROM dh_asset_usage_log"
            + " WHERE deleted = 0 AND asset_id = #{assetId} AND create_time >= #{since}"
            + " GROUP BY CAST(create_time AS date), action")
    List<DailyRow> selectDailyActions(@Param("assetId") Long assetId, @Param("since") LocalDateTime since);

    /** 全部资产窗口内活跃（浏览或使用）的去重用户数 */
    @Select("SELECT count(DISTINCT user_id) FROM dh_asset_usage_log WHERE deleted = 0 AND create_time >= #{since}")
    Long selectActiveUserCount(@Param("since") LocalDateTime since);

    @Data
    class ActionRow {
        private Long assetId;
        private Integer action;
        private Long total;
        private Long users;
    }

    @Data
    class LastRow {
        private Long assetId;
        private LocalDateTime lastTime;
    }

    @Data
    class CountRow {
        private Long assetId;
        private Long total;
    }

    @Data
    class GrantRow {
        private Long assetId;
        private Long total;
        private Long depts;
    }

    @Data
    class ApiBindingRow {
        private Long assetId;
        private Long apiId;
    }

    @Data
    class DailyRow {
        private LocalDate day;
        private Integer action;
        private Long total;
    }
}

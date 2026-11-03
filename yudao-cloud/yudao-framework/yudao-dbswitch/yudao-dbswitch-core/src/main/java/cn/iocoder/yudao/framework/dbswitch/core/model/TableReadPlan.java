package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 明确的关系覆盖范围。消费方必须使用 fromSql，不得再次加入已覆盖的子关系。 */
public record TableReadPlan(ReadMode mode, List<Read> reads, Map<Relation, Relation> coveredSelections) {

    public enum ReadMode {INCLUDE_DESCENDANTS, ONLY}

    public record Relation(String schema, String table) {

        /**
         * 校验构造参数并初始化 {@code Relation}。
         */
        public Relation {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(table, "table");
        }
    }

    public record Read(Relation relation, String fromSql, List<Relation> coveredRelations) {

        /**
         * 校验构造参数并初始化 {@code Read}。
         */
        public Read {
            Objects.requireNonNull(relation, "relation");
            Objects.requireNonNull(fromSql, "fromSql");
            coveredRelations = List.copyOf(coveredRelations);
        }
    }

    /**
     * 校验构造参数并初始化 {@code TableReadPlan}。
     */
    public TableReadPlan {
        Objects.requireNonNull(mode, "mode");
        reads = List.copyOf(reads);
        coveredSelections = Map.copyOf(coveredSelections);
    }
}

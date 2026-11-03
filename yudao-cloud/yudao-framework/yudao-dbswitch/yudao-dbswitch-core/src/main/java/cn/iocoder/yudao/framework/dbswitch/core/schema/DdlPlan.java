package cn.iocoder.yudao.framework.dbswitch.core.schema;

import java.util.*;

/** 仅供预览。执行仍需要目标授权、锁保护下的重新观测和执行后检查。 */
public record DdlPlan(String beforeFingerprint, String desiredFingerprint,
                      List<Step> steps, List<Diagnostic> diagnostics) {

    public enum Kind {CREATE_TABLE, ADD_COLUMN, WIDEN_TYPE, SET_DEFAULT, DROP_DEFAULT, RELAX_NULLABILITY}

    public enum Risk {LOCK, TABLE_REWRITE, IMPLICIT_COMMIT, REVERSE_CAN_LOSE_DATA}

    public enum Transactionality {TRANSACTIONAL, IMPLICIT_COMMIT}

    public enum RollbackCompleteness {STRUCTURE_ONLY_DATA_NOT_RESTORED, CONDITIONAL_STRUCTURE}

    public record Diagnostic(String code, String objectName) {

    }

    public record Step(String id, Kind kind, String objectName, String sql, List<String> dependsOn,
                       Set<Risk> risks, Transactionality transactionality, String reverseSql,
                       RollbackCompleteness rollbackCompleteness) {

        /**
         * 初始化 {@code Step}。
         */
        public Step {
            dependsOn = List.copyOf(dependsOn);
            risks = Set.copyOf(risks);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "DdlStep[id=" + id + ", kind=" + kind + "]";
        }
    }

    /**
     * 初始化 {@code DdlPlan}。
     */
    public DdlPlan {
        steps = ordered(steps);
        diagnostics = List.copyOf(diagnostics);
    }

    /**
     * 检查当前资源是否已经准备就绪。
     */
    public boolean ready() {
        return diagnostics.isEmpty();
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "DdlPlan[steps=" + steps.size() + ", diagnostics=" + diagnostics.size() + "]";
    }

    /**
     * 按既定比较规则排列当前键或字段集合。
     */
    public static List<Step> ordered(List<Step> input) {
        Map<String, Step> remaining = new TreeMap<>();
        for (Step step : input) {
            if (remaining.putIfAbsent(step.id(), step) != null) {
                throw new IllegalArgumentException("Duplicate DDL step identity");
            }
        }
        for (Step step : input) {
            if (!remaining.keySet().containsAll(step.dependsOn())) {
                throw new IllegalArgumentException("Unknown DDL dependency");
            }
        }
        Set<String> done = new HashSet<>();
        List<Step> sorted = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Step next = remaining.values().stream().filter(step -> done.containsAll(step.dependsOn())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Cyclic DDL dependency"));
            sorted.add(next);
            remaining.remove(next.id());
            done.add(next.id());
        }
        return List.copyOf(sorted);
    }
}

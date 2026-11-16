package cn.iocoder.yudao.module.governance.service.master.match;

import java.util.*;

/**
 * 主数据疑似重复识别：按分块字段分组后两两加权打分，相似度达到阈值的记录对按传递关系聚成疑似重复组。
 * 只做计算，不读写数据库；输入行顺序决定组内成员顺序。
 */
public final class MasterMatcher {

    private MasterMatcher() {
    }

    /** 一对记录的相似度与逐字段得分（只含两侧都有值的字段） */
    public record Pair(int left, int right, double score, Map<String, Double> fieldScores) {
    }

    /** 疑似重复组：成员为输入行下标（升序），pairs 为组内达到阈值的记录对 */
    public record Group(List<Integer> members, List<Pair> pairs, double maxScore) {
    }

    /**
     * @param groups            疑似重复组，按最高相似度降序
     * @param comparedPairs     实际比较的记录对数
     * @param unblockedRows     因分块字段为空而未参与比较的记录数
     * @param oversizedClusters 成员超过上限而被跳过的簇数
     */
    public record Result(List<Group> groups, long comparedPairs, int unblockedRows, int oversizedClusters) {
    }

    /** 按分块字段划分的比较块；分块字段为空的记录不参与比较 */
    static Map<String, List<Integer>> blocks(List<Map<String, Object>> rows, List<String> blockingFields) {
        Map<String, List<Integer>> blocks = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder key = new StringBuilder();
            boolean complete = true;
            for (String field : blockingFields) {
                Object value = rows.get(i).get(field);
                if (MasterSimilarity.blank(value)) {
                    complete = false;
                    break;
                }
                key.append(MasterSimilarity.normalizeExact(value)).append('\u0001');
            }
            if (complete) {
                blocks.computeIfAbsent(key.toString(), ignored -> new ArrayList<>()).add(i);
            }
        }
        return blocks;
    }

    /** 估算比较次数（不实际打分），用于在读取后、计算前拒绝过大的任务 */
    public static long estimateComparisons(List<Map<String, Object>> rows, MasterMatchConfig config) {
        long total = 0;
        for (List<Integer> block : blocks(rows, config.blockingFields()).values()) {
            long size = block.size();
            total += size * (size - 1) / 2;
        }
        return total;
    }

    /**
     * 识别疑似重复组。
     *
     * @throws IllegalArgumentException 比较次数超过上限
     */
    public static Result match(List<Map<String, Object>> rows, MasterMatchConfig config) {
        if (estimateComparisons(rows, config) > MasterMatchConfig.MAX_COMPARISONS) {
            throw new IllegalArgumentException("比较次数超过上限");
        }
        Map<String, List<Integer>> blocks = blocks(rows, config.blockingFields());
        int blocked = blocks.values().stream().mapToInt(List::size).sum();
        double threshold = config.threshold().doubleValue();
        int totalWeight = config.fields().stream().mapToInt(MasterMatchConfig.Field::weight).sum();
        int[] parent = new int[rows.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        List<Pair> pairs = new ArrayList<>();
        long compared = 0;
        for (List<Integer> block : blocks.values()) {
            for (int x = 0; x < block.size(); x++) {
                for (int y = x + 1; y < block.size(); y++) {
                    compared++;
                    Pair pair = score(block.get(x), block.get(y), rows, config, totalWeight);
                    if (pair != null && pair.score() >= threshold) {
                        pairs.add(pair);
                        union(parent, pair.left(), pair.right());
                    }
                }
            }
        }
        Map<Integer, List<Integer>> clusters = new TreeMap<>();
        Set<Integer> linked = new TreeSet<>();
        pairs.forEach(pair -> {
            linked.add(pair.left());
            linked.add(pair.right());
        });
        for (int index : linked) {
            clusters.computeIfAbsent(find(parent, index), ignored -> new ArrayList<>()).add(index);
        }
        Map<Integer, List<Pair>> pairsByRoot = new HashMap<>();
        for (Pair pair : pairs) {
            pairsByRoot.computeIfAbsent(find(parent, pair.left()), ignored -> new ArrayList<>()).add(pair);
        }
        List<Group> groups = new ArrayList<>();
        int oversized = 0;
        for (Map.Entry<Integer, List<Integer>> cluster : clusters.entrySet()) {
            List<Integer> members = cluster.getValue();
            if (members.size() > MasterMatchConfig.MAX_GROUP_SIZE) {
                oversized++;
                continue;
            }
            List<Pair> groupPairs = pairsByRoot.getOrDefault(cluster.getKey(), List.of());
            double max = groupPairs.stream().mapToDouble(Pair::score).max().orElse(0);
            groups.add(new Group(List.copyOf(members), List.copyOf(groupPairs), max));
        }
        groups.sort(Comparator.comparingDouble(Group::maxScore).reversed()
                .thenComparing(group -> group.members().getFirst()));
        return new Result(List.copyOf(groups), compared, rows.size() - blocked, oversized);
    }

    /**
     * 两条记录的加权相似度：只统计两侧都有值的字段；有值字段的权重不足总权重一半时不判定（返回 null），
     * 避免仅凭少数字段相同就认定重复。
     */
    static Pair score(int left, int right, List<Map<String, Object>> rows, MasterMatchConfig config,
            int totalWeight) {
        Map<String, Object> a = rows.get(left);
        Map<String, Object> b = rows.get(right);
        double weighted = 0;
        int compared = 0;
        Map<String, Double> fieldScores = new LinkedHashMap<>();
        for (MasterMatchConfig.Field field : config.fields()) {
            Object x = a.get(field.name());
            Object y = b.get(field.name());
            if (MasterSimilarity.blank(x) || MasterSimilarity.blank(y)) {
                continue;
            }
            double value = MasterSimilarity.score(field.algorithm(), x, y);
            fieldScores.put(field.name(), value);
            weighted += value * field.weight();
            compared += field.weight();
        }
        if (compared == 0 || compared * 2 < totalWeight) {
            return null;
        }
        return new Pair(left, right, weighted / compared, fieldScores);
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void union(int[] parent, int left, int right) {
        int a = find(parent, left);
        int b = find(parent, right);
        if (a != b) {
            parent[Math.max(a, b)] = Math.min(a, b);
        }
    }

}

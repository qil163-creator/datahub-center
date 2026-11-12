package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 指标依赖解析：把复合指标公式展开为依赖树，并做 DAG 环检测。
 * <p>
 * 复合指标可以引用任意类型的启用指标（包括其他复合指标）；被引用指标必须启用且与根指标同数据域。
 * 派生指标的子节点是其原子指标。解析只读数据库，调用方负责是否持有引用锁。
 */
@Component
public class IndicatorDependencyResolver {

    /** 依赖层级上限（根为第 0 层）。 */
    public static final int MAX_DEPTH = 8;

    /** 依赖树节点（含重复出现的共享节点）上限。 */
    public static final int MAX_NODES = 200;

    @Resource
    private IndicatorModelMapper mapper;

    /**
     * 依赖树节点。
     *
     * @param indicator 指标
     * @param children  直接依赖；原子指标为空
     */
    public record Node(IndicatorModelDO indicator, List<Node> children) {

        /** 深度优先收集叶子（原子、派生）指标，按编号去重并保持首次出现顺序。 */
        public void collectLeaves(Map<Long, IndicatorModelDO> leaves) {
            if (!IndicatorModelDO.COMPOSITE.equals(indicator.getIndicatorType())) {
                leaves.putIfAbsent(indicator.getId(), indicator);
                return;
            }
            children.forEach(child -> child.collectLeaves(leaves));
        }

        /** 收集全部复合节点（不含根以外的叶子），按小写编码索引。 */
        public void collectComposites(Map<String, IndicatorModelDO> composites) {
            if (IndicatorModelDO.COMPOSITE.equals(indicator.getIndicatorType())) {
                composites.putIfAbsent(indicator.getCode().toLowerCase(Locale.ROOT), indicator);
                children.forEach(child -> child.collectComposites(composites));
            }
        }
    }

    /**
     * 解析根指标的依赖树。根可以是尚未保存的草稿；其余节点必须启用。
     *
     * @param root 根指标（需要 code、dataDomainId、indicatorType，复合指标需要 formula）
     * @return 依赖树
     */
    public Node resolve(IndicatorModelDO root) {
        Context context = new Context(root.getDataDomainId());
        if (root.getCode() != null) {
            context.byCode.put(root.getCode().toLowerCase(Locale.ROOT), root);
        }
        return visit(root, new ArrayDeque<>(), context, 0);
    }

    /**
     * 解析公式文本，失败时转为业务错误。
     */
    public static IndicatorFormula parse(String formula) {
        try {
            return IndicatorFormula.parse(formula);
        } catch (IllegalArgumentException invalid) {
            throw exception(INDICATOR_FORMULA_INVALID, invalid.getMessage());
        }
    }

    /**
     * 递归访问节点。
     */
    private Node visit(IndicatorModelDO node, Deque<String> path, Context context, int depth) {
        if (depth > MAX_DEPTH) {
            throw exception(INDICATOR_FORMULA_INVALID, "引用层级不能超过 " + MAX_DEPTH + " 层");
        }
        if (++context.nodes > MAX_NODES) {
            throw exception(INDICATOR_FORMULA_INVALID, "依赖指标不能超过 " + MAX_NODES + " 个");
        }
        String key = Objects.toString(node.getCode(), "#" + node.getId()).toLowerCase(Locale.ROOT);
        path.addLast(key);
        List<Node> children = new ArrayList<>();
        if (IndicatorModelDO.COMPOSITE.equals(node.getIndicatorType())) {
            IndicatorFormula formula = parse(node.getFormula());
            load(formula.references(), context);
            for (String code : formula.references()) {
                String lowered = code.toLowerCase(Locale.ROOT);
                if (path.contains(lowered)) {
                    List<String> cycle = new ArrayList<>(path);
                    cycle.add(lowered);
                    throw exception(INDICATOR_FORMULA_CYCLE, String.join(" → ",
                            cycle.subList(cycle.indexOf(lowered), cycle.size())));
                }
                IndicatorModelDO reference = context.byCode.get(lowered);
                if (reference == null || reference.getId() == null
                        || !IndicatorModelDO.ACTIVE.equals(reference.getStatus())
                        || !Objects.equals(reference.getDataDomainId(), context.dataDomainId)) {
                    throw exception(INDICATOR_FORMULA_REFERENCE_INVALID, code);
                }
                children.add(visit(reference, path, context, depth + 1));
            }
        } else if (IndicatorModelDO.DERIVED.equals(node.getIndicatorType())) {
            IndicatorModelDO base = node.getBaseIndicatorId() == null ? null
                    : context.byId.computeIfAbsent(node.getBaseIndicatorId(), mapper::selectById);
            if (base == null || !IndicatorModelDO.ATOMIC.equals(base.getIndicatorType())
                    || !IndicatorModelDO.ACTIVE.equals(base.getStatus())) {
                throw exception(INDICATOR_BASE_INVALID);
            }
            children.add(new Node(base, List.of()));
        }
        path.removeLast();
        return new Node(node, List.copyOf(children));
    }

    /**
     * 批量加载尚未缓存的编码。
     */
    private void load(List<String> codes, Context context) {
        List<String> missing = codes.stream().map(code -> code.toLowerCase(Locale.ROOT))
                .filter(code -> !context.byCode.containsKey(code)).distinct().toList();
        if (missing.isEmpty()) {
            return;
        }
        for (IndicatorModelDO row : mapper.selectListByCodesIgnoreCase(missing)) {
            context.byCode.putIfAbsent(row.getCode().toLowerCase(Locale.ROOT), row);
            context.byId.putIfAbsent(row.getId(), row);
        }
    }

    /** 单次解析的缓存。 */
    private static final class Context {

        private final Long dataDomainId;

        private final Map<String, IndicatorModelDO> byCode = new HashMap<>();

        private final Map<Long, IndicatorModelDO> byId = new HashMap<>();

        private int nodes;

        private Context(Long dataDomainId) {
            this.dataDomainId = dataDomainId;
        }
    }
}

package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorBoardSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorImpactRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorBoardDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorBoardMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyIndicatorApiRefRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_NOT_EXISTS;

/**
 * 指标口径变更影响分析：修改或停用一个指标前，列出会随之变化的下游。
 * <p>
 * 下游包括：以它为基础的派生指标、公式引用它的复合指标（逐层向上，含草稿），使用这些指标的看板组件，
 * 以及在数据服务中发布了这些指标的接口。数据服务不可用时接口清单标记为不完整，不影响其余结果。
 */
@Slf4j
@Service
public class IndicatorImpactService {

    /** 下游指标上限。 */
    static final int MAX_DEPENDENTS = IndicatorDependencyResolver.MAX_NODES;

    @Resource
    private IndicatorModelMapper indicatorMapper;
    @Resource
    private IndicatorBoardMapper boardMapper;
    @Resource
    private SupplyDataApiApi supplyDataApiApi;

    /**
     * 分析影响。
     */
    public IndicatorImpactRespVO analyze(Long id) {
        IndicatorModelDO root = id == null ? null : indicatorMapper.selectById(id);
        if (root == null) {
            throw exception(INDICATOR_NOT_EXISTS);
        }
        IndicatorImpactRespVO resp = new IndicatorImpactRespVO();
        resp.setIndicator(item(root, 0, null));
        List<IndicatorModelDO> composites = indicatorMapper.selectList(IndicatorModelDO::getIndicatorType,
                IndicatorModelDO.COMPOSITE);
        Map<Long, Set<String>> referencesByComposite = new LinkedHashMap<>();
        for (IndicatorModelDO composite : composites) {
            referencesByComposite.put(composite.getId(), references(composite.getFormula()));
        }
        Map<Long, IndicatorModelDO> compositeById = composites.stream()
                .collect(Collectors.toMap(IndicatorModelDO::getId, value -> value, (left, right) -> left));

        Map<Long, IndicatorImpactRespVO.Item> seen = new LinkedHashMap<>();
        seen.put(root.getId(), resp.getIndicator());
        Deque<IndicatorModelDO> queue = new ArrayDeque<>(List.of(root));
        Map<Long, Integer> depth = new LinkedHashMap<>(Map.of(root.getId(), 0));
        boolean truncated = false;
        while (!queue.isEmpty() && !truncated) {
            IndicatorModelDO current = queue.poll();
            List<IndicatorModelDO> children = new ArrayList<>(
                    indicatorMapper.selectList(IndicatorModelDO::getBaseIndicatorId, current.getId()));
            String code = current.getCode() == null ? null : current.getCode().toLowerCase(Locale.ROOT);
            if (code != null) {
                referencesByComposite.forEach((compositeId, refs) -> {
                    if (refs.contains(code)) {
                        children.add(compositeById.get(compositeId));
                    }
                });
            }
            for (IndicatorModelDO child : children) {
                if (child == null || seen.containsKey(child.getId())) {
                    continue;
                }
                if (seen.size() > MAX_DEPENDENTS) {
                    truncated = true;
                    break;
                }
                int level = depth.get(current.getId()) + 1;
                depth.put(child.getId(), level);
                IndicatorImpactRespVO.Item item = item(child, level, current.getId());
                seen.put(child.getId(), item);
                resp.getDependents().add(item);
                queue.add(child);
            }
        }
        resp.setTruncated(truncated);
        Set<Long> affected = seen.keySet();
        resp.setBoards(boards(affected));
        try {
            List<SupplyIndicatorApiRefRespDTO> apis = supplyDataApiApi
                    .getIndicatorReferences(new ArrayList<>(affected)).getCheckedData();
            for (SupplyIndicatorApiRefRespDTO ref : apis == null ? List.<SupplyIndicatorApiRefRespDTO>of() : apis) {
                IndicatorImpactRespVO.Api api = new IndicatorImpactRespVO.Api();
                api.setId(ref.getId());
                api.setName(ref.getName());
                api.setPath(ref.getPath());
                api.setStatus(ref.getStatus());
                api.setIndicatorIds(ref.getIndicatorIds());
                resp.getApis().add(api);
            }
            resp.setApisAvailable(true);
        } catch (RuntimeException unavailable) {
            log.warn("[analyze][数据服务不可用，接口清单不完整 failureType={}]", unavailable.getClass().getSimpleName());
            resp.setApisAvailable(false);
        }
        return resp;
    }

    // ==================== 内部 ====================

    /**
     * 使用受影响指标的看板组件。
     */
    private List<IndicatorImpactRespVO.Board> boards(Set<Long> affected) {
        List<IndicatorImpactRespVO.Board> result = new ArrayList<>();
        for (IndicatorBoardDO board : boardMapper.selectListByName(null)) {
            IndicatorImpactRespVO.Board hit = null;
            for (IndicatorBoardSaveReqVO.Widget widget : IndicatorBoardService.widgets(board)) {
                List<Long> ids = widget.getQuery() == null ? null : widget.getQuery().getIndicatorIds();
                if (ids != null && ids.stream().anyMatch(affected::contains)) {
                    if (hit == null) {
                        hit = new IndicatorImpactRespVO.Board();
                        hit.setId(board.getId());
                        hit.setName(board.getName());
                        hit.setOwnerUserId(board.getOwnerUserId());
                        result.add(hit);
                    }
                    hit.getWidgets().add(widget.getTitle());
                }
            }
        }
        return result;
    }

    /**
     * 公式引用的指标编码（小写）；无法解析时视为无引用。
     */
    static Set<String> references(String formula) {
        if (formula == null || formula.isBlank()) {
            return Set.of();
        }
        try {
            return IndicatorFormula.parse(formula).references().stream()
                    .map(code -> code.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        } catch (IllegalArgumentException invalid) {
            return Set.of();
        }
    }

    /**
     * 指标摘要。
     */
    private static IndicatorImpactRespVO.Item item(IndicatorModelDO indicator, int depth, Long upstreamId) {
        IndicatorImpactRespVO.Item item = new IndicatorImpactRespVO.Item();
        item.setId(indicator.getId());
        item.setCode(indicator.getCode());
        item.setName(indicator.getName());
        item.setIndicatorType(indicator.getIndicatorType());
        item.setStatus(indicator.getStatus());
        item.setDepth(depth);
        item.setUpstreamId(upstreamId);
        return item;
    }
}

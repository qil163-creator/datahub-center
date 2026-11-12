package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorQueryRespVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_QUERY_MASK_FAILED;

/**
 * 指标查询结果的动态脱敏接入：调用方给出每个输出列的物理来源，由 governance 按分类、白名单与场景处理。
 * <p>
 * 失败关闭：脱敏服务不可用、返回行数或列不一致时整次查询失败，绝不返回原值。
 * 调用方必须在目标库连接关闭后、任何事务之外调用。
 */
@Slf4j
@Component
public class IndicatorQueryMaskSupport {

    /** governance 中登记的指标查询脱敏场景。 */
    public static final String SCENE = "INDICATOR_QUERY";

    private static final int MAX_RESOURCE_NAME = 250;

    @Resource
    private SafetySecurityApi safetySecurityApi;

    /**
     * 脱敏结果。
     *
     * @param rows          处理后的行（列 key 与顺序不变）
     * @param maskedColumns 被脱敏的列
     */
    public record Result(List<Map<String, String>> rows, List<IndicatorQueryRespVO.MaskedColumn> maskedColumns) {
    }

    /**
     * 调用 governance 动态脱敏。
     *
     * @param resourceName 来源表名（审计用）
     * @param columns      全部输出列及其物理来源
     * @param rows         结果行
     * @return 脱敏后的行与被脱敏列
     */
    public Result apply(String resourceName, List<SafetyDynamicMaskReqDTO.Column> columns,
            List<Map<String, String>> rows) {
        if (rows.isEmpty() || columns.isEmpty()) {
            return new Result(rows, List.of());
        }
        SafetyDynamicMaskReqDTO request = new SafetyDynamicMaskReqDTO();
        request.setUserId(SecurityFrameworkUtils.getLoginUserId());
        request.setScene(SCENE);
        request.setResourceName(StrUtil.maxLength(StrUtil.nullToEmpty(resourceName), MAX_RESOURCE_NAME));
        request.setColumns(columns);
        List<Map<String, Object>> payload = new ArrayList<>(rows.size());
        rows.forEach(row -> payload.add(new LinkedHashMap<>(row)));
        request.setRows(payload);
        SafetyDynamicMaskRespDTO masked;
        try {
            masked = safetySecurityApi.maskRows(request).getCheckedData();
        } catch (RuntimeException unavailable) {
            // 只记录异常类型；不能因脱敏服务不可用而返回原值。
            log.warn("[apply][指标查询动态脱敏不可用，失败关闭 failureType={}]", unavailable.getClass().getSimpleName());
            throw exception(INDICATOR_QUERY_MASK_FAILED, "动态脱敏服务暂不可用，为保护敏感数据暂不返回结果");
        }
        if (masked == null || masked.getRows() == null || masked.getRows().size() != rows.size()) {
            throw exception(INDICATOR_QUERY_MASK_FAILED, "动态脱敏结果不完整，为保护敏感数据暂不返回结果");
        }
        List<Map<String, String>> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> source = masked.getRows().get(index);
            Map<String, String> row = new LinkedHashMap<>();
            for (String key : rows.get(index).keySet()) {
                if (source == null || !source.containsKey(key)) {
                    throw exception(INDICATOR_QUERY_MASK_FAILED, "动态脱敏结果不完整，为保护敏感数据暂不返回结果");
                }
                Object value = source.get(key);
                row.put(key, value == null ? null : value.toString());
            }
            result.add(row);
        }
        List<IndicatorQueryRespVO.MaskedColumn> maskedColumns = new ArrayList<>();
        for (SafetyDynamicMaskRespDTO.MaskedColumn column : Objects.requireNonNullElse(masked.getMaskedColumns(),
                List.<SafetyDynamicMaskRespDTO.MaskedColumn>of())) {
            IndicatorQueryRespVO.MaskedColumn item = new IndicatorQueryRespVO.MaskedColumn();
            item.setName(column.getOutputName());
            item.setReason(column.getReason());
            item.setCategoryNames(column.getCategoryNames() == null ? List.of() : column.getCategoryNames());
            maskedColumns.add(item);
        }
        return new Result(result, maskedColumns);
    }

    /**
     * 可追溯的输出列。
     */
    public static SafetyDynamicMaskReqDTO.Column column(String outputName,
            Collection<SafetyDynamicMaskReqDTO.Source> sources) {
        SafetyDynamicMaskReqDTO.Column column = new SafetyDynamicMaskReqDTO.Column();
        column.setOutputName(outputName);
        column.setSources(new ArrayList<>(sources));
        column.setTraceable(true);
        return column;
    }

    /**
     * 无法追溯到物理列的输出列：以来源表为候选，由 governance 判定是否失败关闭。
     */
    public static SafetyDynamicMaskReqDTO.Column untraceable(String outputName,
            Collection<SafetyDynamicMaskReqDTO.Source> candidateTables) {
        SafetyDynamicMaskReqDTO.Column column = new SafetyDynamicMaskReqDTO.Column();
        column.setOutputName(outputName);
        column.setSources(new ArrayList<>());
        column.setTraceable(false);
        column.setCandidateTables(new ArrayList<>(candidateTables));
        return column;
    }
}

package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTable;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsBatchReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableColumnsRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableKeyDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 以 governance 已采集的元数据作为血缘分析的表结构来源。
 * <p>
 * 一次分析只批量请求一次（每批最多 100 张表），不连接源库、不读取凭据；元数据未采集的表返回空，
 * 分析结果相应降级为部分解析。
 */
@Slf4j
@Component
public class LineageCatalogProvider {

    @Resource
    private MetadataApi metadataApi;

    /** 预取结果：可用的表结构与是否读取失败。 */
    public record Prefetched(LineageCatalog catalog, boolean failed) {
    }

    /**
     * 批量预取同一数据源下若干表的字段名。
     */
    public Prefetched prefetch(Long datasourceId, Collection<LineageTable> tables) {
        Map<String, List<String>> columns = new HashMap<>();
        if (datasourceId == null || tables.isEmpty()) {
            return new Prefetched(catalog(columns), false);
        }
        List<LineageTable> unique = new ArrayList<>(new LinkedHashSet<>(tables));
        boolean failed = false;
        for (int from = 0; from < unique.size(); from += MetadataTableColumnsBatchReqDTO.MAX_TABLES) {
            List<LineageTable> chunk = unique.subList(from,
                    Math.min(unique.size(), from + MetadataTableColumnsBatchReqDTO.MAX_TABLES));
            MetadataTableColumnsBatchReqDTO req = new MetadataTableColumnsBatchReqDTO();
            for (LineageTable table : chunk) {
                MetadataTableKeyDTO key = new MetadataTableKeyDTO();
                key.setDatasourceId(datasourceId);
                key.setSchemaName(table.schema());
                key.setTableName(table.name());
                req.getTables().add(key);
            }
            try {
                List<MetadataTableColumnsRespDTO> resp = metadataApi.getColumnNamesBatch(req).getCheckedData();
                for (int i = 0; i < Math.min(resp.size(), chunk.size()); i++) {
                    MetadataTableColumnsRespDTO item = resp.get(i);
                    if (Boolean.TRUE.equals(item.getFound()) && item.getColumnNames() != null
                            && !item.getColumnNames().isEmpty()) {
                        columns.put(key(chunk.get(i)), List.copyOf(item.getColumnNames()));
                    }
                }
            } catch (RuntimeException unavailable) {
                // 只记录异常类型；元数据服务的错误信息可能包含表名之外的内部细节。
                log.warn("[prefetch][读取已采集元数据失败 datasourceId={} failureType={}]", datasourceId,
                        unavailable.getClass().getSimpleName());
                failed = true;
            }
        }
        return new Prefetched(catalog(columns), failed);
    }

    /**
     * 基于预取结果的只读表结构。
     */
    private static LineageCatalog catalog(Map<String, List<String>> columns) {
        return table -> Optional.ofNullable(columns.get(key(table)));
    }

    /**
     * 大小写不敏感的表键。
     */
    private static String key(LineageTable table) {
        return (table.schema() == null ? "" : table.schema().toLowerCase(Locale.ROOT)) + "\u0000"
                + table.name().toLowerCase(Locale.ROOT);
    }
}

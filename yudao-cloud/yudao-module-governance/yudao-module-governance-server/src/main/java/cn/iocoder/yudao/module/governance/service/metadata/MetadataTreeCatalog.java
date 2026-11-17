package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.MetadataTreeSourceRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
class MetadataTreeCatalog {

    private final MetaDbMapper dbMapper;
    private final DatabaseApi databaseApi;

    record View(List<MetaDbDO> databases, Map<Long, String> names, Set<Long> deletedSources) {}

    View load(Collection<Long> taskSources) {
        var dbs = dbMapper.selectListForTree();
        var sourceIds = new LinkedHashSet<>(taskSources);
        dbs.forEach(db -> sourceIds.add(db.getDatasourceId()));
        sourceIds.remove(null);

        Map<Long, String> names = new HashMap<>();
        Set<Long> deleted = new HashSet<>();
        for (var batch : Lists.partition(new ArrayList<>(sourceIds), 500)) {
            try {
                for (MetadataTreeSourceRespDTO source : databaseApi.getMetadataTreeSources(batch).getCheckedData()) {
                    names.put(source.getId(), source.getName());
                    if (Boolean.TRUE.equals(source.getDeleted())) {
                        deleted.add(source.getId());
                    }
                }
            } catch (RuntimeException ex) {
                // 无法确认状态的连接（包括离线导入的连接）保留原有节点；刷新时重试。
                log.warn("元数据树读取连接状态失败，本次保留该批次节点", ex);
            }
        }
        var visible = dbs.stream().filter(db -> !deleted.contains(db.getDatasourceId())
                || db.getTableCount() > 0).toList();
        return new View(visible, names, deleted);
    }
}

package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectContext;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaDiffEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetaCollectExistenceTest {

    @Mock
    private MetaTableMapper tableMapper;
    @Mock
    private TransactionTemplate transactionTemplate;
    @InjectMocks
    private MetaDiffEngine diffEngine;

    @Test
    void t13EnumeratedButUnreadTableIsNotDeleted() {
        MetaTableDO live = MetaTableDO.builder().id(1L).tableName("t_person")
                .objectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE).build();
        when(tableMapper.selectListByDbId(9L)).thenReturn(List.of(live));
        MetaCollectContext ctx = org.mockito.Mockito.mock(MetaCollectContext.class);
        Set<String> enumerated = new LinkedHashSet<>();
        enumerated.add("t_person");
        diffEngine.markMissingTables(ctx, MetaDbDO.builder().id(9L).dbName("ods").build(), enumerated);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

    @Test
    void t14BlacklistedTableIsTreatedAsExcludeNotDelete() {
        MetaTableDO skipped = MetaTableDO.builder().id(2L).tableName("secret_tab")
                .objectStatus(MetaDbDO.OBJECT_STATUS_ACTIVE).build();
        when(tableMapper.selectListByDbId(9L)).thenReturn(List.of(skipped));
        MetaCollectContext ctx = org.mockito.Mockito.mock(MetaCollectContext.class);
        Set<String> presentOrExcluded = new LinkedHashSet<>();
        presentOrExcluded.add("secret_tab");
        diffEngine.markMissingTables(ctx, MetaDbDO.builder().id(9L).dbName("ods").build(), presentOrExcluded);
        verify(transactionTemplate, never()).executeWithoutResult(any());
    }

}

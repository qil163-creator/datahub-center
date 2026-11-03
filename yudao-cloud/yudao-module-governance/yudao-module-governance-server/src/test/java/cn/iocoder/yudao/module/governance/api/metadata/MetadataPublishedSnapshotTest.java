package cn.iocoder.yudao.module.governance.api.metadata;

import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableSnapshotDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataPublishedSnapshotTest {

    @Mock
    private MetaTableMapper tableMapper;
    @Mock
    private MetaTableSnapshotMapper snapshotMapper;
    @InjectMocks
    private MetadataApiImpl api;

    @Test
    void t15PublishedColumnsComeFromFrozenSnapshotNotLiveHead() {
        when(tableMapper.selectById(5L)).thenReturn(MetaTableDO.builder()
                .id(5L).publishedSnapshotId(88L).publishStatus(MetaTableDO.PUBLISH_STATUS_YES).build());
        when(snapshotMapper.selectById(88L)).thenReturn(MetaTableSnapshotDO.builder()
                .id(88L).tableId(5L)
                .snapshot("{\"columns\":[{\"columnName\":\"id_card\",\"ordinal\":1}]}")
                .build());
        List<MetadataColumnRespDTO> columns = api.getPublishedColumnList(5L).getData();
        assertThat(columns).extracting(MetadataColumnRespDTO::getColumnName).containsExactly("id_card");
    }

    @Test
    void t15UnpublishedTableDoesNotInventSnapshot() {
        when(tableMapper.selectById(5L)).thenReturn(MetaTableDO.builder()
                .id(5L).publishStatus(MetaTableDO.PUBLISH_STATUS_NO).build());
        assertThatThrownBy(() -> api.getPublishedColumnList(5L))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
                .hasMessage("表元数据尚未发布");
    }

    @Test
    void frozenIdentityIsLosslessAndNeverResolvedByCurrentColumnName() {
        when(snapshotMapper.selectById(88L)).thenReturn(MetaTableSnapshotDO.builder().id(88L).tableId(5L)
                .snapshot("{\"columns\":[{\"id\":\"9007199254740993\",\"resourceUrn\":\"urn:frozen:old\","
                        + "\"columnName\":\"customer_name\"}]}").build());
        var frozen = api.getColumnListBySnapshot(88L).getData().getFirst();
        assertThat(frozen.getId()).isEqualTo(9007199254740993L);
        assertThat(frozen.getResourceUrn()).isEqualTo("urn:frozen:old");
        assertThat(frozen.getRevision()).isNull();
    }

    @Test
    void legacySnapshotDoesNotInventCurrentFieldIdentityOrRevision() {
        when(snapshotMapper.selectById(88L)).thenReturn(MetaTableSnapshotDO.builder().id(88L).tableId(5L)
                .snapshot("{\"columns\":[{\"columnName\":\"customer_name\"}]}").build());
        var frozen = api.getColumnListBySnapshot(88L).getData().getFirst();
        assertThat(frozen.getId()).isNull();
        assertThat(frozen.getRevision()).isNull();
    }

    @Test
    void publishedWithoutFinalizedVersionFailsInsteadOfReadingCurrentFields() {
        when(tableMapper.selectById(5L)).thenReturn(MetaTableDO.builder()
                .id(5L).publishStatus(MetaTableDO.PUBLISH_STATUS_YES).build());
        assertThatThrownBy(() -> api.getPublishedColumnList(5L)).hasMessage("表元数据尚未定版");
    }

    @Test
    void publishedTableHeaderComesFromFrozenBusinessAndStructure() {
        when(tableMapper.selectById(5L)).thenReturn(MetaTableDO.builder().id(5L)
                .publishStatus(MetaTableDO.PUBLISH_STATUS_YES).publishedSnapshotId(88L)
                .businessName("当前未确认名称").columnCount(9).publishedVersion("v1.0.0")
                .finalizedSnapshot("{\"table\":{\"id\":5,\"businessName\":\"定版名称\",\"columnCount\":2},\"columns\":[]}")
                .build());
        var result = api.getTable(5L).getData();
        assertThat(result.getBusinessName()).isEqualTo("定版名称");
        assertThat(result.getColumnCount()).isEqualTo(2);
        assertThat(result.getPublishedSnapshotId()).isEqualTo(88L);
    }

    @Test
    void foreignTableSnapshotCannotBeUsedAsPublishedPointer() {
        when(tableMapper.selectById(5L)).thenReturn(MetaTableDO.builder().id(5L)
                .publishedSnapshotId(88L).publishStatus(MetaTableDO.PUBLISH_STATUS_YES).build());
        when(snapshotMapper.selectById(88L)).thenReturn(MetaTableSnapshotDO.builder().id(88L)
                .tableId(6L).snapshot("{\"columns\":[]}").build());
        assertThatThrownBy(() -> api.getPublishedColumnList(5L)).hasMessage("结构快照不属于指定表");
    }

}

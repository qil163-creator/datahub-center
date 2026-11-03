package cn.iocoder.yudao.module.governance.api.metadata;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaFileDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaFileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataCurrentFileProjectionTest {
    @Test void returnsOnlyCurrentRealFileIdentityAndNeverContactsOrDisplaySnapshot() {
        var mapper = mock(MetaFileMapper.class); var api = new MetadataApiImpl(); ReflectionTestUtils.setField(api, "fileMapper", mapper);
        var file = MetaFileDO.builder().id(9007199254740993L).objectStatus(1).name("rainfall.csv").path("/exports/rainfall.csv").fileType("CSV")
                .techLeaderPhone("contact_marker").displaySnapshot("private_display_marker").build();
        when(mapper.selectById(file.getId())).thenReturn(file);
        var result = api.getCurrentFile(file.getId()).getCheckedData();
        assertThat(result.getId()).isEqualTo(9007199254740993L);
        assertThat(result.getName()).isEqualTo("rainfall.csv");
        assertThat(JsonUtils.toJsonString(result)).doesNotContain("contact_marker", "private_display_marker", "techLeaderPhone");
        file.setObjectStatus(3);
        assertThat(api.getCurrentFile(file.getId()).getCheckedData()).isNull();
        file.setObjectStatus(null);
        assertThat(api.getCurrentFile(file.getId()).getCheckedData()).isNull();
    }
}

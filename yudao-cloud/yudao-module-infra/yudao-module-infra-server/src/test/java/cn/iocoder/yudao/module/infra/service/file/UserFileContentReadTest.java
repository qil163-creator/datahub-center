package cn.iocoder.yudao.module.infra.service.file;

import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import cn.iocoder.yudao.module.infra.dal.mysql.file.FileMapper;
import cn.iocoder.yudao.module.infra.framework.file.core.client.FileClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserFileContentReadTest {
    private final FileServiceImpl service = new FileServiceImpl();
    private final FileMapper mapper = mock(FileMapper.class);
    private final FileConfigService configs = mock(FileConfigService.class);
    private final FileClient client = mock(FileClient.class);
    private FileDO file;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "fileMapper", mapper);
        ReflectionTestUtils.setField(service, "fileConfigService", configs);
        file = new FileDO().setId(77L).setConfigId(9L).setPath("20261101/upload.jsonl").setName("upload.jsonl").setSize(2L);
        file.setCreator("42"); when(mapper.selectById(77L)).thenReturn(file); when(configs.getFileClient(9L)).thenReturn(client);
    }

    @Test void readsOnlyWithBoundedStorageApiAndOmitsStorageCoordinates() throws Exception {
        when(client.getContent(file.getPath(), 20L * 1024 * 1024)).thenReturn(new byte[]{1, 2});
        var result = service.readUserUpload(77L, 42L);
        assertEquals("upload.jsonl", result.getName()); assertArrayEquals(new byte[]{1, 2}, result.getContent());
        verify(client, never()).getContent(anyString());
    }

    @Test void anotherUsersFileAndManagedBusinessUploadsAreNeverRead() {
        assertThrows(Exception.class, () -> service.readUserUpload(77L, 43L));
        file.setPath("managed/dataforge/task/fixture.jsonl");
        assertThrows(Exception.class, () -> service.readUserUpload(77L, 42L));
        verifyNoInteractions(client);
    }

    @Test void limitsAreCheckedBeforeReadingAndActualLengthMustMatch() throws Exception {
        file.setSize(20L * 1024 * 1024 + 1);
        assertThrows(Exception.class, () -> service.readUserUpload(77L, 42L)); verifyNoInteractions(client);
        file.setSize(2L); when(client.getContent(file.getPath(), 20L * 1024 * 1024)).thenReturn(new byte[]{1});
        assertThrows(Exception.class, () -> service.readUserUpload(77L, 42L));
    }

    @Test void invalidIdentitiesDoNotReadStorage() {
        assertThrows(Exception.class, () -> service.readUserUpload(null, 42L));
        assertThrows(Exception.class, () -> service.readUserUpload(77L, null));
        assertThrows(Exception.class, () -> service.readUserUpload(-1L, 42L)); verifyNoInteractions(client);
    }

    @Test void ordinaryJsonLinesUploadUsesExistingTextContentChecks() throws Exception {
        when(configs.getMasterFileClient()).thenReturn(client);
        when(client.getId()).thenReturn(9L);
        when(client.upload(any(), anyString(), anyString())).thenReturn("https://localhost/upload");
        byte[] content = "{\"id\":1}\n{\"id\":2}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("text/plain", service.createFileWithInfo(content, "sample.jsonl", null, "application/octet-stream").getType());
        assertEquals("text/plain", service.createFileWithInfo(content, "sample.ndjson", null, "application/octet-stream").getType());
        assertThrows(Exception.class, () -> service.createFileWithInfo(new byte[]{0, 1, 2, 3}, "sample.jsonl", null, "application/json"));
    }
}

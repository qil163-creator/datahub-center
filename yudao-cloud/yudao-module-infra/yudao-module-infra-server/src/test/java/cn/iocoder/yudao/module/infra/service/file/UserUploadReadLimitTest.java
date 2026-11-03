package cn.iocoder.yudao.module.infra.service.file;

import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import cn.iocoder.yudao.module.infra.dal.mysql.file.FileMapper;
import cn.iocoder.yudao.module.infra.framework.file.core.client.FileClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserUploadReadLimitTest {
    private final FileServiceImpl service = new FileServiceImpl();
    private final FileMapper mapper = mock(FileMapper.class);
    private final FileConfigService configs = mock(FileConfigService.class);
    private final FileClient client = mock(FileClient.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "fileMapper", mapper);
        ReflectionTestUtils.setField(service, "fileConfigService", configs);
    }
    @Test void ownerUploadUsesFiftyMiBBoundAndVerifiesTheWholeReceipt() throws Exception {
        byte[] content = new byte[(int) ManagedFileApi.MAX_USER_UPLOAD_BYTES];
        var file = file(content.length);
        when(mapper.selectById(1L)).thenReturn(file);
        when(configs.getFileClient(7L)).thenReturn(client);
        when(client.getContent("user/sample.csv", ManagedFileApi.MAX_USER_UPLOAD_BYTES)).thenReturn(content);
        var upload = service.readUserUpload(1L, 42L);
        assertSame(content, upload.getContent()); assertEquals((long) content.length, upload.getSize());
        verify(client).getContent("user/sample.csv", 50L * 1024 * 1024);
        file.setSize((long) content.length - 1);
        assertThrows(RuntimeException.class, () -> service.readUserUpload(1L, 42L));
    }
    @Test void oversizedWrongOwnerAndManagedObjectsNeverReachObjectStorage() {
        var file = file(ManagedFileApi.MAX_USER_UPLOAD_BYTES + 1); when(mapper.selectById(1L)).thenReturn(file);
        var exception = assertThrows(RuntimeException.class, () -> service.readUserUpload(1L, 42L));
        assertTrue(exception.getMessage().contains("50MiB"));
        file.setSize(1L); assertThrows(RuntimeException.class, () -> service.readUserUpload(1L, 43L));
        file.setPath("managed/owned/file.csv"); assertThrows(RuntimeException.class, () -> service.readUserUpload(1L, 42L));
        verifyNoInteractions(configs, client);
    }
    @Test void generatedManagedFilesRetainTheTwentyMiBLimit() {
        var request = new ManagedFileCreateReqDTO().setOperationKey("managed-limit-boundary")
                .setName("generated.csv").setContent(new byte[20 * 1024 * 1024 + 1]);
        var exception = assertThrows(RuntimeException.class, () -> service.createManaged("dataforge", request));
        assertTrue(exception.getMessage().contains("20MB")); verifyNoInteractions(configs, client, mapper);
    }
    private static FileDO file(long size) {
        var file = new FileDO().setId(1L).setConfigId(7L).setPath("user/sample.csv").setName("sample.csv").setSize(size);
        file.setCreator("42"); return file;
    }
}

package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationFileSchemaModels;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.UserFileContentRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationFileSchemaServiceTest {
    private final IntegrationFileSchemaService service = new IntegrationFileSchemaService();
    private final ManagedFileApi files = mock(ManagedFileApi.class);
    @BeforeEach void setup() { ReflectionTestUtils.setField(service, "files", files); }

    @Test void csvUsesOwnerScopedUploadAndRejectsMismatchedExtensions() {
        var csv = request(); csv.setFormat("CSV");
        var content = "id,name\n0012,站点".getBytes(StandardCharsets.UTF_8);
        try (var context = mockStatic(SecurityFrameworkUtils.class)) {
            context.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(42L);
            when(files.readUserUpload(9007199254740993L, 42L)).thenReturn(CommonResult.success(upload("sample.csv", content)));
            var result = service.parse(csv);
            assertEquals("STRING", result.fields().getFirst().dataType()); assertTrue(result.partial());
            verify(files).readUserUpload(9007199254740993L, 42L);
            when(files.readUserUpload(anyLong(), anyLong())).thenReturn(CommonResult.success(upload("sample.jsonl", content)));
            assertThrows(RuntimeException.class, () -> service.parse(csv));
        }
    }

    @Test void readsCurrentActorUploadAndReturnsOnlyPartialFieldMetadata() {
        var content = "{\"id\":1,\"name\":\"河道A\"}\n{\"id\":2,\"name\":\"q'Data\"}".getBytes(StandardCharsets.UTF_8);
        when(files.readUserUpload(9007199254740993L, 42L)).thenReturn(CommonResult.success(upload("sample.jsonl", content)));
        try (var context = mockStatic(SecurityFrameworkUtils.class)) {
            context.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(42L);
            var result = service.parse(request());
            assertTrue(result.partial()); assertFalse(result.warnings().isEmpty());
            assertEquals("LONG", result.fields().getFirst().dataType()); assertNull(result.fields().getFirst().length());
            verify(files).readUserUpload(9007199254740993L, 42L);
        }
    }

    @Test void unauthenticatedRequestNeverReachesStorage() {
        try (var context = mockStatic(SecurityFrameworkUtils.class)) {
            context.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
            assertThrows(RuntimeException.class, () -> service.parse(request())); verifyNoInteractions(files);
        }
    }

    @Test void inaccessibleUploadProducesSanitizedErrorWithoutStorageLocation() {
        when(files.readUserUpload(anyLong(), anyLong())).thenThrow(new IllegalStateException("https://secret-storage.example/internal?token=hidden"));
        try (var context = mockStatic(SecurityFrameworkUtils.class)) {
            context.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(42L);
            var exception = assertThrows(RuntimeException.class, () -> service.parse(request()));
            assertFalse(exception.getMessage().contains("secret-storage")); assertFalse(exception.getMessage().contains("token"));
        }
    }

    @Test void rejectsWrongFileFormatMalformedBytesAndInconsistentReceipt() {
        try (var context = mockStatic(SecurityFrameworkUtils.class)) {
            context.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(42L);
            when(files.readUserUpload(anyLong(), anyLong())).thenReturn(CommonResult.success(upload("wrong.csv", new byte[]{1})));
            assertThrows(RuntimeException.class, () -> service.parse(request()));
            when(files.readUserUpload(anyLong(), anyLong())).thenReturn(CommonResult.success(upload("sample.jsonl", new byte[]{1})));
            assertThrows(RuntimeException.class, () -> service.parse(request()));
            when(files.readUserUpload(anyLong(), anyLong())).thenReturn(CommonResult.success(upload("sample.jsonl", new byte[]{1}).setSize(20L * 1024 * 1024 + 1)));
            assertThrows(RuntimeException.class, () -> service.parse(request()));
            var invalid = request(); invalid.setStartColumn(0); clearInvocations(files);
            assertThrows(RuntimeException.class, () -> service.parse(invalid)); verifyNoInteractions(files);
        }
    }

    @Test void acceptsOwnerUploadedCsvAtFiftyMiBAndRejectsOversizedReceipts() {
        byte[] content = new byte[50 * 1024 * 1024]; java.util.Arrays.fill(content, (byte) 'x');
        content[0] = 'v'; content[1] = '\n';
        for (int i = 6001; i < content.length; i += 6001) content[i] = '\n';
        var csv = request(); csv.setFormat("CSV");
        try (var context = mockStatic(SecurityFrameworkUtils.class)) {
            context.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(42L);
            when(files.readUserUpload(anyLong(), anyLong())).thenReturn(CommonResult.success(upload("large.csv", content)));
            assertEquals("v", service.parse(csv).fields().getFirst().name());
            when(files.readUserUpload(anyLong(), anyLong())).thenReturn(CommonResult.success(upload("large.csv", content).setSize(50L * 1024 * 1024 + 1)));
            assertThrows(RuntimeException.class, () -> service.parse(csv));
        }
    }

    private static IntegrationFileSchemaModels.Request request() {
        var request = new IntegrationFileSchemaModels.Request(); request.setFileId(9007199254740993L); request.setFormat("JSONL"); return request;
    }
    private static UserFileContentRespDTO upload(String name, byte[] content) {
        return new UserFileContentRespDTO().setName(name).setSize((long) content.length).setContent(content);
    }
}

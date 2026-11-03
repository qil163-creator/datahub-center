package cn.iocoder.yudao.module.dataforge.service.fileresource;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.file.core.remote.*;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证原数据连接的两个测试入口都分发到文件协议，而非 JDBC。协议本身由组件的真实网络测试覆盖。 */
class FileResourceDiagnosisTest {
    private final DatabaseServiceImpl service=new DatabaseServiceImpl();
    private final DatabaseTypeMapper types=mock(DatabaseTypeMapper.class);
    private final DbSwitchRuntime databaseRuntime=mock(DbSwitchRuntime.class);
    private final RemoteFileClient client=mock(RemoteFileClient.class);
    private final RemoteFileClientFactory clientFactory=mock(RemoteFileClientFactory.class);
    private final FileResourceConnectionResolver resolver=new FileResourceConnectionResolver();
    private final FileResourceDiagnosis diagnosis=new FileResourceDiagnosis();
    private final SecurityFrameworkService security=mock(SecurityFrameworkService.class);
    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(service,"databaseTypeMapper",types);
        ReflectionTestUtils.setField(service,"dbSwitchRuntime",databaseRuntime);
        var factories=mock(ObjectProvider.class);
        when(factories.getIfAvailable()).thenReturn(clientFactory);
        ReflectionTestUtils.setField(resolver,"clientFactories",factories);
        ReflectionTestUtils.setField(diagnosis,"connections",resolver);
        ReflectionTestUtils.setField(service,"fileResourceDiagnosis",diagnosis);
        when(security.hasPermission("dataforge:database:query")).thenReturn(true);
        when(security.hasPermission("dataforge:database:update")).thenReturn(true);
        ReflectionTestUtils.setField(service,"securityFrameworkService",security);
        when(client.stat("/")).thenReturn(new RemoteFileEntry("/","/",true,0,0));
        when(client.list("/")).thenReturn(List.of());
    }
    @ParameterizedTest @ValueSource(strings={"FTP","HDFS"})
    void bothTestEntrypointsUseReadOnlyFileProbe(String protocol) throws Exception {
        when(types.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode(protocol).build());
        var form=form();
        when(clientFactory.open(any())).thenReturn(client);
            service.testDatabase(form);
            var req=new DatabaseDiagnosisReqVO();req.setConnection(form);req.setIncludeWriteChecks(false);
            var result=service.diagnoseDatabase(req);
            assertTrue(result.isConnectionSuccessful());assertEquals(1,result.getNormalCount());
            assertEquals(4,result.getSkippedCount());assertEquals("SKIPPED",result.getGroups().getLast().getItems().getFirst().getStatus());
            assertNotNull(result.getGroups().getFirst().getItems().getFirst().getImpact());
            assertNotNull(result.getGroups().getLast().getItems().getFirst().getImpact());
            assertWireItems(result);
            verify(client,times(2)).list("/");verify(client,times(2)).close();
            verify(client, never()).upload(any(), any(), anyLong(), anyBoolean());
            verifyNoInteractions(databaseRuntime);
    }
    @Test void connectionFailureDoesNotReturnEndpointOrCredentialError() throws Exception {
        when(types.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode("FTP").build());
        when(clientFactory.open(any())).thenThrow(new IOException("synthetic-private-secret"));
            assertThrows(ServiceException.class,()->service.testDatabase(form()));
            var req=new DatabaseDiagnosisReqVO();req.setConnection(form());
            var result=service.diagnoseDatabase(req);
            assertFalse(result.isConnectionSuccessful());assertEquals("FAIL",result.getOverallStatus());
            assertNotNull(result.getGroups().getFirst().getItems().getFirst().getImpact());
            assertNotNull(result.getGroups().getLast().getItems().getFirst().getImpact());
            assertWireItems(result);
            assertFalse(result.getGroups().getFirst().getItems().getFirst().getMessage().contains("secret"));
            verifyNoInteractions(databaseRuntime);
    }
    private static void assertWireItems(DatabaseDiagnosisRespVO result) {
        var json = JsonUtils.getObjectMapper().readTree(JsonUtils.toJsonString(result));
        assertTrue(json.path("checkedAt").isNumber());
        for (var group : json.path("groups")) for (var item : group.path("items")) {
            for (var field : List.of("checkCode", "checkName", "checkContent", "status", "message", "impact")) {
                assertTrue(item.path(field).isString(), field);
            }
            assertTrue(item.path("durationMs").isNumber());
        }
    }

    @ParameterizedTest @ValueSource(strings={"FTP","HDFS"})
    void requestedWriteChecksExerciseAndRemoveOnlyTemporaryFiles(String protocol) throws Exception {
        var fake = new FakeClient();
        fake.files.put("/existing", "keep".getBytes());
        when(types.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode(protocol).build());
        when(clientFactory.open(any())).thenReturn(fake);
        var req = new DatabaseDiagnosisReqVO(); req.setConnection(form()); req.setIncludeWriteChecks(true);
        var result = service.diagnoseDatabase(req);
        assertTrue(result.isConnectionSuccessful()); assertEquals("PASS", result.getOverallStatus());
        assertEquals(5, result.getNormalCount()); assertEquals(0, result.getSkippedCount());
        assertEquals(List.of("FILE_CREATE", "FILE_OVERWRITE", "FILE_RENAME", "FILE_DELETE"),
                result.getGroups().getLast().getItems().stream().map(DatabaseDiagnosisRespVO.Item::getCheckCode).toList());
        assertEquals(List.of("create", "overwrite", "rename", "delete"), fake.operations);
        assertEquals(List.of("/existing"), List.copyOf(fake.files.keySet()));
        assertWireItems(result);
    }

    @ParameterizedTest @ValueSource(strings={"create", "overwrite", "rename", "delete"})
    void writeFailurePreservesOnlineAndReportsPossibleResidue(String failedOperation) throws Exception {
        var fake = new FakeClient(); fake.failAfter = failedOperation;
        when(types.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode("FTP").build());
        when(clientFactory.open(any())).thenReturn(fake);
        var req = new DatabaseDiagnosisReqVO(); req.setConnection(form()); req.setIncludeWriteChecks(true);
        var result = service.diagnoseDatabase(req);
        assertTrue(result.isConnectionSuccessful()); assertEquals("FAIL", result.getOverallStatus());
        var writes = result.getGroups().getLast().getItems();
        int failedIndex = List.of("create", "overwrite", "rename", "delete").indexOf(failedOperation);
        assertEquals("FAIL", writes.get(failedIndex).getStatus());
        assertTrue(writes.get(failedIndex).getMessage().contains("可能存在探测残留"));
        assertEquals(3 - failedIndex, result.getSkippedCount()); assertTrue(fake.files.isEmpty());
        assertWireItems(result);
    }

    @Test void writeChecksRequireUpdatePermissionBeforeOpeningRemoteConnection() {
        when(security.hasPermission("dataforge:database:update")).thenReturn(false);
        var req = new DatabaseDiagnosisReqVO(); req.setConnection(form()); req.setIncludeWriteChecks(true);
        assertThrows(ServiceException.class, () -> service.diagnoseDatabase(req));
        verifyNoInteractions(clientFactory);
    }

    @Test void readFailureSkipsAllWriteChecks() throws Exception {
        when(types.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode("FTP").build());
        when(clientFactory.open(any())).thenReturn(client);
        when(client.list("/")).thenThrow(new IOException("synthetic-secret"));
        var req = new DatabaseDiagnosisReqVO(); req.setConnection(form()); req.setIncludeWriteChecks(true);
        var result = service.diagnoseDatabase(req);
        assertFalse(result.isConnectionSuccessful()); assertEquals(4, result.getSkippedCount());
        verify(client, never()).upload(any(), any(), anyLong(), anyBoolean());
    }

    @Test void cleanupFailureDoesNotClaimRemoval() throws Exception {
        var fake = new FakeClient(); fake.failAfter = "create"; fake.denyDelete = true;
        when(types.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode("FTP").build());
        when(clientFactory.open(any())).thenReturn(fake);
        var req = new DatabaseDiagnosisReqVO(); req.setConnection(form()); req.setIncludeWriteChecks(true);
        var result = service.diagnoseDatabase(req);
        assertEquals(1, fake.files.size());
        assertTrue(result.getGroups().getLast().getItems().getFirst().getMessage().contains("可能存在探测残留"));
    }

    @Test void failedDirectoryReadAfterDeleteCannotProveDeletion() throws Exception {
        var fake = new FakeClient(); fake.failListAfterDelete = true;
        when(types.selectById(80L)).thenReturn(DatabaseTypeDO.builder().id(80L).typeCode("FTP").build());
        when(clientFactory.open(any())).thenReturn(fake);
        var req = new DatabaseDiagnosisReqVO(); req.setConnection(form()); req.setIncludeWriteChecks(true);
        var result = service.diagnoseDatabase(req);
        assertEquals("FAIL", result.getGroups().getLast().getItems().getLast().getStatus());
        assertTrue(result.isConnectionSuccessful());
    }

    private static class FakeClient implements RemoteFileClient {
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> operations = new java.util.ArrayList<>();
        String failAfter;
        boolean denyDelete;
        boolean failListAfterDelete;
        boolean deleted;
        @Override public List<RemoteFileEntry> list(String path) throws IOException {
            if (failListAfterDelete && deleted) throw new IOException("synthetic-secret");
            return files.keySet().stream().map(p -> new RemoteFileEntry(p.substring(1), p, false, 0, 0)).toList();
        }
        @Override public RemoteFileEntry stat(String path) throws IOException {
            if ("/".equals(path)) return new RemoteFileEntry("/", "/", true, 0, 0);
            if (!files.containsKey(path)) throw new FileNotFoundException();
            return new RemoteFileEntry(path.substring(1), path, false, files.get(path).length, 0);
        }
        @Override public void upload(String path, InputStream input, long size, boolean overwrite) throws IOException {
            if (!overwrite && files.containsKey(path)) throw new IOException();
            operations.add(overwrite ? "overwrite" : "create"); files.put(path, input.readAllBytes());
            if ((overwrite ? "overwrite" : "create").equals(failAfter)) throw new IOException("synthetic-secret");
        }
        @Override public void download(String path, OutputStream output, long maxBytes) throws IOException {
            if (!files.containsKey(path)) throw new FileNotFoundException();
            output.write(files.get(path));
        }
        @Override public void rename(String path, String target) throws IOException {
            operations.add("rename"); files.put(target, files.remove(path));
            if ("rename".equals(failAfter)) throw new IOException("synthetic-secret");
        }
        @Override public void delete(String path) throws IOException {
            operations.add("delete");
            if (denyDelete) throw new IOException("synthetic-secret");
            files.remove(path); deleted = true;
            if ("delete".equals(failAfter)) throw new IOException("synthetic-secret");
        }
        @Override public void mkdir(String path) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
    private static DatabaseSaveReqVO form(){
        var req=new DatabaseSaveReqVO();req.setDatabaseType(80);req.setDatabaseIp("127.0.0.1");
        req.setDatabasePort("9870");req.setUserName("synthetic");
        req.setExtendConfig("{\"basePath\":\"/\",\"webHdfsUrl\":\"http://127.0.0.1:9870\",\"allowedDataNodeAuthorities\":[\"127.0.0.1:9864\"]}");
        return req;
    }
}


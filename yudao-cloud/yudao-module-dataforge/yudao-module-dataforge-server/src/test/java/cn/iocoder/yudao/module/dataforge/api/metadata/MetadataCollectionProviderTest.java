package cn.iocoder.yudao.module.dataforge.api.metadata;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.file.config.YudaoFileAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.dataforge.framework.security.MetadataCollectionRpcFilter;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.systemsource.SystemSourceMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.fileresource.FileResourceConnectionResolver;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 合法权限调用直接消费 starter 的真实 HTTP 协议，非真实 Hadoop 集群。 */
class MetadataCollectionProviderTest {
    HttpServer server;
    List<String> operations=new CopyOnWriteArrayList<>();
    ApplicationContextRunner runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoFileAutoConfiguration.class))
            .withBean(DatabaseMapper.class,()->mock(DatabaseMapper.class)).withBean(DatabaseTypeMapper.class,()->mock(DatabaseTypeMapper.class))
            .withBean(SystemSourceMapper.class,()->mock(SystemSourceMapper.class)).withBean(AccessDatabaseResolver.class,()->mock(AccessDatabaseResolver.class))
            .withBean(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.class,()->mock(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.class))
            .withBean(HttpServletRequest.class, MetadataCollectionProviderTest::verifiedRequest)
            .withBean(PermissionApi.class,()->mock(PermissionApi.class)).withBean(FileResourceConnectionResolver.class).withBean(MetadataCollectionApiImpl.class);
    private static HttpServletRequest verifiedRequest() {
        try {
            var certificate = mock(X509Certificate.class);
            when(certificate.getEncoded()).thenReturn(new byte[]{1, 2, 3});
            var request = new MockHttpServletRequest();
            request.setSecure(true);
            request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
            new MetadataCollectionRpcFilter(() -> Map.of("governance", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))))
                    .doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
            MetadataCollectionRpcFilter.requireVerified(request);
            return request;
        } catch (Exception failure) {
            throw new AssertionError("测试认证请求创建失败", failure);
        }
    }
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/webhdfs/v1",exchange->{
            String query=exchange.getRequestURI().getQuery(); String op=query.contains("op=LISTSTATUS")?"LISTSTATUS":"GETFILESTATUS";
            operations.add(exchange.getRequestMethod()+" "+op);
            String body=op.equals("LISTSTATUS") ? "{\"FileStatuses\":{\"FileStatus\":[{\"pathSuffix\":\"test.csv\",\"type\":\"FILE\",\"length\":12,\"modificationTime\":1760000000000}]}}"
                    : "{\"FileStatus\":{\"type\":\"DIRECTORY\",\"length\":0,\"modificationTime\":1760000000000}}";
            if (op.equals("LISTSTATUS") && exchange.getRequestURI().getPath().equals("/webhdfs/v1/"))
                body="{\"FileStatuses\":{\"FileStatus\":[{\"pathSuffix\":\"scope\",\"type\":\"DIRECTORY\",\"length\":0}]}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { if(server!=null)server.stop(0); }
    @Test void authorizedManifestUsesOnlyMetadataReadsAndNeverReturnsCredentials() {
        runner.run(context->{
            assertNull(context.getStartupFailure());
            var db=DatabaseDO.builder().id(7L).name("协议测试连接").databaseType(2).status(0)
                    .databaseIp("127.0.0.1").databasePort("8020").userName("synthetic-reader")
                    .extendConfig("{\"basePath\":\"/scope\",\"webHdfsUrl\":\"http://127.0.0.1:"+server.getAddress().getPort()+"\"}").build();
            when(context.getBean(DatabaseMapper.class).selectById(7L)).thenReturn(db);
            when(context.getBean(DatabaseTypeMapper.class).selectById(2L)).thenReturn(DatabaseTypeDO.builder().id(2L).typeCode("HDFS").status((short)0).build());
            var permission=context.getBean(PermissionApi.class);
            when(permission.hasAnyPermissions(9L,"governance:meta-collect-task:run")).thenReturn(CommonResult.success(true));
            when(permission.hasAnyPermissions(9L,"governance:meta-collect-task:query")).thenReturn(CommonResult.success(true));
            var result=context.getBean(MetadataCollectionApiImpl.class).fileManifest(7L,"/",9L).getCheckedData();
            assertEquals(2,result.size());assertEquals("/test.csv",result.get(1).getPath());assertEquals(12,result.get(1).getSize());
            String json=JsonUtils.toJsonString(result);assertFalse(json.contains("password"));assertFalse(json.contains("synthetic-reader"));
            assertFalse(operations.isEmpty());assertTrue(operations.stream().allMatch(op->op.equals("GET LISTSTATUS")||op.equals("GET GETFILESTATUS")));
        });
    }
    @Test void deniedOrMissingActorNeverOpensRemoteConnection() {
        runner.run(context->{
            var api=context.getBean(MetadataCollectionApiImpl.class);
            assertThrows(ServiceException.class,()->api.fileManifest(7L,"/",null));
            when(context.getBean(PermissionApi.class).hasAnyPermissions(9L,"governance:meta-collect-task:run")).thenReturn(CommonResult.success(false));
            assertThrows(ServiceException.class,()->api.fileManifest(7L,"/",9L));
            verifyNoInteractions(context.getBean(DatabaseMapper.class));assertTrue(operations.isEmpty());
        });
    }
    @Test void disabledStarterCannotBeBypassedByCollectionProvider() {
        runner.withPropertyValues("yudao.file.enabled=false").run(context->{
            var db=DatabaseDO.builder().id(7L).databaseType(2).status(0).build();
            when(context.getBean(DatabaseMapper.class).selectById(7L)).thenReturn(db);
            when(context.getBean(DatabaseTypeMapper.class).selectById(2L)).thenReturn(DatabaseTypeDO.builder().typeCode("HDFS").status((short)0).build());
            when(context.getBean(PermissionApi.class).hasAnyPermissions(eq(9L),any(String.class))).thenReturn(CommonResult.success(true));
            assertThrows(ServiceException.class,()->context.getBean(MetadataCollectionApiImpl.class).fileManifest(7L,"/",9L));
            assertTrue(operations.isEmpty());
        });
    }
    @Test void sourceSystemUsesCommonEnabledStatusAndOptionsAcceptFileQueryPermission() {
        runner.run(context->{
            var permission=context.getBean(PermissionApi.class);
            when(permission.hasAnyPermissions(9L,"governance:meta-collect-task:query")).thenReturn(CommonResult.success(true));
            when(permission.hasAnyPermissions(9L,"governance:meta-collect-task:query","governance:meta-file:query"))
                    .thenReturn(CommonResult.success(true));
            var source=cn.iocoder.yudao.module.dataforge.dal.dataobject.systemsource.SystemSourceDO.builder()
                    .id(3L).name("测试来源系统").status((short)0).build();
            when(context.getBean(SystemSourceMapper.class).selectById(3L)).thenReturn(source);
            var api=context.getBean(MetadataCollectionApiImpl.class);
            assertEquals(3L,api.system(3L,9L).getCheckedData().getId());
            source.setStatus((short)1); assertThrows(ServiceException.class,()->api.system(3L,9L));
            assertTrue(api.options(9L).getCheckedData().getConnections().isEmpty());
            verify(permission).hasAnyPermissions(9L,"governance:meta-collect-task:query","governance:meta-file:query");
            assertTrue(operations.isEmpty());
        });
    }
    @Test void collectionFeignHasBoundedTimeoutAndNoAutomaticRetryOrRedirect() {
        var configuration=new MetadataCollectionApi.ClientConfig();
        assertEquals(5000,configuration.metadataCollectionOptions().connectTimeoutMillis());
        assertEquals(90000,configuration.metadataCollectionOptions().readTimeoutMillis());
        assertFalse(configuration.metadataCollectionOptions().isFollowRedirects());
        assertSame(feign.Retryer.NEVER_RETRY,configuration.metadataCollectionRetryer());
    }
}

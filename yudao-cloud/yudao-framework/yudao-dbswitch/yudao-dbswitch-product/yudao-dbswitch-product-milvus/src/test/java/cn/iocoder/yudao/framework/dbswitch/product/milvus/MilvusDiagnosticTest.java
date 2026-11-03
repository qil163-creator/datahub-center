package cn.iocoder.yudao.framework.dbswitch.product.milvus;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MilvusDiagnosticTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={401,403})
    void rejectedHttpResponseNeverLeaksBodyOrFabricatesIndependentSuccess(int status) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v2/vectordb/collections/list",exchange->{
            byte[] body="private-token-server-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        }); server.start();
        try {
            var request=new ConnectionProbeRequest(ProductTypeEnum.MILVUS,null,null,"http://127.0.0.1:"+server.getAddress().getPort(),
                    "fixture-user","fixture-token","default",null,Map.of(),Duration.ofSeconds(2));
            var result=new MilvusConnectionProbe().diagnose(request,()->null);
            assertEquals("FAIL",result.status()); assertFalse(result.connectionSuccessful());
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("AUTH_TARGET")
                    &&(status==401?"AUTHENTICATION":"AUTHORIZATION").equals(c.reasonCode())));
            assertTrue(result.checks().stream().noneMatch(c->c.status().equals("PASS")));
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("COLLECTIONS")&&"PREREQUISITE_FAILED".equals(c.reasonCode())));
            assertFalse(result.toString().contains("private-token-server-error"));
            assertFalse(result.toString().contains("fixture-token"));
        } finally {server.stop(0);}
    }

    @Test void successfulListIsDatabaseAndCollectionMetadataEvidenceNotVectorReadOrWrite() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v2/vectordb/collections/list",exchange->{
            byte[] body="{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        }); server.start();
        try {
            var request=new ConnectionProbeRequest(ProductTypeEnum.MILVUS,null,null,"http://127.0.0.1:"+server.getAddress().getPort(),
                    "fixture-user","fixture-token","default",null,Map.of(),Duration.ofSeconds(2));
            var result=new MilvusConnectionProbe().diagnose(request,()->null);
            assertEquals("PASS",result.status());
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("TARGET")&&c.status().equals("PASS")));
            assertTrue(result.checks().stream().anyMatch(c->c.group().equals("METADATA")&&c.status().equals("PASS")));
            assertTrue(result.checks().stream().anyMatch(c->c.group().equals("READ")&&c.status().equals("SKIPPED")));
            assertTrue(result.checks().stream().anyMatch(c->c.group().equals("WRITE")&&c.reasonCode().equals("NOT_AUTHORIZED")));
        } finally {server.stop(0);}
    }
}

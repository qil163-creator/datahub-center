package cn.iocoder.yudao.framework.dbswitch.product.influxdb2;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class Influxdb2DiagnosticTest {
    @Test void missingTokenIsConfigurationNotProtocolFailure() {
        var request=new ConnectionProbeRequest(ProductTypeEnum.INFLUXDB2,null,null,"http://127.0.0.1:1",
                null,null,null,null,Map.of("org","org","bucket","bucket"),Duration.ofSeconds(2));
        var result=new Influxdb2ConnectionProbe().diagnose(request,()->null);
        assertEquals("CONFIGURATION",result.checks().stream().filter(c->c.status().equals("FAIL")).findFirst().orElseThrow().reasonCode());
        assertFalse(result.connectionSuccessful());
    }

    @Test void authorizedEmptyOrganizationListPreservesAuthButNotTargetEvidence() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v2/orgs",exchange->{
            byte[] body="{\"orgs\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        }); server.start();
        try {
            var request=new ConnectionProbeRequest(ProductTypeEnum.INFLUXDB2,null,null,"http://127.0.0.1:"+server.getAddress().getPort(),
                    null,"fixture-token",null,null,Map.of("org","missing","bucket","bucket"),Duration.ofSeconds(2));
            var result=new Influxdb2ConnectionProbe().diagnose(request,()->null);
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("AUTH")&&c.status().equals("PASS")));
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("ORGANIZATION")&&"TARGET_NOT_FOUND".equals(c.reasonCode())));
            assertFalse(result.connectionSuccessful());
        } finally {server.stop(0);}
    }

    @Test void organizationEvidenceSurvivesBucketAuthorizationFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            boolean org=exchange.getRequestURI().getPath().endsWith("/orgs");
            byte[] body=(org?"{\"orgs\":[{\"name\":\"org\",\"id\":\"id\"}]}":"private-token-error").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(org?200:403,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        }); server.start();
        try {
            var request=new ConnectionProbeRequest(ProductTypeEnum.INFLUXDB2,null,null,"http://127.0.0.1:"+server.getAddress().getPort(),
                    null,"fixture-token",null,null,Map.of("org","org","bucket","bucket"),Duration.ofSeconds(2));
            var result=new Influxdb2ConnectionProbe().diagnose(request,()->null);
            assertEquals("FAIL",result.status());
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("AUTH")&&c.status().equals("PASS")));
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("ORGANIZATION")&&c.status().equals("PASS")));
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("BUCKET")&&c.status().equals("FAIL")));
            assertTrue(result.checks().stream().anyMatch(c->c.group().equals("WRITE")&&c.status().equals("SKIPPED")));
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("BUCKET_METADATA")&&"PREREQUISITE_FAILED".equals(c.reasonCode())));
            assertFalse(result.toString().contains("private-token-error"));
        } finally {server.stop(0);}
    }
}

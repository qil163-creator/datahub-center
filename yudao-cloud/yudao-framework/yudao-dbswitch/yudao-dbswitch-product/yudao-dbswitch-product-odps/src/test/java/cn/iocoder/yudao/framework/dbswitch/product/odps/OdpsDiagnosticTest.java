package cn.iocoder.yudao.framework.dbswitch.product.odps;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OdpsDiagnosticTest {
    @Test void rejectedProjectReturnsSafeStageEvidenceAndNoSqlOrWriteIsSubmitted() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            assertEquals("GET",exchange.getRequestMethod());
            byte[] body="private-odps-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        }); server.start();
        try {
            var request=new ConnectionProbeRequest(ProductTypeEnum.ODPS,null,null,"http://127.0.0.1:"+server.getAddress().getPort(),
                    "fixture-user","fixture-token",null,null,Map.of("project","fixture_project"),Duration.ofSeconds(2));
            var result=new OdpsConnectionProbe().diagnose(request,()->null);
            assertEquals("FAIL",result.status());
            assertTrue(result.checks().stream().anyMatch(c->c.status().equals("FAIL")&&"AUTHORIZATION".equals(c.reasonCode())));
            assertTrue(result.checks().stream().anyMatch(c->c.group().equals("READ")&&c.status().equals("SKIPPED")));
            assertTrue(result.checks().stream().anyMatch(c->c.group().equals("WRITE")&&"NOT_AUTHORIZED".equals(c.reasonCode())));
            assertFalse(result.toString().contains("private-odps-error"));
        } finally {server.stop(0);}
    }
}

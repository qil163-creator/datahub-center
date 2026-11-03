package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EtlScriptWorkerHttpTest {
    private final EtlScriptRunner runner=new EtlScriptRunner(EtlScriptWorkerPolicy.defaults(true));
    private static String source(String address){return "public class Login implements tech.qiantong.qdata.common.core.base.EtlBase {public String execute(com.alibaba.fastjson2.JSONObject rows,Object log)throws Exception{String result=cn.hutool.http.HttpRequest.post(\""+address+"\").header(\"X-Mode\",\"test\").body(\"{\\\"nonce\\\":\\\"public\\\"}\").execute().body();writeLog(log,result);return com.alibaba.fastjson2.JSONObject.parseObject(result).getString(\"token\");}}";}
    @Test void originalHutoolTemplateUsesBoundedIpcAndCannotInheritHostSecretsOrCertificates(){
        var calls=new AtomicInteger();
        try(var session=runner.open(EtlScriptRunner.Language.QDATA_TOKEN,source("https://login.internal/auth?page=1"),()->{},()->fail("cleanup"),request->{
            calls.incrementAndGet();assertFalse(request.headers().keySet().stream().anyMatch(name->name.equalsIgnoreCase("Content-Length")));assertEquals("https://login.internal/auth",request.endpoint());assertEquals("POST",request.method());assertEquals(Map.of("page","1"),request.query());assertEquals("test",request.headers().get("X-Mode"));assertEquals("{\"nonce\":\"public\"}",new String(request.body(),java.nio.charset.StandardCharsets.UTF_8));
            return new EtlScriptWorkerHttp.Response(200,"application/json;charset=UTF-8","{\"token\":\"ipc-only-sentinel\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        })) {assertEquals("ipc-only-sentinel",session.execute(List.of()).getFirst().get("__token"));}
        assertEquals(1,calls.get());assertEquals(0,runner.activeWorkers());
    }
    @Test void deniedResourceNeverFallsBackToWorkerNetworkOrReturnsResponseData(){
        var calls=new AtomicInteger();
        try(var session=runner.open(EtlScriptRunner.Language.QDATA_TOKEN,source("http://ungranted.internal/auth"),()->{},()->fail("cleanup"),request->{calls.incrementAndGet();throw new IllegalArgumentException("private-policy-sentinel");})){
            var failure=assertThrows(IllegalStateException.class,()->session.execute(List.of()));assertFalse(failure.getMessage().contains("private-policy-sentinel"));assertFalse(failure.getMessage().contains("ungranted.internal"));
        }
        assertEquals(1,calls.get());assertEquals(0,runner.activeWorkers());
    }
    @Test void workerRequestsRejectUnsupportedMethodsCredentialsInAddressesAndOversizedBodies(){
        assertThrows(IllegalArgumentException.class,()->new EtlScriptWorkerHttp.Request("https://user:password@api.internal/data","GET",Map.of(),Map.of(),new byte[0],1024,1000));
        assertThrows(IllegalArgumentException.class,()->new EtlScriptWorkerHttp.Request("https://api.internal/data","DELETE",Map.of(),Map.of(),new byte[0],1024,1000));
        assertThrows(IllegalArgumentException.class,()->new EtlScriptWorkerHttp.Request("https://api.internal/data","POST",Map.of(),Map.of(),new byte[1048577],1024,1000));
    }
}

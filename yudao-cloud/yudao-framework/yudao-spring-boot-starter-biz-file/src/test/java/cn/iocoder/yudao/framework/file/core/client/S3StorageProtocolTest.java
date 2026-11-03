package cn.iocoder.yudao.framework.file.core.client;
import cn.iocoder.yudao.framework.file.core.client.s3.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;
class S3StorageProtocolTest {
    @Test void probeOnlySendsSignedHeadAndRejectsDeniedBucket() throws Exception {
        List<String> methods=Collections.synchronizedList(new ArrayList<>());
        List<String> authorization=Collections.synchronizedList(new ArrayList<>());
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                methods.add(exchange.getRequestMethod());
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/allowed")?200:403,-1);
            } finally {exchange.close();}
        });server.start();
        S3FileClientConfig config=new S3FileClientConfig().setEndpoint("http://127.0.0.1:"+server.getAddress().getPort())
                .setBucket("allowed").setAccessKey("probe-only").setAccessSecret(UUID.randomUUID().toString())
                .setEnablePathStyleAccess(true).setEnablePublicAccess(false);
        try {
            try(S3FileClient client=new S3FileClient(1L,config)) {
                client.init();client.testConnection(java.time.Duration.ofSeconds(2));
            }
            config.setBucket("denied");
            try(S3FileClient client=new S3FileClient(2L,config)) {
                client.init();assertThatThrownBy(()->client.testConnection(java.time.Duration.ofSeconds(2)))
                        .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
            }
            assertThat(methods).isNotEmpty().allMatch("HEAD"::equals);
            assertThat(authorization).allMatch(value->value!=null&&value.startsWith("AWS4-HMAC-SHA256 "));
        } finally {server.stop(0);}
    }
    @Test void sdkSendsRealHttpRequestsAndProducesScopedSignatures() throws Exception {
        Map<String,byte[]> objects=new ConcurrentHashMap<>();List<String> methods=Collections.synchronizedList(new ArrayList<>());
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            methods.add(exchange.getRequestMethod());String key=exchange.getRequestURI().getPath();
            try {
                switch(exchange.getRequestMethod()) {
                    case "PUT" -> { objects.put(key,exchange.getRequestBody().readAllBytes());exchange.getResponseHeaders().set("ETag","\"fixture\"");exchange.sendResponseHeaders(200,-1); }
                    case "GET" -> { byte[] bytes=objects.get(key);if(bytes==null){exchange.sendResponseHeaders(404,-1);}else{exchange.getResponseHeaders().set("Content-Type","text/plain");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);} }
                    case "DELETE" -> {objects.remove(key);exchange.sendResponseHeaders(204,-1);}
                    default -> exchange.sendResponseHeaders(405,-1);
                }
            } finally {exchange.close();}
        });server.start();
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        S3FileClientConfig config=new S3FileClientConfig().setEndpoint(endpoint).setDomain(endpoint+"/isolated").setBucket("isolated").setAccessKey("isolated-test").setAccessSecret(UUID.randomUUID().toString()).setEnablePathStyleAccess(true).setEnablePublicAccess(false);
        try(S3FileClient client=new S3FileClient(1L,config)) {
            client.init();String url=client.upload(new byte[]{1,2,3},"nested/file.txt","text/plain");
            assertThat(url).startsWith(endpoint+"/isolated/nested/file.txt?").contains("X-Amz-Signature=");
            assertThat(client.getContent("nested/file.txt")).containsExactly((byte)1,(byte)2,(byte)3);
            assertThat(client.presignPutUrl("new.txt")).startsWith(endpoint+"/isolated/new.txt?");
            assertThatThrownBy(()->client.presignPutUrl("../outside")).isInstanceOf(IllegalArgumentException.class);
            client.delete("nested/file.txt");assertThat(objects).isEmpty();assertThat(methods).containsExactly("PUT","GET","DELETE");
            assertThat(config.toString()).doesNotContain(config.getAccessSecret());
        } finally {server.stop(0);}
    }
}

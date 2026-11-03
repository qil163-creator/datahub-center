package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class EtlWebServiceRowsTest {
    public static String wsdl(String base) {
        StringBuilder bindings = new StringBuilder(), ports = new StringBuilder();
        for (String method : List.of("soap11", "soap12", "httpGet", "httpPost")) {
            boolean soap = method.startsWith("soap"); String prefix = method.equals("soap11") ? "s11" : method.equals("soap12") ? "s12" : "h";
            bindings.append("<w:binding name=\"").append(method).append("Binding\" type=\"t:PortType\"><").append(prefix).append(":binding ")
                    .append(soap ? "style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"" : "verb=\"" + (method.equals("httpGet") ? "GET" : "POST") + "\"")
                    .append("/><w:operation name=\"List\"><").append(prefix).append(":operation ").append(soap ? "soapAction=\"urn:List\"" : "location=\"rows\"")
                    .append("/><w:input>").append(soap ? "<" + prefix + ":body use=\"literal\"/>" : "<h:urlEncoded/>").append("</w:input></w:operation></w:binding>");
            ports.append("<w:port name=\"").append(method).append("Port\" binding=\"t:").append(method).append("Binding\"><").append(prefix).append(":address location=\"")
                    .append(base).append('/').append(method).append(soap ? "" : "/").append("\"/></w:port>");
        }
        return "<w:definitions xmlns:w=\"http://schemas.xmlsoap.org/wsdl/\" xmlns:x=\"http://www.w3.org/2001/XMLSchema\" xmlns:t=\"urn:fixture\" xmlns:s11=\"http://schemas.xmlsoap.org/wsdl/soap/\" xmlns:s12=\"http://schemas.xmlsoap.org/wsdl/soap12/\" xmlns:h=\"http://schemas.xmlsoap.org/wsdl/http/\" targetNamespace=\"urn:fixture\">"
                + "<w:types><x:schema targetNamespace=\"urn:fixture\" elementFormDefault=\"qualified\"><x:element name=\"List\"><x:complexType><x:sequence><x:element name=\"label\" type=\"x:string\"/></x:sequence></x:complexType></x:element></x:schema></w:types>"
                + "<w:message name=\"Input\"><w:part name=\"body\" element=\"t:List\"/></w:message><w:portType name=\"PortType\"><w:operation name=\"List\"><w:input message=\"t:Input\"/></w:operation></w:portType>"
                + bindings + "<w:service name=\"Service\">" + ports + "</w:service></w:definitions>";
    }

    @Test void metadataReadsOnlyAuthorizedDocumentsAndNeverInvokesBusinessOperations() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var business = new java.util.concurrent.atomic.AtomicInteger();
        var document = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/wsdl", exchange -> {
            document.incrementAndGet(); byte[] bytes = wsdl(base).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/soap11", exchange -> { business.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            var allowed = Set.of(base + "/wsdl", base + "/soap11", base + "/soap12", base + "/httpGet/rows", base + "/httpPost/rows");
            assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.inspect(base + "/wsdl", Set.of(), allowed));
            assertEquals(0, document.get());
            var metadata = EtlWebServiceRows.inspect(base + "/wsdl", Set.of(base + "/wsdl"), allowed);
            assertEquals(1, metadata.services().size()); assertEquals(4, metadata.services().getFirst().ports().size());
            var operation = metadata.services().getFirst().ports().getFirst().operations().getFirst();
            assertEquals("List", operation.name()); assertEquals("urn:fixture", operation.targetNamespace());
            assertEquals("label", operation.parameters().getFirst().name()); assertEquals("string", operation.parameters().getFirst().dataType());
            assertEquals(true, operation.parameters().getFirst().required()); assertFalse(operation.partial()); assertEquals(0, business.get());
            var onlySoap = EtlWebServiceRows.inspect(base + "/wsdl", Set.of(base + "/wsdl"), Set.of(base + "/soap11"));
            assertEquals(1, onlySoap.services().getFirst().ports().size()); assertFalse(onlySoap.warnings().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.inspect(base + "/wsdl", Set.of(base + "/wsdl"), Set.of(base + "/wsdl")));
            assertEquals(0, business.get());
        } finally { server.stop(0); }
    }

    @Test void metadataRejectsUnapprovedImportsRedirectsEntitiesAndOversizedDocuments() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var content = new java.util.concurrent.atomic.AtomicReference<>("<w:definitions xmlns:w=\"http://schemas.xmlsoap.org/wsdl/\"><w:import location=\"/private\"/></w:definitions>");
        var privateReads = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/wsdl", exchange -> {
            byte[] bytes = content.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/private", exchange -> { privateReads.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", base + "/private"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.start();
        try {
            assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.inspect(base + "/wsdl", Set.of(base + "/wsdl"), Set.of()));
            assertEquals(0, privateReads.get());
            assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.inspect(base + "/redirect", Set.of(base + "/redirect"), Set.of()));
            assertEquals(0, privateReads.get());
            content.set("<!DOCTYPE foo [<!ENTITY leak SYSTEM '" + base + "/private'>]><foo>&leak;</foo>");
            assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.inspect(base + "/wsdl", Set.of(base + "/wsdl"), Set.of()));
            assertEquals(0, privateReads.get());
            content.set("x".repeat(1_048_577));
            assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.inspect(base + "/wsdl", Set.of(base + "/wsdl"), Set.of()));
        } finally { server.stop(0); }
    }

    @Test void invokesActualWsdlBindingsForBothSoapVersionsAndHttpMethods() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var requests = new ArrayList<String>(); var fault = new AtomicBoolean();
        server.createContext("/wsdl", exchange -> {
            byte[] bytes = wsdl(base).getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        for (String method : List.of("soap11", "soap12", "httpGet", "httpPost")) server.createContext("/" + method, exchange -> {
            if (!"Bearer test-only".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401, -1); exchange.close(); return; }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(method + " " + exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
            if (method.startsWith("soap")) {
                assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").startsWith(method.equals("soap11") ? "text/xml" : "application/soap+xml"));
                assertEquals("O'Reilly<&水", EtlWebServiceRows.xml(body.getBytes(StandardCharsets.UTF_8)).getElementsByTagNameNS("urn:fixture", "label").item(0).getTextContent());
                if (method.equals("soap11")) assertEquals("\"urn:List\"", exchange.getRequestHeaders().getFirst("SOAPAction"));
            }
            String content = "<ListResponse><rows><row><id>9007199254740993</id><label>河道😀</label></row><row><id>9007199254740994</id><label>O'Reilly水情</label></row></rows></ListResponse>";
            if (method.startsWith("soap")) {
                String envelope = method.equals("soap11") ? "http://schemas.xmlsoap.org/soap/envelope/" : "http://www.w3.org/2003/05/soap-envelope";
                content = "<s:Envelope xmlns:s=\"" + envelope + "\"><s:Body>" + (fault.get() ? "<s:Fault><secret>test-only</secret></s:Fault>" : content) + "</s:Body></s:Envelope>";
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            Set<String> allowed = Set.of(base + "/wsdl", base + "/soap11", base + "/soap12", base + "/httpGet/rows", base + "/httpPost/rows");
            for (String method : List.of("soap11", "soap12", "httpGet", "httpPost")) {
                var plan = JSONUtil.createObj().set("apiUrl", base + "/wsdl").set("operationName", "List").set("apiMethod", method)
                        .set("serviceName", "Service").set("portName", method + "Port").set("requestParams", Map.of("label", "O'Reilly<&水"))
                        .set("headerSecretRefs", Map.of("Authorization", "apiAccess")).set("returnDataLine", "ListResponse/rows/row");
                var rows = new ArrayList<String>();
                EtlWebServiceRows.read(plan, allowed, reference -> { assertEquals("apiAccess", reference); return "Bearer test-only"; }, rows::add);
                assertEquals(2, rows.size()); assertEquals("9007199254740993", JSONUtil.parseObj(rows.get(0)).getStr("id")); assertEquals("河道😀", JSONUtil.parseObj(rows.get(0)).getStr("label"));
                if (method.equals("soap11")) {
                    var failure = assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.read(plan, Set.of(base + "/wsdl"), reference -> "Bearer test-only", rows::add));
                    assertFalse(failure.toString().contains("Bearer"));
                    fault.set(true);
                    failure = assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.read(plan, allowed, reference -> "Bearer test-only", rows::add));
                    assertFalse(failure.toString().contains("test-only")); fault.set(false);
                }
            }
            assertTrue(requests.stream().anyMatch(value -> value.startsWith("httpGet GET /httpGet/rows?label=O%27Reilly%3C%26%E6%B0%B4")));
            assertTrue(requests.stream().anyMatch(value -> value.startsWith("httpPost POST /httpPost/rows") && value.contains("label=O%27Reilly%3C%26%E6%B0%B4")));
        } finally { server.stop(0); }
    }

    @Test void rejectsExternalEntitiesBeforeAnyNetworkOrFileResolution() {
        assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.xml("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void selectsJsonReturnPathAndEnforcesDeclaredObjectOrArrayWithoutDuplicateKeys() {
        assertEquals("9007199254740993", JSONUtil.parseObj(EtlWebServiceRows.jsonValues("{\"data\":{\"rows\":[{\"id\":9007199254740993}]}}", "JSON数组", "data.rows").getFirst()).getStr("id"));
        assertEquals(1, EtlWebServiceRows.jsonValues("{\"data\":{\"id\":1}}", "JSON对象", "$.data").size());
        assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.jsonValues("{\"id\":1}", "JSON数组", ""));
        assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.jsonValues("[]", "JSON对象", ""));
        assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.jsonValues("{\"rows\":[{\"id\":1,\"id\":2}]}", "JSON数组", "rows"));
        assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.jsonValues("{\"rows\":null}", "JSON数组", "rows"));
        assertThrows(IllegalArgumentException.class, () -> EtlWebServiceRows.jsonValues("[1]", "JSON数组", ""));
    }
}

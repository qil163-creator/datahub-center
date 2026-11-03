package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** WSDL1.1原生工作节点行源，按实际服务、绑定、端口与操作构造SOAP或HTTP请求。 */
public final class EtlWebServiceRows {
    private static final String WSDL = "http://schemas.xmlsoap.org/wsdl/";
    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String SOAP11 = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String SOAP12 = "http://schemas.xmlsoap.org/wsdl/soap12/";
    private static final String HTTP = "http://schemas.xmlsoap.org/wsdl/http/";
    private EtlWebServiceRows() { }

    public record Metadata(List<Service> services, List<String> warnings) { }
    public record Service(String name, List<Port> ports) { }
    public record Port(String name, String endpoint, String apiMethod, List<Operation> operations) { }
    public record Operation(String name, String targetNamespace, List<Parameter> parameters, boolean partial) { }
    public record Parameter(String name, String dataType, Boolean required) { }

    /** 只读取获精确授权的 WSDL/XSD；不调用服务操作，也不解析或发送运行凭据。 */
    public static Metadata inspect(String address, Set<String> metadataAddresses, Set<String> executionAddresses) {
        var documents = new LinkedHashMap<String, Document>();
        load(address, Set.copyOf(metadataAddresses), 1_048_576, documents, System.nanoTime() + 10_000_000_000L, 8);
        var services = new ArrayList<Service>(); var warnings = new ArrayList<String>(); int operations = 0;
        for (Document document : documents.values()) for (Element service : children(document.getDocumentElement(), WSDL, "service")) {
            var ports = new ArrayList<Port>();
            for (Element port : children(service, WSDL, "port")) {
                Element binding = definition(documents, "binding", qualified(port, port.getAttribute("binding")));
                for (String protocol : List.of(SOAP11, SOAP12, HTTP)) {
                    var addresses = children(port, protocol, "address");
                    if (addresses.isEmpty()) continue;
                    Element target = only(addresses); String endpoint = target.getAttribute("location");
                    Element extension = only(children(binding, protocol, "binding"));
                    String method = protocol.equals(SOAP11) ? "soap11" : protocol.equals(SOAP12) ? "soap12"
                            : switch (extension.getAttribute("verb")) { case "GET" -> "httpGet"; case "POST" -> "httpPost"; default -> throw invalid("WSDL HTTP绑定方式未支持"); };
                    var entries = new ArrayList<Operation>();
                    Element portType = definition(documents, "portType", qualified(binding, binding.getAttribute("type")));
                    for (Element operation : children(binding, WSDL, "operation")) {
                        if (++operations > 256) throw invalid("WSDL操作超过分析预算");
                        String name = metadataName(operation.getAttribute("name"));
                        Element input = only(children(operation, WSDL, "input"));
                        if (!protocol.equals(HTTP) && !"literal".equals(only(children(input, protocol, "body")).getAttribute("use"))) {
                            warnings.add("操作 " + name + " 使用尚未支持的encoded绑定，未生成参数"); continue;
                        }
                        String actualEndpoint = endpoint;
                        if (protocol.equals(HTTP)) {
                            String location = only(children(operation, HTTP, "operation")).getAttribute("location");
                            if (location.contains("(") || location.contains(")")) {
                                warnings.add("操作 " + name + " 使用动态HTTP路径，未生成可选操作"); continue;
                            }
                            actualEndpoint = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/")
                                    .resolve(location.startsWith("/") ? location.substring(1) : location).toString();
                        }
                        try { EtlHttpRows.endpoint(actualEndpoint, executionAddresses); }
                        catch (IllegalArgumentException unauthorized) { warnings.add("操作 " + name + " 的端点未获执行资源授权，未生成可选操作"); continue; }
                        Element declaration = named(children(portType, WSDL, "operation"), name);
                        Element messageInput = only(children(declaration, WSDL, "input"));
                        Element message = definition(documents, "message", qualified(messageInput, messageInput.getAttribute("message")));
                        var parameters = new ArrayList<Parameter>(); boolean partial = false;
                        String namespace = binding.getOwnerDocument().getDocumentElement().getAttribute("targetNamespace");
                        if (!protocol.equals(HTTP)) {
                            String explicit = only(children(input, protocol, "body")).getAttribute("namespace");
                            if (!explicit.isBlank()) namespace = explicit;
                        }
                        var parts = children(message, WSDL, "part");
                        for (Element part : parts) {
                            if (!part.getAttribute("element").isBlank()) {
                                QName qname = qualified(part, part.getAttribute("element")); namespace = qname.getNamespaceURI();
                                Element root = schemaElement(documents, qname);
                                var complex = root == null ? List.<Element>of() : children(root, XSD, "complexType");
                                var sequences = complex.size() == 1 ? children(complex.get(0), XSD, "sequence") : List.<Element>of();
                                if (sequences.size() != 1) { partial = true; continue; }
                                if (elements(complex.get(0)).size() != 1) partial = true;
                                for (Element field : elements(sequences.get(0))) {
                                    if (!XSD.equals(field.getNamespaceURI()) || !field.getLocalName().equals("element")) { partial = true; continue; }
                                    String type = primitive(field);
                                    boolean repeated = !Set.of("", "1").contains(field.getAttribute("maxOccurs"));
                                    if (type == null || repeated || !elements(field).isEmpty()) partial = true;
                                    parameters.add(new Parameter(metadataName(field.getAttribute("name")), type, !"0".equals(field.getAttribute("minOccurs"))));
                                }
                            } else {
                                String type = primitive(part); if (type == null) partial = true;
                                parameters.add(new Parameter(metadataName(part.getAttribute("name")), type, null));
                            }
                            if (parameters.size() > 256) throw invalid("WSDL参数超过分析预算");
                        }
                        if (namespace.length() > 2048) throw invalid("WSDL命名空间超过预算");
                        entries.add(new Operation(name, namespace, List.copyOf(parameters), partial));
                    }
                    if (!entries.isEmpty()) ports.add(new Port(metadataName(port.getAttribute("name")), endpoint, method, List.copyOf(entries)));
                }
            }
            if (!ports.isEmpty()) services.add(new Service(metadataName(service.getAttribute("name")), List.copyOf(ports)));
            if (services.size() > 64) throw invalid("WSDL服务超过分析预算");
        }
        if (services.isEmpty()) throw invalid("WSDL未提供当前已授权执行通道支持的服务操作");
        if (services.stream().flatMap(service -> service.ports().stream()).flatMap(port -> port.operations().stream()).anyMatch(Operation::partial))
            warnings.add("复杂或重复XSD参数仅展示已声明名称，未知类型需人工核对；分析不代表操作执行成功");
        return new Metadata(List.copyOf(services), List.copyOf(warnings));
    }

    private static String primitive(Element element) {
        if (element.getAttribute("type").isBlank()) return null;
        QName name = qualified(element, element.getAttribute("type"));
        return XSD.equals(name.getNamespaceURI()) ? name.getLocalPart() : null;
    }

    private static String metadataName(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) throw invalid("WSDL声明名称无效");
        return name;
    }

    public static void read(JSONObject plan, Set<String> allowed, Function<String, String> secrets, Consumer<String> receiver) {
        int limit = plan.getInt("maxResponseBytes", 8_388_608), rowsLimit = plan.getInt("maxRows", 100_000);
        if (limit < 1 || limit > 16_777_216 || rowsLimit < 1 || rowsLimit > 1_000_000) throw invalid("WebService读取预算无效");
        var documents = new LinkedHashMap<String, Document>();
        load(plan.getStr("apiUrl"), allowed, limit, documents);
        String method = plan.getStr("apiMethod", "soap11"), operation = plan.getStr("operationName");
        String protocol = switch (method) { case "soap11" -> SOAP11; case "soap12" -> SOAP12; case "httpGet", "httpPost" -> HTTP; default -> throw invalid("WebService请求方式无效"); };
        var ports = new ArrayList<Element>();
        for (Document document : documents.values()) for (Element service : children(document.getDocumentElement(), WSDL, "service")) {
            if (!plan.getStr("serviceName", "").isBlank() && !service.getAttribute("name").equals(plan.getStr("serviceName"))) continue;
            for (Element port : children(service, WSDL, "port")) {
                if (!plan.getStr("portName", "").isBlank() && !port.getAttribute("name").equals(plan.getStr("portName"))) continue;
                if (children(port, protocol, "address").size() == 1) ports.add(port);
            }
        }
        if (ports.size() != 1) throw invalid("WSDL服务端口缺失或不唯一，请明确服务与端口");
        Element port = ports.get(0), binding = definition(documents, "binding", qualified(port, port.getAttribute("binding")));
        Element bindingOperation = named(children(binding, WSDL, "operation"), operation);
        String endpoint = children(port, protocol, "address").get(0).getAttribute("location");
        var headers = headers(plan.getJSONObject("headerParams"), plan.getJSONObject("headerSecretRefs"), secrets);
        JSONObject parameters = plan.getJSONObject("requestParams");
        if (parameters == null) parameters = JSONUtil.createObj();
        var query = new LinkedHashMap<String, String>();
        byte[] body = new byte[0];
        boolean soap = method.startsWith("soap");
        if (soap) {
            Element soapOperation = only(children(bindingOperation, protocol, "operation"));
            Element input = only(children(bindingOperation, WSDL, "input"));
            Element soapBody = only(children(input, protocol, "body"));
            if (!"literal".equals(soapBody.getAttribute("use"))) throw invalid("当前WebService支持literal绑定，encoded绑定需要独立适配");
            String action = soapOperation.getAttribute("soapAction");
            if (action.length() > 4096 || action.indexOf('"') >= 0 || action.chars().anyMatch(character -> character < 32)) throw invalid("WSDL SOAPAction无效");
            String envelope = method.equals("soap11") ? "http://schemas.xmlsoap.org/soap/envelope/" : "http://www.w3.org/2003/05/soap-envelope";
            headers.put("Content-Type", method.equals("soap11") ? "text/xml; charset=UTF-8" : "application/soap+xml; charset=UTF-8" + (action.isBlank() ? "" : "; action=\"" + action + "\""));
            if (method.equals("soap11")) headers.put("SOAPAction", "\"" + action + "\"");
            Element bindingProtocol = only(children(binding, protocol, "binding"));
            String style = soapOperation.getAttribute("style");
            if (style.isBlank()) style = bindingProtocol.getAttribute("style");
            QName request;
            boolean qualifiedChildren = false;
            if (style.equals("rpc")) {
                String namespace = soapBody.getAttribute("namespace");
                if (namespace.isBlank()) namespace = binding.getOwnerDocument().getDocumentElement().getAttribute("targetNamespace");
                request = new QName(namespace, operation);
            } else {
                Element portType = definition(documents, "portType", qualified(binding, binding.getAttribute("type")));
                Element portOperation = named(children(portType, WSDL, "operation"), operation);
                Element message = definition(documents, "message", qualified(only(children(portOperation, WSDL, "input")), only(children(portOperation, WSDL, "input")).getAttribute("message")));
                Element part = only(children(message, WSDL, "part"));
                if (part.getAttribute("element").isBlank()) throw invalid("document绑定需要唯一的element消息部分");
                request = qualified(part, part.getAttribute("element"));
                Element declaration = schemaElement(documents, request);
                qualifiedChildren = declaration != null && "qualified".equals(((Element) declaration.getParentNode()).getAttribute("elementFormDefault"));
            }
            String namespace = request.getNamespaceURI();
            if (!plan.getStr("targetNamespace", "").isBlank() && !namespace.equals(plan.getStr("targetNamespace"))) throw invalid("所选请求命名空间与WSDL不一致");
            var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"").append(envelope)
                    .append("\"><s:Body><m:").append(request.getLocalPart()).append(" xmlns:m=\"").append(escape(namespace)).append("\">");
            for (var entry : parameters.entrySet()) append(xml, entry.getKey(), entry.getValue(), qualifiedChildren ? "m:" : "", 0);
            xml.append("</m:").append(request.getLocalPart()).append("></s:Body></s:Envelope>");
            body = xml.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            Element httpBinding = only(children(binding, HTTP, "binding"));
            if (!httpBinding.getAttribute("verb").equals(method.equals("httpGet") ? "GET" : "POST")) throw invalid("HTTP请求方式与WSDL绑定不一致");
            Element httpOperation = only(children(bindingOperation, HTTP, "operation"));
            String location = httpOperation.getAttribute("location");
            Element input = only(children(bindingOperation, WSDL, "input"));
            if (!children(input, HTTP, "urlReplacement").isEmpty()) {
                for (var entry : parameters.entrySet()) location = location.replace("(" + entry.getKey() + ")", java.net.URLEncoder.encode(scalar(entry.getValue()), StandardCharsets.UTF_8).replace("+", "%20"));
                if (location.contains("(") || location.contains(")")) throw invalid("HTTP路径参数未填完整");
            } else {
                if (method.equals("httpGet") && children(input, HTTP, "urlEncoded").isEmpty()) throw invalid("WSDL HTTP GET缺少urlEncoded声明");
                for (var entry : parameters.entrySet()) query.put(entry.getKey(), scalar(entry.getValue()));
            }
            endpoint = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/").resolve(location.startsWith("/") ? location.substring(1) : location).toString();
            if (method.equals("httpPost")) {
                headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                body = String.join("&", query.entrySet().stream().map(entry -> java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "=" + java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).toList()).getBytes(StandardCharsets.UTF_8);
                query.clear();
            }
        }
        var response = EtlManagedHttp.exchange(endpoint, allowed, method.equals("httpGet") ? "GET" : "POST", query, headers, body, limit);
        String format = plan.getStr("returnFormat", "XML");
        List<Object> values = new ArrayList<>();
        if (soap || format.equals("XML")) {
            Element root = xml(response.bytes()).getDocumentElement();
            if (soap) {
                String envelope = method.equals("soap11") ? "http://schemas.xmlsoap.org/soap/envelope/" : "http://www.w3.org/2003/05/soap-envelope";
                if (!root.getLocalName().equals("Envelope") || !envelope.equals(root.getNamespaceURI())) throw invalid("SOAP响应信封版本不符");
                Element responseBody = only(children(root, envelope, "Body"));
                if (!children(responseBody, envelope, "Fault").isEmpty()) throw invalid("WebService返回SOAP Fault");
                root = only(elements(responseBody));
            }
            List<Element> selected = select(root, plan.getStr("returnDataLine", ""));
            for (Element element : selected) {
                if (format.equals("XML")) values.add(object(element, 0));
                else values.addAll(jsonValues(element.getTextContent(), format, ""));
            }
        } else values.addAll(jsonValues(new String(response.bytes(), StandardCharsets.UTF_8), format, plan.getStr("returnDataLine", "")));
        if (values.size() > rowsLimit) throw invalid("WebService返回行数超过预算");
        for (Object value : values) {
            if (!(value instanceof JSONObject row)) throw invalid("WebService返回行必须为对象");
            receiver.accept(EtlNativeRowGuard.normalizeDeclared(row.toString(), plan));
        }
    }

    private static void load(String address, Set<String> allowed, int limit, Map<String, Document> documents) {
        load(address, allowed, limit, documents, Long.MAX_VALUE, 16);
    }

    private static void load(String address, Set<String> allowed, int limit, Map<String, Document> documents, long deadline, int maximumDocuments) {
        if (documents.containsKey(address)) return;
        if (documents.size() >= maximumDocuments) throw invalid("WSDL与XSD导入超过文档预算");
        long remaining = deadline == Long.MAX_VALUE ? 30_000 : (deadline - System.nanoTime()) / 1_000_000;
        if (remaining < 100) throw invalid("WSDL分析超过总时间预算");
        byte[] bytes = EtlManagedHttp.exchange(address, allowed, "GET", Map.of(), Map.of(), new byte[0], limit, (int) Math.min(30_000, remaining)).bytes();
        long consumed = documents.values().stream().mapToLong(document -> (Integer) document.getUserData("sourceBytes")).sum();
        if (consumed + bytes.length > limit) throw invalid("WSDL与XSD总大小超过读取预算");
        Document document = xml(bytes); document.setUserData("sourceBytes", bytes.length, null);
        documents.put(address, document);
        var all = document.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < all.getLength(); index++) {
            Element element = (Element) all.item(index);
            String location = WSDL.equals(element.getNamespaceURI()) && element.getLocalName().equals("import") ? element.getAttribute("location")
                    : XSD.equals(element.getNamespaceURI()) && Set.of("import", "include").contains(element.getLocalName()) ? element.getAttribute("schemaLocation") : "";
            if (!location.isBlank()) load(URI.create(address).resolve(location).toString(), allowed, limit, documents, deadline, maximumDocuments);
        }
    }
    public static Document xml(byte[] bytes) {
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (Exception failure) { throw invalid("XML格式无效或包含禁止的实体与外部资源"); }
    }
    private static Map<String, String> headers(JSONObject plain, JSONObject references, Function<String, String> secrets) {
        var result = new LinkedHashMap<String, String>();
        if (plain != null) plain.forEach((name, value) -> result.put(name, String.valueOf(value)));
        if (references != null) references.forEach((name, reference) -> result.put(name, secrets.apply(String.valueOf(reference))));
        return result;
    }
    private static Element definition(Map<String, Document> documents, String kind, QName name) {
        var found = new ArrayList<Element>();
        for (Document document : documents.values()) if (name.getNamespaceURI().equals(document.getDocumentElement().getAttribute("targetNamespace")))
            for (Element element : children(document.getDocumentElement(), WSDL, kind)) if (name.getLocalPart().equals(element.getAttribute("name"))) found.add(element);
        return only(found);
    }
    private static Element schemaElement(Map<String, Document> documents, QName name) {
        for (Document document : documents.values()) {
            var schemas = document.getElementsByTagNameNS(XSD, "schema");
            for (int index = 0; index < schemas.getLength(); index++) {
                Element schema = (Element) schemas.item(index);
                if (schema.getAttribute("targetNamespace").equals(name.getNamespaceURI()))
                    for (Element element : children(schema, XSD, "element")) if (element.getAttribute("name").equals(name.getLocalPart())) return element;
            }
        }
        return null;
    }
    private static QName qualified(Element context, String value) {
        String[] parts = value.split(":", -1);
        if (parts.length > 2 || !parts[parts.length - 1].matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) throw invalid("WSDL限定名无效");
        String namespace = context.lookupNamespaceURI(parts.length == 2 ? parts[0] : null);
        if (namespace == null) throw invalid("WSDL限定名没有命名空间绑定");
        return new QName(namespace, parts[parts.length - 1]);
    }
    private static Element named(List<Element> elements, String name) { return only(elements.stream().filter(element -> element.getAttribute("name").equals(name)).toList()); }
    private static Element only(List<Element> elements) { if (elements.size() != 1) throw invalid("WSDL或XML节点缺失或不唯一"); return elements.get(0); }
    private static List<Element> elements(Element parent) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element element) result.add(element);
        return result;
    }
    private static List<Element> children(Element parent, String namespace, String name) {
        return elements(parent).stream().filter(element -> namespace.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())).toList();
    }
    private static void append(StringBuilder xml, String name, Object value, String prefix, int depth) {
        if (depth > 64 || !name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) throw invalid("WebService请求参数名称或嵌套深度无效");
        if (value instanceof Iterable<?> array) { for (Object item : array) append(xml, name, item, prefix, depth + 1); return; }
        xml.append('<').append(prefix).append(name);
        if (value == null || JSONUtil.isNull(value)) { xml.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:nil=\"true\"/>"); return; }
        xml.append('>');
        if (value instanceof Map<?, ?> object) for (var entry : object.entrySet()) append(xml, String.valueOf(entry.getKey()), entry.getValue(), prefix, depth + 1);
        else xml.append(escape(scalar(value)));
        xml.append("</").append(prefix).append(name).append('>');
    }
    private static String scalar(Object value) { if (value instanceof Map<?, ?> || value instanceof Iterable<?>) throw invalid("HTTP参数必须为标量"); return JSONUtil.isNull(value) ? "" : String.valueOf(value); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static List<Element> select(Element root, String path) {
        if (path.isBlank()) return List.of(root);
        if (!path.matches("[A-Za-z_][A-Za-z0-9_.-]*(?:/[A-Za-z_][A-Za-z0-9_.-]*)*")) throw invalid("XML返回行路径必须为以斜线分隔的节点名称");
        List<Element> current = List.of(root); String[] parts = path.split("/"); int start = parts[0].equals(root.getLocalName()) ? 1 : 0;
        for (int index = start; index < parts.length; index++) {
            String name = parts[index]; current = current.stream().flatMap(element -> elements(element).stream()).filter(element -> element.getLocalName().equals(name)).toList();
        }
        return current;
    }
    private static Object object(Element element, int depth) {
        if (depth > 64) throw invalid("XML响应嵌套超过预算");
        if (element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil").equals("true")) return null;
        List<Element> children = elements(element);
        if (children.isEmpty()) return element.getTextContent();
        var result = JSONUtil.createObj();
        for (Element child : children) {
            String name = child.getLocalName(); Object value = object(child, depth + 1);
            if (!result.containsKey(name)) result.set(name, value);
            else if (result.get(name) instanceof JSONArray array) array.add(value);
            else result.set(name, new JSONArray().put(result.get(name)).put(value));
        }
        return result;
    }
    static List<Object> jsonValues(String value, String format, String path) {
        Object parsed;
        try { parsed = JSONUtil.parse(value, cn.hutool.json.JSONConfig.create().setCheckDuplicate(true).setIgnoreError(false).setIgnoreNullValue(false).setStripTrailingZeros(false)); }
        catch (RuntimeException failure) { throw invalid("WebService响应节点不是有效JSON或包含重复字段"); }
        Object selected = EtlHttpRows.resolve(parsed, path);
        if (format.equals("JSON对象") && selected instanceof JSONObject object) return List.of(object);
        if (format.equals("JSON数组") && selected instanceof JSONArray array) {
            for (Object row : array) if (!(row instanceof JSONObject)) throw invalid("WebService JSON数组必须仅包含对象行");
            return new ArrayList<>(array);
        }
        throw invalid("WebService JSON返回形状与声明格式不符");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

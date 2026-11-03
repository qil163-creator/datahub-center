package cn.iocoder.yudao.framework.dbswitch.product.odps;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.CatalogMetadata;
import cn.iocoder.yudao.framework.dbswitch.spi.HttpConnectionProbeSupport;
import com.aliyun.odps.Odps;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.rest.RestClient;
import com.aliyun.odps.type.AbstractCharTypeInfo;
import com.aliyun.odps.type.DecimalTypeInfo;
import com.aliyun.odps.type.TypeInfoParser;
import com.aliyun.odps.rest.ResourceBuilder;
import org.w3c.dom.Element;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 官方 SDK 普通项目目录；只取 REST 表清单与表结构，不调用 SQLTask 或 Tunnel。 */
public final class OdpsCatalogMetadata implements CatalogMetadata {
    private final OdpsReadTransport.Opener opener;

    public OdpsCatalogMetadata() {
        this(uri -> (java.net.HttpURLConnection) uri.toURL().openConnection());
    }

    OdpsCatalogMetadata(OdpsReadTransport.Opener opener) { this.opener = opener; }

    @Override
    public List<Table> tables(ConnectionProbeRequest request) {
        String project = project(request);
        String resource = ResourceBuilder.buildTablesResource(project);
        var client = client(request, resource, Set.of("expectmarker", "marker", "maxitems"));
        try {
            var result = new ArrayList<Table>();
            var names = new HashSet<String>();
            var markers = new HashSet<String>();
            String marker = "";
            do {
                var parameters = new HashMap<String, String>();
                parameters.put("expectmarker", "true");
                parameters.put("maxitems", "256");
                if (!marker.isEmpty()) parameters.put("marker", marker);
                Element root = xml(client.getRestClient().request(resource, "GET", parameters, null, null).getBody(), "Tables");
                for (Element item : children(root, "Table")) {
                    String name = text(item, "Name"); identifier(name);
                    if (result.size() >= 5000 || !names.add(name)) throw new IllegalArgumentException();
                    String type = text(item, "Type");
                    Boolean view = "VIRTUAL_VIEW".equals(type) ? Boolean.TRUE
                            : Set.of("MANAGED_TABLE", "EXTERNAL_TABLE", "MATERIALIZED_VIEW").contains(type == null ? "" : type) ? Boolean.FALSE : null;
                    result.add(new Table(name, text(item, "Comment"), view));
                }
                marker = text(root, "Marker");
                if (marker == null) marker = "";
                if (marker.length() > 2048 || marker.codePoints().anyMatch(Character::isISOControl)
                        || !marker.isEmpty() && !markers.add(marker)) throw new IllegalArgumentException();
            } while (!marker.isEmpty());
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("ODPS 表目录读取失败，请核对项目、目录权限与连接配置");
        }
    }

    @Override
    public Structure describe(ConnectionProbeRequest request, String table) {
        String project = project(request); identifier(table);
        String resource = ResourceBuilder.buildTableResource(project, table);
        var client = client(request, resource, Set.of());
        try {
            Element root = xml(client.getRestClient().request(resource, "GET", new HashMap<>(), null, null).getBody(), "Table");
            if (!table.equals(text(root, "Name"))) throw new IllegalArgumentException();
            var raw = new JsonParser().parse(text(root, "Schema")).getAsJsonObject();
            var nullable = nullability(raw);
            var columns = new ArrayList<Column>(); var names = new HashSet<String>();
            for (String key : List.of("columns", "partitionKeys")) {
                if (!raw.has(key)) continue;
                for (var value : raw.getAsJsonArray(key)) columns.add(column(value.getAsJsonObject(), key.equals("partitionKeys"), names, nullable));
            }
            if (columns.isEmpty() || columns.size() > 5000) throw new IllegalArgumentException();
            String comment = raw.has("comment") && !raw.get("comment").isJsonNull() ? raw.get("comment").getAsString() : text(root, "Comment");
            Boolean view = raw.has("isVirtualView") ? raw.get("isVirtualView").getAsBoolean() : null;
            return new Structure(new Table(table, comment, view), columns);
        } catch (Exception failure) {
            throw new IllegalStateException("ODPS 表结构读取失败，请核对表名、结构权限与受支持的普通项目");
        }
    }

    private static Column column(JsonObject column, boolean partition, Set<String> names, Map<String, Boolean> nullable) {
        String name = column.get("name").getAsString();
        if (name.isBlank() || name.length() > 128 || !names.add(name)) throw new IllegalArgumentException();
        String nativeType = column.get("type").getAsString();
        if (nativeType.length() > 4000) throw new IllegalArgumentException();
        var type = TypeInfoParser.getTypeInfoFromTypeString(nativeType);
        Integer length = type instanceof AbstractCharTypeInfo text ? text.getLength() : null;
        Integer precision = type instanceof DecimalTypeInfo decimal ? decimal.getPrecision() : null;
        Integer scale = type instanceof DecimalTypeInfo decimal ? decimal.getScale() : null;
        String comment = column.has("comment") && !column.get("comment").isJsonNull() ? column.get("comment").getAsString() : null;
        return new Column(name, type.getTypeName(), comment, nullable.get(name), length, precision, scale, partition);
    }

    private static Element xml(byte[] bytes, String expectedRoot) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        var root = builder.parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement();
        if (!expectedRoot.equals(root.getTagName())) throw new IllegalArgumentException();
        return root;
    }

    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && element.getTagName().equals(name)) result.add(element);
        return result;
    }

    private static String text(Element parent, String name) {
        var matches = children(parent, name);
        if (matches.size() > 1) throw new IllegalArgumentException();
        return matches.isEmpty() ? null : matches.getFirst().getTextContent();
    }

    private static Map<String, Boolean> nullability(JsonObject schema) {
        var result = new HashMap<String, Boolean>();
        for (String key : List.of("columns", "partitionKeys")) {
            if (!schema.has(key)) continue;
            for (var value : schema.getAsJsonArray(key)) {
                var column = value.getAsJsonObject();
                if (column.has("isNullable") && column.get("isNullable").isJsonPrimitive()
                        && column.getAsJsonPrimitive("isNullable").isBoolean())
                    result.put(column.get("name").getAsString(), column.get("isNullable").getAsBoolean());
            }
        }
        return result;
    }

    private Odps client(ConnectionProbeRequest request, String path, Set<String> queryKeys) {
        URI endpoint = HttpConnectionProbeSupport.endpoint(request.endpoint());
        if (!"https".equals(endpoint.getScheme())) throw new IllegalArgumentException("ODPS 目录必须使用 TLS");
        return new CatalogOdps(request, endpoint, path, queryKeys, opener);
    }

    private static String project(ConnectionProbeRequest request) {
        if (request.product() != ProductTypeEnum.ODPS || request.username() == null || request.password() == null
                || request.username().isBlank() || request.password().isBlank()
                || request.username().length() > 256 || request.password().length() > 1024
                || (request.username() + request.password()).codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("ODPS 目录连接配置无效");
        String project = request.requiredProperty("project");
        if (!project.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("ODPS 项目名称无效");
        return project;
    }

    private static void identifier(String table) {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("ODPS 目录仅支持普通表名称");
    }

    private static final class CatalogOdps extends Odps {
        private CatalogOdps(ConnectionProbeRequest request, URI endpoint, String path, Set<String> queryKeys, OdpsReadTransport.Opener opener) {
            super(new AliyunAccount(request.username(), request.password()));
            client = new RestClient(new OdpsReadTransport(endpoint, request.timeout(), Set.of(OdpsReadTransport.path(endpoint, path)), queryKeys, opener));
            client.setAccount(getAccount());
            setEndpoint(endpoint.toString().replaceAll("/+$", ""));
            setDefaultProject(request.requiredProperty("project"));
            // 只调用 RestClient 的目录资源，避免 SDK Table 对外部项目回退为 SQL 作业或加载 Arrow。
            client.setIgnoreCerts(false);
            client.setRetryTimes(0);
            client.setConnectTimeout((int) request.timeout().toSeconds());
            client.setReadTimeout((int) request.timeout().toSeconds());
        }
    }
}

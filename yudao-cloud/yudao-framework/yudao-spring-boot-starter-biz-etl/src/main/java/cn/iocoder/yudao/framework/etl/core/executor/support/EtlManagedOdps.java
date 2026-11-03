package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aliyun.odps.Odps;
import com.aliyun.odps.PartitionSpec;
import com.aliyun.odps.TableSchema;
import com.aliyun.odps.account.Account;
import com.aliyun.odps.account.AliyunAccount;
import com.aliyun.odps.account.StsAccount;
import com.aliyun.odps.rest.RestClient;
import com.aliyun.odps.tunnel.TableTunnel;
import com.aliyun.odps.tunnel.io.CompressOption;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** 官方 TableTunnel 执行端适配；秘密不进入 Spark 计划或上传分片工厂。 */
public final class EtlManagedOdps {
    private EtlManagedOdps() { }
    public record Client(TableTunnel tunnel, String project, String table, PartitionSpec partition) { }

    public static Client connect(JSONObject plan, boolean output, Function<String, String> secrets) {
        try {
            JSONObject secret = JSONUtil.parseObj(secrets.apply(plan.getStr("credentialRef")));
            if (!secret.keySet().equals(Set.of("serviceEndpoint", "tunnelEndpoint", "project", "accessId", "accessSecret", "securityToken", "allowPlainHttp", "resources"))) throw new IllegalArgumentException();
            for (String key : List.of("serviceEndpoint", "tunnelEndpoint", "project")) if (!Objects.equals(plan.getStr(key), secret.getStr(key))) throw new IllegalArgumentException();
            URI service = EtlOdpsTransport.endpoint(URI.create(plan.getStr("serviceEndpoint"))), tunnel = EtlOdpsTransport.endpoint(URI.create(plan.getStr("tunnelEndpoint")));
            if ((!service.getScheme().equals("https") || !tunnel.getScheme().equals("https")) && !(Boolean.TRUE.equals(plan.getBool("allowPlainHttp")) && Boolean.TRUE.equals(secret.getBool("allowPlainHttp"))))
                throw new IllegalArgumentException();
            String table = plan.getStr("table"), partition = canonicalPartition(plan.getStr("partition", ""));
            var resources = secret.getJSONArray("resources"); if (resources == null || resources.isEmpty() || resources.size() > 100) throw new IllegalArgumentException();
            long approved = resources.stream().map(JSONUtil::parseObj).filter(resource -> resource.keySet().equals(Set.of("table", "partition", "source", "sink", "allowOverwrite"))
                    && Objects.equals(table, resource.getStr("table")) && partition.equals(canonicalPartition(resource.getStr("partition", "")))
                    && Boolean.TRUE.equals(resource.getBool(output ? "sink" : "source"))
                    && (!output || !Boolean.TRUE.equals(plan.getBool("overwrite")) || Boolean.TRUE.equals(resource.getBool("allowOverwrite")))).count();
            if (approved != 1) throw new IllegalArgumentException();
            String accessId = secret.getStr("accessId", ""), accessSecret = secret.getStr("accessSecret", ""), token = secret.getStr("securityToken", "");
            if (accessId.isBlank() || accessSecret.isBlank() || accessId.length() > 256 || accessSecret.length() > 1024 || token.length() > 2048
                    || (accessId + accessSecret + token).codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            Account account = token.isEmpty() ? new AliyunAccount(accessId, accessSecret) : new StsAccount(accessId, accessSecret, token);
            Odps odps = new BoundOdps(account, service, tunnel, plan.getStr("project"), table, output);
            var configuration = new com.aliyun.odps.tunnel.Configuration(odps); configuration.setSocketRetryTimes(0);
            var transfer = new TableTunnel(odps, configuration); transfer.setEndpoint(tunnel.toString());
            return new Client(transfer, plan.getStr("project"), table, partition.isEmpty() ? null : new PartitionSpec(partition));
        } catch (Exception failure) { throw new IllegalStateException("ODPS 受管认证、端点或表分区授权无效"); }
    }

    /** SDK 默认 clone 会重建默认 Transport，必须在 Tunnel 克隆时保留同一物理边界。 */
    private static final class BoundOdps extends Odps {
        private final URI service, tunnel; private final String project, table; private final boolean output;
        BoundOdps(Account account, URI service, URI tunnel, String project, String table, boolean output) {
            super(account); this.service = service; this.tunnel = tunnel; this.project = project; this.table = table; this.output = output;
            client = new RestClient(new EtlOdpsTransport(service, tunnel, project, table, output)); client.setAccount(account);
            setEndpoint(service.toString()); setTunnelEndpoint(tunnel.toString()); setDefaultProject(project);
            client.setIgnoreCerts(false); client.setConnectTimeout(10); client.setReadTimeout(60); client.setRetryTimes(0);
        }
        @Override public Odps clone() { return new BoundOdps(getAccount(), service, tunnel, project, table, output); }
    }

    public static void read(JSONObject plan, Function<String, String> secrets, Consumer<String> rows) {
        try {
            Client client = connect(plan, false, secrets);
            var session = client.tunnel().buildDownloadSession(client.project(), client.table())
                    .setPartitionSpec(client.partition()).setEnableMaxStorage(false).setAsyncMode(false).setWaitAsyncBuild(false).build();
            validateSchema(plan, session.getSchema());
            long expected = session.getRecordCount(); if (expected < 0 || expected > plan.getLong("maxRows")) throw new IllegalArgumentException();
            if (expected == 0) return;
            var selected = plan.getJSONArray("selectedColumns").toList(String.class); long count = 0, bytes = 0;
            var columns = selected.stream().map(session.getSchema()::getColumn).toList();
            try (var reader = session.openRecordReader(0, expected, new CompressOption(CompressOption.CompressAlgorithm.ODPS_RAW, 0, 0), columns)) {
                com.aliyun.odps.data.Record record;
                while ((record = reader.read()) != null) {
                    if (++count > expected) throw new IllegalArgumentException();
                    var result = JSONUtil.createObj();
                    for (String name : selected) result.set(name, text(record.get(name), record, name, plan.getJSONObject("columns").getStr(name)));
                    String row = EtlNativeRowGuard.validate(result.toString(), plan); bytes += row.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > plan.getLong("maxBytes")) throw new IllegalArgumentException(); rows.accept(row);
                }
            }
            if (count != expected) throw new IllegalArgumentException();
        } catch (Exception failure) { throw new IllegalStateException("ODPS Tunnel 读取失败，请核查表分区、真实字段、预算或执行认证"); }
    }

    public static TableTunnel.UploadSession upload(JSONObject plan, Function<String, String> secrets, String sessionId) {
        try {
            Client client = connect(plan, true, secrets); TableTunnel.UploadSession session;
            if (sessionId == null) session = client.partition() == null
                    ? client.tunnel().createUploadSession(client.project(), client.table(), plan.getBool("overwrite", false))
                    : client.tunnel().createUploadSession(client.project(), client.table(), client.partition(), plan.getBool("overwrite", false));
            else session = client.partition() == null ? client.tunnel().getUploadSession(client.project(), client.table(), sessionId)
                    : client.tunnel().getUploadSession(client.project(), client.table(), client.partition(), sessionId);
            validateSchema(plan, session.getSchema()); return session;
        } catch (Exception failure) { throw new IllegalStateException("ODPS Tunnel 上传会话不可用或真实字段不一致"); }
    }

    public static void validateSchema(JSONObject plan, TableSchema actual) {
        var expected = plan.getJSONObject("columns"); if (expected == null || actual == null || expected.isEmpty()) throw new IllegalArgumentException("ODPS 字段合同缺失");
        var observed = new LinkedHashMap<String, String>();
        for (var column : actual.getColumns()) observed.put(column.getName(), column.getTypeInfo().getTypeName().toUpperCase(Locale.ROOT).replace(" ", ""));
        if (!new ArrayList<>(expected.keySet()).equals(new ArrayList<>(observed.keySet()))) throw new IllegalArgumentException("ODPS 真实字段顺序或数量与受管合同不一致");
        for (String name : observed.keySet()) if (!observed.get(name).equals(expected.getStr(name).toUpperCase(Locale.ROOT).replace(" ", "")))
            throw new IllegalArgumentException("ODPS 真实字段类型与受管合同不一致");
        String partition = canonicalPartition(plan.getStr("partition", ""));
        var keys = actual.getPartitionColumns().stream().map(com.aliyun.odps.Column::getName).collect(java.util.stream.Collectors.toSet());
        if (!keys.equals(partition.isEmpty() ? Set.of() : new PartitionSpec(partition).keys())) throw new IllegalArgumentException("ODPS 必须精确指定全部物理分区键");
    }

    public static Object value(String nativeType, String value) {
        if (value == null) return null;
        String type = nativeType.toUpperCase(Locale.ROOT).replace(" ", "");
        if (type.equals("STRING")) return value;
        if (type.startsWith("DECIMAL(") && type.endsWith(")")) {
            String[] shape = type.substring(8, type.length() - 1).split(",");
            int precision = Integer.parseInt(shape[0]), scale = Integer.parseInt(shape[1]);
            if (precision < 1 || precision > 38 || scale < 0 || scale > precision) throw new IllegalArgumentException();
            var decimal = new BigDecimal(value);
            if ((long) decimal.precision() - decimal.scale() > precision - scale || decimal.stripTrailingZeros().scale() > scale) throw new IllegalArgumentException();
            decimal = decimal.setScale(scale, java.math.RoundingMode.UNNECESSARY);
            if (decimal.precision() > precision) throw new IllegalArgumentException(); return decimal;
        }
        return switch (type) {
            case "BIGINT" -> new BigDecimal(value).longValueExact(); case "INT" -> new BigDecimal(value).intValueExact();
            case "SMALLINT" -> { int parsed = new BigDecimal(value).intValueExact(); if (parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) throw new IllegalArgumentException(); yield (short) parsed; }
            case "TINYINT" -> { int parsed = new BigDecimal(value).intValueExact(); if (parsed < Byte.MIN_VALUE || parsed > Byte.MAX_VALUE) throw new IllegalArgumentException(); yield (byte) parsed; }
            case "DOUBLE" -> { double parsed = Double.parseDouble(value); if (!Double.isFinite(parsed) || parsed == 0 && new BigDecimal(value).signum() != 0) throw new IllegalArgumentException(); yield parsed; }
            case "FLOAT" -> {
                double exact = Double.parseDouble(value); float parsed = Float.parseFloat(value);
                if (!Double.isFinite(exact) || !Float.isFinite(parsed) || Double.compare(parsed, exact) != 0
                        || BigDecimal.valueOf(exact).compareTo(new BigDecimal(value)) != 0) throw new IllegalArgumentException();
                yield parsed;
            }
            case "BOOLEAN" -> { if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(); yield Boolean.valueOf(value); }
            case "DATE" -> java.time.LocalDate.parse(value);
            case "DATETIME" -> { Instant timestamp = Instant.parse(value); if (timestamp.getNano() % 1000000 != 0) throw new IllegalArgumentException(); yield java.util.Date.from(timestamp); }
            case "TIMESTAMP" -> java.sql.Timestamp.from(Instant.parse(value));
            default -> throw new IllegalArgumentException("ODPS 字段类型尚未适配");
        };
    }
    static String text(Object value, com.aliyun.odps.data.Record record, String name, String type) throws Exception {
        if (value == null) return null;
        if (type.equalsIgnoreCase("STRING")) return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(record.getBytes(name))).toString();
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        // Spark 使用 DOUBLE 承载原生 FLOAT：先无损扩展二进制值，不能用 Float 的短小数文本改写值。
        if (value instanceof Float number) return Double.toString(number.doubleValue());
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof java.util.Date date) return date.toInstant().toString();
        if (value instanceof java.time.LocalDate date) return date.toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value.toString();
        throw new IllegalArgumentException("ODPS 非标量字段不能静默转换");
    }
    private static String canonicalPartition(String value) { return value == null || value.isBlank() ? "" : new PartitionSpec(value).toString(); }
}

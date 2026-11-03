package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlFileInput;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlTextValues;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlFileValueContractTest {
    @TempDir Path directory;

    @Test void csvActualWriterAndReaderPreserveNullEmptyWhitespaceLiteralTokenAndFormula() throws Exception {
        List<Object> values = new ArrayList<>(java.util.Arrays.asList(null, "", "  ", "\\N", "\\F=literal", "=SUM(1,2)", "\t=1", "中文,\"引号\"\n换行"));
        values.add(new BigDecimal("12345678901234567890.0000100"));
        List<Map<String, Object>> input = values.stream().map(value -> {
            Map<String, Object> row = new LinkedHashMap<>(); row.put("value", value); return row;
        }).toList();
        write("write-csv", "values.csv", "ARRAY", input);
        String content = Files.readString(directory.resolve("values.csv"));
        assertTrue(content.contains("\\N")); assertTrue(content.contains("\\\\N")); assertTrue(content.contains("\\F=SUM"));
        List<Map<String, Object>> output = read("read-csv", "values.csv");
        assertEquals(input.size(), output.size());
        for (int i = 0; i < input.size(); i++) {
            Object expected = values.get(i) instanceof BigDecimal decimal ? decimal.toPlainString() : values.get(i);
            assertEquals(expected, output.get(i).get("value"), "row " + i);
        }
    }

    @Test void jsonArrayAndNdjsonKeepNullAndExactNumbersAsStrings() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", new BigDecimal("12345678901234567890.0123456789"));
        row.put("id", Long.MAX_VALUE); row.put("small", 7); row.put("empty", ""); row.put("nil", null);
        write("write-json", "array.json", "ARRAY", List.of(row, row));
        var output = read("read-json", "array.json");
        assertEquals(2, output.size()); assertEquals("12345678901234567890.0123456789", output.getFirst().get("amount"));
        assertEquals(Long.toString(Long.MAX_VALUE), output.getFirst().get("id"));
        assertTrue(output.getFirst().containsKey("nil")); assertNull(output.getFirst().get("nil"));
        write("write-json", "lines", "NDJSON", List.of(row, row));
        assertEquals(2, Files.readAllLines(directory.resolve("lines.jsonl")).size());
        @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) EtlJsonValues.parse("{\"amount\":12345678901234567890.0123456789,\"id\":9223372036854775807}");
        assertEquals("12345678901234567890.0123456789", parsed.get("amount"));
        assertEquals("9223372036854775807", parsed.get("id"));
    }

    @Test void inputLimitsApplyToDecodedStreamsAndCodecDoesNotConvertBlankToNull() throws Exception {
        try (var input = EtlFileInput.bounded(new ByteArrayInputStream(new byte[9]), 8)) {
            assertThrows(IOException.class, input::readAllBytes);
        }
        assertEquals("", EtlTextValues.decode("")); assertEquals(" ", EtlTextValues.decode(" "));
        assertNull(EtlTextValues.decode("\\N"));
        assertEquals("=1+1", EtlTextValues.decode(EtlTextValues.encode("=1+1", true)));
    }

    @Test void wireCopyPreservesNestedPrecisionWithoutMutatingExecutionValuesAndBoundsDecimalExpansion() {
        var amount = new BigDecimal("12345678901234567890.0000100");
        var original = Map.<String, Object>of("nested", List.of(Map.of("amount", amount, "id", Long.MAX_VALUE)));
        var wire = EtlJsonValues.wireRow(original);
        var nested = (Map<?, ?>) ((List<?>) wire.get("nested")).getFirst();
        assertEquals("12345678901234567890.0000100", nested.get("amount"));
        assertEquals(Long.toString(Long.MAX_VALUE), nested.get("id"));
        assertSame(amount, ((Map<?, ?>) ((List<?>) original.get("nested")).getFirst()).get("amount"));
        assertThrows(IllegalArgumentException.class,
                () -> EtlJsonValues.wireRow(Map.of("huge", new BigDecimal("1e2147483647"))));
    }

    @Test void rawCsvNeverInterpretsReservedTokensAndEncodedHeadersRoundTrip() throws Exception {
        Files.writeString(directory.resolve("raw.csv"), "value\n\\N\n\\F=literal\n");
        var raw=read("read-csv","raw.csv","RAW");
        assertEquals("\\N",raw.get(0).get("value"));assertEquals("\\F=literal",raw.get(1).get("value"));
        write("write-csv","headers.csv","ARRAY",List.of(Map.of("=formula", "value", " ", "space")));
        assertTrue(Files.readString(directory.resolve("headers.csv")).contains("\\F=formula"));
        var result=read("read-csv","headers.csv");
        assertEquals("value",result.getFirst().get("=formula"));assertEquals("space",result.getFirst().get(" "));
    }

    @Test void excelActualWriterReaderKeepExactTextNullEmptyWhitespaceAndFormulaAsData() throws Exception {
        Map<String,Object> input=new LinkedHashMap<>();
        input.put("amount",new BigDecimal("12345678901234567890.0000100"));input.put("id",Long.MAX_VALUE);
        input.put("nil",null);input.put("empty","");input.put("space","  ");input.put("token","\\N");
        input.put("formula","=SUM(1,2)");input.put("=header","literal");input.put("bool",true);
        write("write-excel","values.xlsx","ARRAY",List.of(input));
        try(var inputStream=Files.newInputStream(directory.resolve("values.xlsx"));
            var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(inputStream)){
            var data=workbook.getSheetAt(0).getRow(1);
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,data.getCell(0).getCellType());
            assertEquals("12345678901234567890.0000100",data.getCell(0).getStringCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,data.getCell(1).getCellType());
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK,data.getCell(2).getCellType());
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,data.getCell(3).getCellType());
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,data.getCell(6).getCellType());
        }
        var output=read("read-excel","values.xlsx").getFirst();
        assertEquals("12345678901234567890.0000100",output.get("amount"));assertEquals(Long.toString(Long.MAX_VALUE),output.get("id"));
        assertNull(output.get("nil"));assertEquals("",output.get("empty"));assertEquals("  ",output.get("space"));
        assertEquals("\\N",output.get("token"));assertEquals("=SUM(1,2)",output.get("formula"));
        assertEquals("literal",output.get("=header"));assertEquals("true",output.get("bool"));
    }

    private void write(String code, String name, String format, List<Map<String, Object>> input) {
        var registry = new EtlExecutorRegistry();
        registry.register("fixture-source", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            @Override protected void execute() throws Exception { for (var row : input) pushRow(row); }
        });
        var target = node("target", code, Map.of("filePath", directory.toString(), "fileName", name, "format", format));
        try (var engine = new EtlPipelineEngine(registry)) {
            var result = engine.execute(dag(node("source", "fixture-source", Map.of()), target), EtlRunOptions.builder()
                    .fileOutputPolicy(new cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy(List.of(directory), false)).build());
            assertTrue(result.isSuccess(), result.getFirstError());
        }
    }

    private List<Map<String, Object>> read(String code, String name) throws Exception {
        return read(code,name,"DBSWITCH_CSV_V1");
    }

    private List<Map<String, Object>> read(String code, String name, String encoding) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture", exchange -> {
            byte[] content = Files.readAllBytes(directory.resolve(name)); exchange.sendResponseHeaders(200, content.length);
            try (var body = exchange.getResponseBody()) { body.write(content); }
        });
        server.start();
        try {
            List<Map<String, Object>> rows = new ArrayList<>(); var registry = new EtlExecutorRegistry();
            registry.register("fixture-sink", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
                @Override protected void execute() throws Exception { Map<String, Object> row; while ((row = readNextRow()) != null) rows.add(row); }
            });
            var source = node("source", code, Map.of("fileUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture", "valueEncoding",encoding));
            try (var engine = new EtlPipelineEngine(registry)) {
                var result = engine.execute(dag(source, node("target", "fixture-sink", Map.of())), EtlRunOptions.builder().build());
                assertTrue(result.isSuccess(), result.getFirstError()); return rows;
            }
        } finally { server.stop(0); }
    }

    private static EtlNodeDef node(String id, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code);
        node.setToolType(id.equals("source") ? EtlNodeDef.TOOL_TYPE_INPUT : EtlNodeDef.TOOL_TYPE_OUTPUT);
        node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlDag dag(EtlNodeDef source, EtlNodeDef target) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source.getNo()); edge.setTargetNo(target.getNo()); edge.setEdgeType("DATA");
        return EtlDag.build(List.of(source, target), List.of(edge));
    }
}

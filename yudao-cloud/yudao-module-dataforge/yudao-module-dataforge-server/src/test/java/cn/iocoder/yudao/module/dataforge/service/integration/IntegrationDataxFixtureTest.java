package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.model.DataxTaskParameters;
import cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 给真实 DS 验收导出同一编译链产物，不用手工 JSON 代替连接器编译结果。 */
class IntegrationDataxFixtureTest {
    @Test void exportsNativeCsvTaskFromAuthorizedBindingAndBothComponentBuilders() throws Exception {
        var binding = new IntegrationDataxBindingsProperties.ManagedBinding();
        binding.setAllowedTaskIds(Set.of(7L)); binding.setConnectorCodes(Set.of("read-csv", "write-csv"));
        binding.setInstalledPlugins(Set.of("txtfilereader", "txtfilewriter"));
        binding.setAuthentication(EtlDataxConnectorCompiler.Authentication.NONE);
        binding.setRootPath("/home/datahub/fixtures");
        var bindings = new IntegrationDataxBindingsProperties(); bindings.setBindings(Map.of("fixture", binding));
        var columns = List.of(Map.of("name", "id", "type", "LONG", "index", 0),
                Map.of("name", "名称", "type", "STRING", "index", 1));
        var source = Map.<String, Object>of("resourceBindingId", "fixture", "path", "source.csv", "columns", columns, "hasHeader", true);
        var target = Map.<String, Object>of("resourceBindingId", "fixture", "path", "out", "fileName", "replica", "columns", columns, "includeHeader", true);
        EtlDataxConnectorCompiler.validatePair(source, target);
        var reader = EtlDataxConnectorCompiler.compile("read-csv", source, bindings.require(7L, "read-csv", null, "fixture"));
        var writer = EtlDataxConnectorCompiler.compile("write-csv", target, bindings.require(7L, "write-csv", null, "fixture"));
        var parameters = DataxTaskParameters.custom(reader.taskParameter(), writer.taskParameter(),
                new DataxTaskParameters.RuntimeOptions(1024, 1024, 1, 0, 0, 0), List.of());
        assertEquals("nonConflict", writer.parameter().get("writeMode"));
        assertEquals(List.of("id", "名称"), writer.parameter().get("header"));
        assertEquals(List.of("/home/datahub/fixtures/source.csv"), reader.parameter().get("path"));
        assertEquals(1, parameters.get("customConfig"));
        var output = Path.of("target", "datax-connector-csv-native.json");
        Files.createDirectories(output.getParent()); Files.writeString(output, JSONUtil.toJsonPrettyStr(parameters));
    }
}

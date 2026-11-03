package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelineSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlRunCommandReqVO;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class EtlParameterWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();

    @Test
    void runValuesMustArriveAsJsonStringsToPreservePrecision() throws Exception {
        var request = mapper.readValue("""
                {"pipelineId":1,"requestId":"stable","expectedDefinitionRevision":4,
                 "parameters":{"id":"9007199254740993","amount":"0.123456789012345678901"}}
                """, EtlRunCommandReqVO.class);
        assertEquals("9007199254740993", request.getParameters().get("id"));
        assertEquals("0.123456789012345678901", request.getParameters().get("amount"));

        assertThrows(RuntimeException.class, () -> mapper.readValue("""
                {"pipelineId":1,"requestId":"unsafe","parameters":{"id":9007199254740993}}
                """, EtlRunCommandReqVO.class));
    }

    @Test
    void numericDefaultsAndBoundsMustAlsoBeJsonStrings() {
        assertThrows(RuntimeException.class, () -> mapper.readValue("""
                {"name":"sample","taskType":1,"parameters":[
                  {"name":"id","type":"INTEGER","defaultValue":9007199254740993,"min":1}]}
                """, EtlPipelineSaveReqVO.class));
    }

    @Test
    void responseKeepsLargeIdsExactAndOmitsUndefinedOptionalFields() throws Exception {
        var command = new EtlRunCommandReqVO();
        command.setPipelineId(9007199254740993L);
        command.setRequestId("wire");
        String commandJson = mapper.writeValueAsString(command);
        assertTrue(commandJson.contains("\"pipelineId\":\"9007199254740993\""), commandJson);

        var definition = new EtlPipelineSaveReqVO.ParamItem();
        definition.setName("label");
        String definitionJson = mapper.writeValueAsString(definition);
        assertFalse(definitionJson.contains("defaultValue"), definitionJson);
        assertFalse(definitionJson.contains("minLength"), definitionJson);
        assertFalse(definitionJson.contains("maxLength"), definitionJson);
    }
}

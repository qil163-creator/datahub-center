package cn.iocoder.yudao.framework.etl.core.schema;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段契约推导结果：节点编号 → 输入/输出字段
 *
 * @author Li
 */
@Data
public class EtlSchemaResult {

    @Data
    public static class NodeSchema {

        private List<EtlFieldMeta> inputFields = new ArrayList<>();

        private List<EtlFieldMeta> outputFields = new ArrayList<>();

        /** 分支编号对应其独立输出字段；旧 outputFields 继续作为汇总展示。 */
        private Map<String, List<EtlFieldMeta>> outputPorts = new LinkedHashMap<>();

        /** false 表示仅有局部字段提示，不能视为完整执行契约。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Boolean complete;

    }

    private Map<String, NodeSchema> nodes = new LinkedHashMap<>();

    private List<String> errors = new ArrayList<>();

    private List<String> warnings = new ArrayList<>();

}

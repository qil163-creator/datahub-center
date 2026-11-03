package cn.iocoder.yudao.module.system.controller.admin.dict;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.system.controller.admin.dict.vo.data.DictDataSimpleRespVO;
import cn.iocoder.yudao.module.system.dal.dataobject.dict.DictDataDO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 精简字典只新增可选备注，复用既有转换和全局 JSON 线协议。 */
class DictDataSimpleRespVOTest {
    @Test
    void simpleDictionaryWirePreservesGroupingRemarkWithoutAuditFields() {
        var source = new DictDataDO().setDictType("modeling_indicator_unit")
                .setValue("小时").setLabel("小时").setRemark("时间单位");
        source.setId(9007199254740993L); source.setCreator("内部审计字段");
        var response = BeanUtils.toBean(source, DictDataSimpleRespVO.class);
        var json = JsonUtils.toJsonString(response);
        assertThat(json).contains("\"remark\":\"时间单位\"", "\"value\":\"小时\"", "\"label\":\"小时\"")
                .doesNotContain("creator", "9007199254740993");
    }

    @Test
    void dictionaryWithoutRemarkKeepsTheExistingLabelsAndValues() {
        var source = new DictDataDO().setDictType("status").setValue("0").setLabel("启用");
        var response = BeanUtils.toBean(source, DictDataSimpleRespVO.class);
        assertThat(response.getRemark()).isNull();
        assertThat(JsonUtils.toJsonString(response)).contains("\"value\":\"0\"", "\"label\":\"启用\"");
    }
}

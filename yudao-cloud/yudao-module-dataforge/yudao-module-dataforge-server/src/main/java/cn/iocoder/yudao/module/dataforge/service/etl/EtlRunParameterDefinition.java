package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelineSaveReqVO;

import java.util.List;

/** 从手动运行实际采用的草稿或发布版本中选择参数定义。 */
public record EtlRunParameterDefinition(List<EtlPipelineSaveReqVO.ParamItem> parameters,
                                        Integer definitionRevision, boolean published) {

}

package cn.iocoder.yudao.module.dataforge.service.dataapi;

import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiFetchRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import jakarta.validation.Valid;

/**
 * 接口采集和 ETL 共用的接口试调与字段解析服务。
 *
 * @author Li
 */
public interface DataApiService {

    /**
     * 按表单配置试调 API，返回原始响应文本
     */
    String testDataApi(@Valid DataApiSaveReqVO reqVO);

    /**
     * 按表单配置取数：解析行数据并推断返回字段
     */
    DataApiFetchRespVO fetchData(@Valid DataApiSaveReqVO reqVO);

}

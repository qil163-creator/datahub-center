package cn.iocoder.yudao.module.dataforge.service.dataapi;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 接口试调与字段解析，不创建独立接口资源记录。
 *
 * @author Li
 */
@Service
@Validated
public class DataApiServiceImpl implements DataApiService {

    @Resource
    private DataApiClient dataApiClient;

    /**
     * 按表单配置试调 API，返回原始响应文本
     *
     * @param reqVO 请求参数
     * @return 按表单配置试调 API，返回原始响应文本文本
     */
    @Override
    public String testDataApi(DataApiSaveReqVO reqVO) {
        return dataApiClient.executeApi(reqVO,
                Integer.valueOf(1).equals(reqVO.getIfPage()) ? 1 : null, 20);
    }

    /**
     * 按表单配置取数：解析行数据并推断返回字段
     *
     * @param reqVO 请求参数
     * @return 数据 API获取响应数据
     */
    @Override
    public DataApiFetchRespVO fetchData(DataApiSaveReqVO reqVO) {
        List<Map<String, Object>> rows = dataApiClient.fetchRows(reqVO,
                Integer.valueOf(1).equals(reqVO.getIfPage()) ? 1 : null, 20);
        DataApiFetchRespVO respVO = new DataApiFetchRespVO();
        respVO.setRows(rows);
        List<ResponseColumnItem> columns = new ArrayList<>();
        if (CollUtil.isNotEmpty(rows)) {
            for (String key : rows.get(0).keySet()) {
                ResponseColumnItem column = new ResponseColumnItem();
                column.setFieldName(key);
                column.setFieldType(Types.VARCHAR);
                column.setDisplaySize(100);
                column.setPrecisionSize(0);
                column.setNullable(true);
                column.setPk(false);
                columns.add(column);
            }
        }
        respVO.setColumns(columns);
        return respVO;
    }

}

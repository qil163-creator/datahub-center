package cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 接口采集与 ETL 共用接口请求配置")
@Data
public class DataApiSaveReqVO {

    @Schema(description = "API 名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口基础信息接口")
    @NotEmpty(message = "API 名称不能为空")
    private String apiName;

    @Schema(description = "请求 URL", requiredMode = Schema.RequiredMode.REQUIRED, example = "https://open.example.gov.cn/api/population")
    @NotEmpty(message = "请求 URL 不能为空")
    private String requestUrl;

    @Schema(description = "请求方式：GET / POST", requiredMode = Schema.RequiredMode.REQUIRED, example = "GET")
    @NotEmpty(message = "请求方式不能为空")
    private String requestMethod;

    @Schema(description = "请求头")
    private List<RequestParamItem> requestHeader;

    @Schema(description = "Query 参数")
    private List<RequestParamItem> requestParam;

    @Schema(description = "请求体类型：none / form-data / urlencoded / raw", example = "none")
    private String bodyType;

    @Schema(description = "form-data 参数")
    private List<RequestParamItem> bodyFormData;

    @Schema(description = "x-www-form-urlencoded 参数")
    private List<RequestParamItem> bodyUrlencoded;

    @Schema(description = "raw 请求体")
    private String bodyRaw;

    @Schema(description = "返回数据类型：1-单对象 2-对象集合", example = "2")
    private Integer dataType;

    @Schema(description = "返回字段定义")
    private List<ResponseColumnItem> dataColumns;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "是否分页：0-否 1-是", example = "1")
    private Integer ifPage;

    @Schema(description = "页码参数名", example = "pageNo")
    private String pageKey;

    @Schema(description = "每页条数参数名", example = "pageSize")
    private String pageSizeKey;

    @Schema(description = "响应数据节点路径（如 data.list，空取整个响应）", example = "data.list")
    private String dataKey;

    /**
     * 判断是否满足请求配置有效条件。
     *
     * @return 判断结果
     */
    @AssertTrue(message = "接口配置不完整：请检查名称、HTTP 地址、请求方式、请求体和分页参数")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isRequestConfigurationValid() {
        if (apiName == null || apiName.isBlank() || requestUrl == null || requestUrl.isBlank()
                || requestMethod == null || !java.util.Set.of("GET", "POST").contains(requestMethod)
                || bodyType != null && !java.util.Set.of("none", "form-data", "urlencoded", "raw").contains(bodyType)
                || ifPage != null && ifPage != 0 && ifPage != 1
                || dataType != null && dataType != 1 && dataType != 2) {
            return false;
        }
        try {
            var uri = java.net.URI.create(requestUrl.trim());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                return false;
            }
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return !Integer.valueOf(1).equals(ifPage)
                || pageKey != null && !pageKey.isBlank() && pageSizeKey != null && !pageSizeKey.isBlank()
                && !pageKey.equals(pageSizeKey);
    }

}

import { requestClient } from '#/api/request';

export namespace DataforgeDataApiApi {
  /** API 请求参数项 */
  export interface RequestParamItem {
    name?: string;
    value?: string;
    description?: string;
  }

  /** API 返回字段定义 */
  export interface ResponseColumnItem {
    fieldName?: string;
    fieldType?: number; // JDBC Types：4-整数 8-小数 12-字符串
    displaySize?: number;
    precisionSize?: number;
    nullable?: boolean;
    pk?: boolean;
    description?: string;
    format?: string;
    defaultValue?: string;
  }

  /** 内嵌接口请求配置 */
  export interface DataApi {
    apiName?: string;
    requestUrl?: string;
    requestMethod?: string;
    requestHeader?: RequestParamItem[];
    requestParam?: RequestParamItem[];
    bodyType?: string; // none / form-data / urlencoded / raw
    bodyFormData?: RequestParamItem[];
    bodyUrlencoded?: RequestParamItem[];
    bodyRaw?: string;
    dataType?: number; // 1-单对象 2-对象集合
    dataColumns?: ResponseColumnItem[];
    description?: string;
    ifPage?: number;
    pageKey?: string;
    pageSizeKey?: string;
    dataKey?: string;
    /** 集成组件的多个受控JSON路径，旧dataKey保持兼容。 */
    dataKeys?: string[];
  }

  /** 取数结果 */
  export interface FetchResult {
    columns: ResponseColumnItem[];
    rows: Record<string, any>[];
  }
}

/** 按表单配置试调 API，返回原始响应文本 */
export function testDataApi(data: DataforgeDataApiApi.DataApi) {
  return requestClient.post<string>('/dataforge/data-api/test', data);
}

/** 按表单配置取数（解析行数据 + 推断字段） */
export function fetchDataApiData(data: DataforgeDataApiApi.DataApi) {
  return requestClient.post<DataforgeDataApiApi.FetchResult>(
    '/dataforge/data-api/fetch-data',
    data,
  );
}

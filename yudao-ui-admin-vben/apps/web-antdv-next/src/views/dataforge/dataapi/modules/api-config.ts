import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';

export interface ApiConfigValidationOptions {
  requireColumns?: boolean;
  requireName?: boolean;
}

/** 任务和节点各自持有完整的接口配置，避免共享可变的参数与字段。 */
export function defaultApiConfig(
  initial: DataforgeDataApiApi.DataApi = {},
): DataforgeDataApiApi.DataApi {
  return {
    apiName: '',
    requestUrl: '',
    requestMethod: 'GET',
    bodyType: 'none',
    bodyRaw: '',
    dataType: 2,
    description: '',
    ifPage: 0,
    pageKey: '',
    pageSizeKey: '',
    dataKey: '',
    ...initial,
    ...(initial.dataKeys ? { dataKeys: [...initial.dataKeys] } : {}),
    requestHeader: (initial.requestHeader ?? []).map((item) => ({ ...item })),
    requestParam: (initial.requestParam ?? []).map((item) => ({ ...item })),
    bodyFormData: (initial.bodyFormData ?? []).map((item) => ({ ...item })),
    bodyUrlencoded: (initial.bodyUrlencoded ?? []).map((item) => ({ ...item })),
    dataColumns: (initial.dataColumns ?? []).map((item) => ({ ...item })),
  };
}

/** 返回第一项校验提示；试调时允许尚未配置返回字段。 */
export function validateApiConfig(
  config: DataforgeDataApiApi.DataApi,
  options: ApiConfigValidationOptions = {},
): string | undefined {
  if (options.requireName && !config.apiName?.trim()) {
    return '请填写接口名称';
  }
  if (!config.requestUrl?.trim()) {
    return '请填写请求地址';
  }
  try {
    const url = new URL(config.requestUrl.trim());
    if (!['http:', 'https:'].includes(url.protocol)) {
      return '请求地址必须以 http:// 或 https:// 开头';
    }
    if (url.username || url.password) {
      return '请通过请求头配置认证信息，不要在请求地址中填写账号或密码';
    }
  } catch {
    return '请填写有效的 HTTP 请求地址';
  }
  if (!['GET', 'POST'].includes(config.requestMethod ?? '')) {
    return '请选择 GET 或 POST 请求方式';
  }
  if (config.ifPage === 1) {
    if (!config.pageKey?.trim() || !config.pageSizeKey?.trim()) {
      return '分页接口必须填写页码与每页条数参数名';
    }
    if (config.pageKey.trim() === config.pageSizeKey.trim()) {
      return '页码与每页条数参数名不能相同';
    }
  }
  const params = [
    ...(config.requestHeader ?? []),
    ...(config.requestParam ?? []),
  ];
  if (config.requestMethod === 'POST') {
    if (config.bodyType === 'form-data') {
      params.push(...(config.bodyFormData ?? []));
    } else if (config.bodyType === 'urlencoded') {
      params.push(...(config.bodyUrlencoded ?? []));
    } else if (config.bodyType === 'raw' && config.bodyRaw?.trim()) {
      try {
        JSON.parse(config.bodyRaw);
      } catch {
        return '请求体不是有效的 JSON，请检查格式';
      }
    }
  }
  if (params.some((item) => !item.name?.trim())) {
    return '请补全参数名，或删除空白参数行';
  }
  if (options.requireColumns !== false) {
    const columns = config.dataColumns ?? [];
    if (columns.length === 0) {
      return '请获取返回字段，或至少手工添加一个字段';
    }
    if (columns.some((column) => !column.fieldName?.trim())) {
      return '请补全返回字段名，或删除空白字段行';
    }
    const names = columns.map((column) => column.fieldName!.trim());
    if (new Set(names).size !== names.length) {
      return '返回字段名不能重复';
    }
  }
  return undefined;
}

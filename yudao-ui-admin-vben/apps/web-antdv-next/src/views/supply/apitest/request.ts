import type { SupplyApiTestApi } from '#/api/supply/apitest';

import { buildUUID } from '@vben/utils';

import { parseResourceId } from '#/utils/resource-id';

export type BodyType =
  | 'form-data'
  | 'json'
  | 'none'
  | 'text'
  | 'x-www-form-urlencoded'
  | 'xml';

export interface KeyValueItem {
  enabled: boolean;
  id: string;
  name: string;
  paramType?: string;
  value: string;
}

export interface RequestAuth {
  key: string;
  password: string;
  token: string;
  type: 'apikey' | 'basic' | 'bearer' | 'none';
  username: string;
  value: string;
}

export interface RequestDraft {
  apiId?: number | string;
  auth: RequestAuth;
  bodyType: BodyType;
  cookies: KeyValueItem[];
  followRedirects: boolean;
  formData: KeyValueItem[];
  headers: KeyValueItem[];
  id: string;
  method: string;
  mode: 'EXTERNAL' | 'REGISTERED';
  query: KeyValueItem[];
  rawBody: string;
  timeoutSeconds: number;
  title: string;
  url: string;
  urlencoded: KeyValueItem[];
}

export type RequestPair = SupplyApiTestApi.Pair;
export type SendRequest = SupplyApiTestApi.SendRequest;

const METHODS = new Set([
  'DELETE',
  'GET',
  'HEAD',
  'OPTIONS',
  'PATCH',
  'POST',
  'PUT',
]);
const CONTROL_HEADERS = new Set([
  'accept-encoding',
  'connection',
  'content-length',
  'data-permission',
  'expect',
  'forwarded',
  'host',
  'http_client_ip',
  'http_x_forwarded_for',
  'keep-alive',
  'login-user',
  'tag',
  'te',
  'tenant-id',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'visit-tenant-id',
  'wl-proxy-client-ip',
  'x-real-ip',
]);
const HEADER_NAME = /^[!#$%&'*+.^_`|~\dA-Za-z-]+$/;

export function createRow(
  name = '',
  value = '',
  paramType?: string,
): KeyValueItem {
  return {
    id: buildUUID(),
    enabled: true,
    name,
    value,
    ...(paramType === undefined ? {} : { paramType }),
  };
}

export function createDraft(
  overrides: Partial<RequestDraft> = {},
): RequestDraft {
  return {
    id: buildUUID(),
    title: '新请求',
    mode: 'EXTERNAL',
    method: 'GET',
    url: '',
    query: [createRow()],
    formData: [createRow()],
    urlencoded: [createRow()],
    headers: [createRow()],
    cookies: [createRow()],
    bodyType: 'none',
    rawBody: '',
    auth: {
      type: 'none',
      token: '',
      username: '',
      password: '',
      key: 'X-Api-Key',
      value: '',
    },
    timeoutSeconds: 60,
    followRedirects: false,
    ...overrides,
  };
}

function pairs(rows: KeyValueItem[], label: string): RequestPair[] {
  return rows.flatMap((row) => {
    if (!row.enabled) return [];
    if (typeof row.name !== 'string' || typeof row.value !== 'string') {
      throw new TypeError(`${label}名称和值必须为字符串`);
    }
    const name = row.name.trim();
    if (!name && !row.value) return [];
    if (!name) throw new Error(`请填写${label}名称`);
    return [{ name, value: row.value }];
  });
}

function validateHeaders(headers: RequestPair[]) {
  for (const { name, value } of headers) {
    if (!HEADER_NAME.test(name)) throw new Error('请求头名称格式无效');
    const normalized = name.toLowerCase();
    if (
      CONTROL_HEADERS.has(normalized) ||
      normalized.startsWith('x-forwarded-') ||
      normalized.startsWith('proxy-') ||
      normalized.startsWith('data-permission-')
    ) {
      throw new Error('不能设置平台内部身份或 HTTP 控制请求头');
    }
    if (
      [...value].some((character) => {
        const point = character.codePointAt(0)!;
        return (point < 32 && point !== 9) || point >= 127;
      })
    ) {
      throw new Error('请求头值不能包含换行、控制字符或非 ASCII 字符');
    }
  }
}

function setHeader(headers: RequestPair[], name: string, value: string) {
  const normalized = name.toLowerCase();
  const matching = headers.findIndex(
    (header) => header.name.toLowerCase() === normalized,
  );
  if (matching === -1) {
    headers.push({ name, value });
    return;
  }
  for (let index = headers.length - 1; index > matching; index--) {
    if (headers[index]?.name.toLowerCase() === normalized) {
      headers.splice(index, 1);
    }
  }
  headers[matching] = { name, value };
}

function defaultContentType(headers: RequestPair[], value: string) {
  if (!headers.some((header) => header.name.toLowerCase() === 'content-type')) {
    headers.push({ name: 'Content-Type', value });
  }
}

function applyAuth(headers: RequestPair[], auth: RequestAuth) {
  switch (auth.type) {
    case 'apikey': {
      const name = auth.key.trim();
      if (!name || !auth.value) throw new Error('请填写 API Key 名称和值');
      validateHeaders([{ name, value: auth.value }]);
      setHeader(headers, name, auth.value);
      break;
    }
    case 'basic': {
      if (!auth.username) throw new Error('请填写 Basic Auth 用户名');
      if (auth.username.includes(':')) {
        throw new Error('Basic Auth 用户名不能包含冒号');
      }
      const bytes = new TextEncoder().encode(
        `${auth.username}:${auth.password}`,
      );
      let binary = '';
      for (const byte of bytes) binary += String.fromCodePoint(byte);
      setHeader(headers, 'Authorization', `Basic ${btoa(binary)}`);
      break;
    }
    case 'bearer': {
      if (!auth.token.trim()) throw new Error('请填写 Bearer Token');
      if (/\s/.test(auth.token)) {
        throw new Error('Bearer Token 不能包含空白字符');
      }
      setHeader(headers, 'Authorization', `Bearer ${auth.token}`);
      break;
    }
    case 'none': {
      break;
    }
    default: {
      throw new Error('认证方式无效');
    }
  }
}

function applyCookies(headers: RequestPair[], rows: KeyValueItem[]) {
  const cookies = pairs(rows, 'Cookie');
  for (const cookie of cookies) {
    if (!HEADER_NAME.test(cookie.name) || /[;,]/.test(cookie.value)) {
      throw new Error('Cookie 名称或值格式无效');
    }
  }
  if (cookies.length > 0) {
    setHeader(
      headers,
      'Cookie',
      cookies.map(({ name, value }) => `${name}=${value}`).join('; '),
    );
  }
}

function validateJson(source: string) {
  try {
    JSON.parse(source);
  } catch {
    throw new Error('JSON 内容格式无效，请检查后再发送');
  }
}

function validateXml(source: string) {
  if (/<!\s*(?:DOCTYPE|ENTITY)\b/i.test(source)) {
    throw new Error('XML 请求体不支持 DTD 或实体声明');
  }
  const document = new DOMParser().parseFromString(source, 'application/xml');
  if (
    !document.documentElement ||
    document.querySelector('parsererror') !== null
  ) {
    throw new Error('XML 内容格式无效，请检查后再发送');
  }
}

function buildBody(
  draft: RequestDraft,
  headers: RequestPair[],
): string | undefined {
  switch (draft.bodyType) {
    case 'form-data': {
      const fields = pairs(draft.formData, '表单字段');
      if (
        fields.some(({ name }) =>
          [...name].some((character) => {
            const point = character.codePointAt(0)!;
            return point < 32 || point === 127;
          }),
        )
      ) {
        throw new Error('表单字段名称不能包含换行或控制字符');
      }
      let boundary: string;
      do {
        boundary = `----DataHub${buildUUID()}`;
      } while (fields.some(({ value }) => value.includes(boundary)));
      setHeader(
        headers,
        'Content-Type',
        `multipart/form-data; boundary=${boundary}`,
      );
      const body = fields
        .map(({ name, value }) => {
          const escaped = name.replaceAll(/[\\"]/g, String.raw`\$&`);
          return `--${boundary}\r\nContent-Disposition: form-data; name="${escaped}"\r\n\r\n${value}\r\n`;
        })
        .join('');
      return `${body}--${boundary}--\r\n`;
    }
    case 'json': {
      validateJson(draft.rawBody);
      defaultContentType(headers, 'application/json');
      return draft.rawBody;
    }
    case 'none': {
      return undefined;
    }
    case 'text': {
      defaultContentType(headers, 'text/plain; charset=UTF-8');
      return draft.rawBody;
    }
    case 'x-www-form-urlencoded': {
      defaultContentType(headers, 'application/x-www-form-urlencoded');
      const body = new URLSearchParams();
      for (const { name, value } of pairs(draft.urlencoded, '表单字段')) {
        body.append(name, value);
      }
      return body.toString();
    }
    case 'xml': {
      validateXml(draft.rawBody);
      defaultContentType(headers, 'application/xml');
      return draft.rawBody;
    }
    default: {
      throw new Error('请求体类型无效');
    }
  }
}

function externalUrl(source: string): string {
  const value = source.trim();
  if (!value) throw new Error('请填写请求地址');
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error('请输入完整的 HTTP 或 HTTPS 地址');
  }
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    !url.hostname ||
    url.username ||
    url.password ||
    url.hash ||
    /^https?:\/\/[^/?#]*@/i.test(value) ||
    value.includes('#') ||
    value.includes('\\') ||
    [...value].some((character) => {
      const point = character.codePointAt(0)!;
      return point <= 32 || point === 127;
    }) ||
    !/^https?:\/\//i.test(value)
  ) {
    throw new Error('请求地址仅支持 HTTP/HTTPS，不能包含账号密码或片段');
  }
  return value;
}

export function buildSendRequest(draft: RequestDraft): SendRequest {
  const method = draft.method.trim().toUpperCase();
  if (!METHODS.has(method)) throw new Error('请求方式无效');
  if (
    !Number.isInteger(draft.timeoutSeconds) ||
    draft.timeoutSeconds < 1 ||
    draft.timeoutSeconds > 120
  ) {
    throw new Error('超时时间必须为 1 到 120 秒的整数');
  }
  const query = pairs(draft.query, '参数');
  const headers = pairs(draft.headers, '请求头');
  validateHeaders(headers);
  applyAuth(headers, draft.auth);
  applyCookies(headers, draft.cookies);
  const body = buildBody(draft, headers);
  validateHeaders(headers);
  if ((method === 'GET' || method === 'HEAD') && body) {
    throw new Error(`${method} 请求不能携带请求体，请切换请求方式或清空 Body`);
  }
  const request: SendRequest = {
    mode: draft.mode,
    method,
    query,
    headers,
    ...(body === undefined ? {} : { body }),
    timeoutSeconds: draft.timeoutSeconds,
    followRedirects: draft.followRedirects,
  };
  if (draft.mode === 'REGISTERED') {
    const apiId = parseResourceId(draft.apiId);
    if (!apiId) throw new Error('请选择有效的已注册接口');
    request.apiId = apiId;
  } else if (draft.mode === 'EXTERNAL') {
    request.url = externalUrl(draft.url);
  } else {
    throw new Error('请求模式无效');
  }
  return request;
}

/** 只重新排布 JSON 词素，不把整数、小数或指数重新序列化。 */
export function formatJson(source: string): string {
  const maxBytes = 4 * 1024 * 1024;
  if (source.length > maxBytes) throw new Error('JSON 内容过大，保留原始内容');
  const tokens: string[] = [];
  let depth = 0;
  for (const match of source.matchAll(
    /"(?:\\.|[^"\\])*"|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null|[{}[\],:]/g,
  )) {
    const token = match[0];
    tokens.push(token);
    if (tokens.length > 100_000) throw new Error('JSON 项目过多，保留原始内容');
    if (token === '{' || token === '[') {
      depth++;
      if (depth > 128) throw new Error('JSON 层级过深，保留原始内容');
    } else if (token === '}' || token === ']') {
      depth--;
    }
  }
  validateJson(source);
  depth = 0;
  const output: string[] = [];
  let outputBytes = 0;
  const encoder = new TextEncoder();
  const append = (value: string) => {
    outputBytes += encoder.encode(value).byteLength;
    if (outputBytes > maxBytes) {
      throw new Error('JSON 格式化结果过大，保留原始内容');
    }
    output.push(value);
  };
  const newline = () => `\n${'  '.repeat(depth)}`;
  tokens.forEach((token, index) => {
    if (token === '{' || token === '[') {
      append(token);
      depth++;
      if (tokens[index + 1] !== '}' && tokens[index + 1] !== ']') {
        append(newline());
      }
    } else if (token === '}' || token === ']') {
      depth--;
      if (tokens[index - 1] !== '{' && tokens[index - 1] !== '[') {
        append(newline());
      }
      append(token);
    } else if (token === ',') {
      append(`,${newline()}`);
    } else if (token === ':') {
      append(': ');
    } else {
      append(token);
    }
  });
  return output.join('');
}

<script lang="ts" setup>
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { computed, h, reactive, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Drawer, Input, message, Table, Tag } from 'antdv-next';

import {
  getDataApi,
  getDataApiPublicBaseUrl,
  signDataApi,
} from '#/api/supply/dataapi';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

import { buildQueryString } from '../utils/query';
import {
  API_TYPE_OPTIONS,
  AUTH_TYPE_OPTIONS,
  optionLabel,
  PARAM_TYPE_OPTIONS,
} from './data';

defineOptions({ name: 'SupplyDataApiDoc', inheritAttrs: false });

const visible = ref(false);
const api = ref<SupplyDataApiApi.DataApi>({});

const isQuery = computed(
  () => !api.value.apiType || api.value.apiType === 'QUERY',
);
const needsOperation = computed(
  () =>
    !isQuery.value ||
    (api.value.configType === 'FORWARD' &&
      (api.value.forwardMethod || api.value.method)?.toUpperCase() !== 'GET'),
);
const isPage = computed(
  () => isQuery.value && api.value.resultFormat === 'PAGE',
);
const isEtlBatch = computed(() => api.value.configType === 'ETL_BATCH');

/** 网关相对路径 */
const gatewayPath = computed(
  () =>
    `/supply/gateway/services/${api.value.version ?? ''}${api.value.path ?? ''}`,
);

const publicBaseUrl = ref('');

/** 完整调用地址（仅使用平台配置的对外基址，未配置时不猜测） */
const fullUrl = computed(() => {
  const base = publicBaseUrl.value.replace(/\/$/, '');
  if (!base) {
    return `（请配置 yudao.supply.data-api.public-base-url）${gatewayPath.value}`;
  }
  return `${base}${gatewayPath.value}`;
});

const serviceTypeLabel = computed(() => {
  const labels: Record<string, string> = {
    DELETE: '删除数据',
    INSERT: '插入数据',
    MULTI: '多脚本（事务）',
    QUERY: '查询数据',
    UPDATE: '更新数据',
  };
  return labels[api.value.apiType ?? 'QUERY'] ?? '查询数据';
});

async function open(id: number | string) {
  api.value = await getDataApi(id);
  try {
    publicBaseUrl.value = (await getDataApiPublicBaseUrl()) || '';
  } catch {
    publicBaseUrl.value = '';
  }
  signForm.appKey = '';
  signForm.appSecret = '';
  signForm.timestamp = '';
  signForm.nonce = '';
  signForm.contentType = api.value.method === 'GET' ? '' : 'application/json';
  signForm.queryString = '';
  signForm.body = '';
  signResult.value = '';
  signVisible.value = false;
  visible.value = true;
}

defineExpose({ open });

/** ==================== 请求头 ==================== */

const headerRows = computed(() => {
  const rows: { key: string; value: string }[] = [];
  if (api.value.method !== 'GET') {
    rows.push({ key: 'Content-Type', value: 'application/json' });
  }
  if (api.value.authType === 'APPKEY' || api.value.authType === 'SIGN') {
    rows.push({ key: 'X-AppKey', value: '在【应用管理】中获取' });
  }
  if (api.value.authType === 'SIGN') {
    rows.push(
      { key: 'X-Timestamp', value: '毫秒时间戳（有效期 10 分钟）' },
      { key: 'X-Nonce', value: '随机数（覆盖整个 10 分钟时间窗，不可重复）' },
      { key: 'X-Sign', value: 'HMAC-SM3(AppSecret, 签名原文) 的小写十六进制' },
    );
  }
  return rows;
});

const headerColumns = [
  { title: 'Key', dataIndex: 'key', key: 'key', width: 240 },
  { title: 'Value', dataIndex: 'value', key: 'value' },
];

/** ==================== 请求体 ==================== */

interface DocRow {
  children?: DocRow[];
  key: string;
  label: string;
  note: string;
  param: string;
  required?: string;
  type: string;
}

const requestRows = computed<DocRow[]>(() => {
  const rows: DocRow[] = [];
  if (isQuery.value && api.value.configType === 'TABLE') {
    rows.push(
      {
        key: 'consumeKey',
        param: '_consumeKey',
        label: '消费请求键',
        type: 'String',
        required: '有限额授权时必填',
        note: '8～128位字母、数字、点、冒号、下划线或短横线。新查询用新键，网络重试沿用；SIGN调用必须把它包含在签名覆盖的query/body中。门户和接口共用批准额度。',
      },
      {
        key: 'consumeMode',
        param: '_consumeMode',
        label: '消费查询模式',
        type: 'String',
        required: '否',
        note: 'STATUS查询原请求回执，不重新读取数据或扣费。COMMITTED已计量，RESERVED尚在执行，CANCELLED/EXPIRED已释放；状态查询不返回原数据。',
      },
    );
  }
  if (needsOperation.value) {
    rows.push(
      {
        key: 'operationKey',
        param: '_operationKey',
        label: '业务操作编号',
        type: 'String',
        required: '是',
        note: '8～128位字母、数字、点、冒号、下划线或短横线；同一业务重试保持不变。须包含在签名覆盖的query/body中。',
      },
      {
        key: 'operationMode',
        param: '_operationMode',
        label: '操作模式',
        type: 'String',
        required: '否',
        note: '默认EXECUTE；STATUS只查询同一编号的结果。每次请求更新nonce和签名；UNKNOWN时先核查目标库，禁止换编号盲目重试。',
      },
    );
  }
  if (isPage.value) {
    rows.push(
      {
        key: 'pageNo',
        label: '当前页',
        note: '默认 1',
        param: 'pageNo',
        required: '否',
        type: 'int',
      },
      {
        key: 'pageSize',
        label: '每页条数',
        note: '默认 10，最大 1000',
        param: 'pageSize',
        required: '否',
        type: 'int',
      },
    );
  }
  if (isEtlBatch.value) {
    rows.push(
      {
        key: 'afterRow',
        param: 'afterRow',
        label: '批次游标',
        type: 'String',
        required: '否',
        note: '首批为 "0"；下一批原样传回 nextAfterRow 十进制字符串。不支持其他筛选参数。',
      },
      {
        key: 'pageSize',
        param: 'pageSize',
        label: '本批最多行数',
        type: 'int',
        required: '否',
        note: '1 至 200。每次读取复核批次准入、归档、当前权限和脱敏；不返回总数。',
      },
    );
    return rows;
  }
  (api.value.requestParams ?? []).forEach((param, index) => {
    const notes: string[] = [];
    if (param.defaultValue) {
      notes.push(`默认 ${param.defaultValue}`);
    }
    if (param.exampleValue) {
      notes.push(`示例 ${param.exampleValue}`);
    }
    rows.push({
      key: `param-${index}`,
      label: param.description ?? '',
      note: notes.join('；') || '-',
      param: param.name ?? '',
      required: param.nullable === 1 ? '否' : '是',
      type: optionLabel(PARAM_TYPE_OPTIONS, param.paramType),
    });
  });
  return rows;
});

const requestColumns = [
  { title: '参数', dataIndex: 'param', key: 'param', width: 180 },
  { title: '名称', dataIndex: 'label', key: 'label', width: 170 },
  { title: '参数类型', dataIndex: 'type', key: 'type', width: 110 },
  { title: '必填', dataIndex: 'required', key: 'required', width: 70 },
  {
    title: '说明',
    dataIndex: 'note',
    key: 'note',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
];

/** ==================== 响应体（树形） ==================== */

const responseRows = computed<DocRow[]>(() => {
  let fieldChildren: DocRow[] = (api.value.responseFields ?? [])
    .filter((field) => field.model !== 'HIDE')
    .map((field, index) => ({
      key: `field-${index}`,
      label: field.cnName || field.description || '',
      note: field.description || field.cnName || '-',
      param: field.outputName || field.name || '',
      type: field.dataType || 'String',
    }));
  if (!isQuery.value) {
    fieldChildren = [
      {
        key: 'affectedRows',
        label: '影响行数',
        note: '本次执行影响的数据行数',
        param: 'affectedRows',
        type: 'String（十进制整数）',
      },
    ];
    if (api.value.apiType === 'MULTI') {
      fieldChildren.unshift({
        key: 'statement',
        label: '语句序号',
        note: '多脚本中第几条语句',
        param: 'statement',
        type: 'String（十进制整数）',
      });
    }
  }
  const dataChildren: DocRow[] = [
    {
      children: fieldChildren.length > 0 ? fieldChildren : undefined,
      key: 'rows',
      label: '数据记录',
      note: '数据记录列表',
      param: 'rows',
      type: 'Array',
    },
  ];
  if (isEtlBatch.value)
    dataChildren.push(
      {
        key: 'nextAfterRow',
        param: 'nextAfterRow',
        label: '下次游标',
        type: 'String | long',
        note: '保持十进制表示，不转换为 JavaScript Number；无总条数。',
      },
      {
        key: 'hasMore',
        param: 'hasMore',
        label: '还有后续数据',
        type: 'boolean',
        note: '为 true 时使用 nextAfterRow 继续读取。',
      },
    );
  if (isQuery.value && api.value.configType === 'TABLE') {
    dataChildren.push(
      {
        key: 'consumptionKey',
        param: 'consumptionKey',
        label: '消费请求键',
        type: 'String',
        note: '有限额授权时返回；与状态查询使用的_consumeKey一致。',
      },
      {
        key: 'consumptionState',
        param: 'consumptionState',
        label: '消费回执',
        type: 'String',
        note: '正常返回为COMMITTED；额度按成功请求或实际返回行数累计。余额不足时拒绝执行，行额度不足整页时缩小本次行数。',
      },
      {
        key: 'consumptionRows',
        param: 'consumptionRows',
        label: '本次返回行数',
        type: 'int',
        note: '行数计量以此确认；次数计量下空结果也算一次成功请求。',
      },
    );
  }
  if (needsOperation.value) {
    dataChildren.push(
      {
        key: 'operationKey',
        param: 'operationKey',
        label: '业务操作编号',
        type: 'String',
        note: '重试、结果查询使用相同编号',
      },
      {
        key: 'operationStatus',
        param: 'operationStatus',
        label: '操作状态',
        type: 'String',
        note: 'SUCCEEDED才表示已确认写入；RUNNING需查询；UNKNOWN需核查目标库。code=0不代表写入完成。',
      },
      {
        key: 'operationMessage',
        param: 'operationMessage',
        label: '处置说明',
        type: 'String',
        note: '提示查询或人工核查；HTTP转发重试仅返回状态，不保存或重放上游响应。禁止未知时换编号盲目重试',
      },
    );
  }
  if (isPage.value) {
    dataChildren.push({
      key: 'total',
      label: '总条数',
      note: '总条数',
      param: 'total',
      type: 'long',
    });
  }
  dataChildren.push({
    key: 'durationMs',
    label: '执行耗时',
    note: '单位毫秒',
    param: 'durationMs',
    type: 'long',
  });
  return [
    {
      key: 'code',
      label: '响应码',
      note: '成功 0，其他见提示信息',
      param: 'code',
      type: 'int',
    },
    {
      key: 'msg',
      label: '消息',
      note: '提示信息',
      param: 'msg',
      type: 'String',
    },
    {
      children: dataChildren,
      key: 'data',
      label: '业务数据',
      note: '业务数据',
      param: 'data',
      type: 'Object',
    },
  ];
});

const responseColumns = [
  { title: '参数', dataIndex: 'param', key: 'param', width: 220 },
  { title: '名称', dataIndex: 'label', key: 'label', width: 170 },
  { title: '参数类型', dataIndex: 'type', key: 'type', width: 110 },
  {
    title: '说明',
    dataIndex: 'note',
    key: 'note',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
];

/** ==================== 示例 ==================== */

function exampleValueOf(param: SupplyDataApiApi.ParamItem) {
  return param.exampleValue || param.defaultValue || '值';
}

const requestExample = computed(() => {
  const example: Record<string, unknown> = {};
  if (needsOperation.value) {
    example._operationKey = 'permit-case-20261003-001';
    example._operationMode = 'EXECUTE';
  }
  if (isPage.value) {
    example.pageNo = 1;
    example.pageSize = 10;
  }
  if (isEtlBatch.value) {
    example.afterRow = '0';
    example.pageSize = 10;
  }
  (isEtlBatch.value ? [] : (api.value.requestParams ?? [])).forEach((param) => {
    if (param.name) {
      example[param.name] = exampleValueOf(param);
    }
  });
  if (api.value.method === 'GET') {
    const query = buildQueryString(example);
    return `GET ${fullUrl.value}${query ? `?${query}` : ''}`;
  }
  return JSON.stringify(example, null, 2);
});

const responseExample = computed(() => {
  let row: Record<string, unknown> = {};
  if (isQuery.value) {
    (api.value.responseFields ?? [])
      .filter((field) => field.model !== 'HIDE')
      .forEach((field) => {
        row[field.outputName || field.name || ''] = field.exampleValue || '';
      });
    if (Object.keys(row).length === 0) {
      row = { 列名: '值' };
    }
  } else {
    row =
      api.value.apiType === 'MULTI'
        ? { affectedRows: '1', statement: '1' }
        : { affectedRows: '1' };
  }
  const data: Record<string, unknown> = { rows: [row] };
  if (needsOperation.value) {
    data.operationKey = 'permit-case-20261003-001';
    data.operationStatus = 'SUCCEEDED';
    data.replayed = false;
  }
  if (isPage.value) {
    data.total = 1;
  }
  if (isEtlBatch.value) {
    data.nextAfterRow = '9007199254740993';
    data.hasMore = true;
  }
  data.durationMs = 12;
  return JSON.stringify({ code: 0, data, msg: '' }, null, 2);
});

const errorExample = JSON.stringify(
  { code: 1_500_040_006, data: null, msg: 'API 服务执行失败：异常信息' },
  null,
  2,
);

/** ==================== 状态码 ==================== */

const statusRows = [
  { code: '0', description: '请求成功', solution: '-' },
  {
    code: '1500040003',
    description: 'API 服务未上线',
    solution: '在 API 管理中上线该服务',
  },
  {
    code: '1500040006',
    description: 'API 服务执行失败',
    solution: '查看 msg 中的异常信息',
  },
  {
    code: '1500040007',
    description: '调用方 IP 不在名单内',
    solution: '检查 IP 黑白名单配置',
  },
  {
    code: '1500040008',
    description: '请求过于频繁，已触发限流',
    solution: '稍后重试或调整限流配置',
  },
  {
    code: '1500040009',
    description: '请求参数不能为空',
    solution: '按文档补全必填参数',
  },
  {
    code: '1500040011',
    description: '接口已熔断',
    solution: '等待熔断时长结束后重试',
  },
  {
    code: '1500040012',
    description: '接口认证失败',
    solution: '检查认证请求头与签名规则',
  },
  {
    code: '1500040013',
    description: '应用未获得有效授权',
    solution: '在应用管理中授权该 API',
  },
  {
    code: '1500040017',
    description: '该应用不允许此签名协议',
    solution: '按应用签名协议使用 v1 或 v2',
  },
  {
    code: '1500040021',
    description: '授权合同与当前发布不一致',
    solution: '重新授权或回滚到批准时的快照',
  },
  {
    code: '1500042005',
    description: '应用已暂停或撤销',
    solution: '在应用管理中重新启用该应用',
  },
];

const statusColumns = [
  { title: '状态码', dataIndex: 'code', key: 'code', width: 140 },
  {
    title: '状态描述',
    dataIndex: 'description',
    key: 'description',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '解决方案', dataIndex: 'solution', key: 'solution' },
];

/** ==================== 复制 / 导出 ==================== */

async function handleCopyUrl() {
  await navigator.clipboard.writeText(fullUrl.value);
  message.success('接口地址已复制');
}

function escapeHtml(text: string) {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

/** 生成打印用独立 HTML（浏览器打印对话框中选择"另存为 PDF"） */
function buildPrintHtml() {
  const tableOf = (
    headers: string[],
    rows: string[][],
  ) => `<table><thead><tr>${headers.map((h) => `<th>${escapeHtml(h)}</th>`).join('')}</tr></thead>
<tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${escapeHtml(cell)}</td>`).join('')}</tr>`).join('')}</tbody></table>`;

  const flattenResponse = (rows: DocRow[], depth = 0): string[][] => {
    const result: string[][] = [];
    rows.forEach((row) => {
      result.push([
        `${'　'.repeat(depth)}${row.param}`,
        row.label,
        row.type,
        row.note,
      ]);
      if (row.children) {
        result.push(...flattenResponse(row.children, depth + 1));
      }
    });
    return result;
  };

  const sections: string[] = [];
  sections.push(
    `<h1>${escapeHtml(api.value.name ?? '')}【接口文档】</h1>`,
    `<p class="meta">服务名称：${escapeHtml(api.value.name ?? '-')}</p>`,
    `<p class="meta">服务描述：${escapeHtml(api.value.description ?? '-')}</p>`,
    `<p class="meta">服务类型：${escapeHtml(serviceTypeLabel.value)}</p>`,
    `<p class="meta">认证方式：${escapeHtml(optionLabel(AUTH_TYPE_OPTIONS, api.value.authType ?? 'NONE'))}</p>`,
    `<p class="meta">接口地址：${escapeHtml(fullUrl.value)}</p>`,
    `<p class="meta">请求方式：${escapeHtml(api.value.method ?? 'POST')}</p>`,
  );
  if (headerRows.value.length > 0) {
    sections.push(
      '<h2>请求头</h2>',
      tableOf(
        ['Key', 'Value'],
        headerRows.value.map((row) => [row.key, row.value]),
      ),
    );
    if (api.value.authType === 'SIGN') {
      sections.push(
        '<p>新接入使用 v2：HMAC-SM3 覆盖请求方法、路径、规范化查询串、原始请求体哈希、AppKey、时间戳、Nonce、Content-Type 和凭证版本。历史 v1 客户端仅在应用允许时继续使用，不要把 v2 签名按 v1 重试。</p>',
      );
    }
  }
  sections.push(
    `<h2>${api.value.method === 'GET' ? '请求参数' : '请求体'}</h2>`,
    tableOf(
      ['参数', '名称', '参数类型', '必填', '说明'],
      requestRows.value.map((row) => [
        row.param,
        row.label,
        row.type,
        row.required ?? '',
        row.note,
      ]),
    ),
    '<h2>响应体</h2>',
    tableOf(
      ['参数', '名称', '参数类型', '说明'],
      flattenResponse(responseRows.value),
    ),
    '<h2>请求示例</h2>',
    `<pre>${escapeHtml(requestExample.value)}</pre>`,
    '<h2>响应示例</h2>',
    `<pre>${escapeHtml(responseExample.value)}</pre>`,
    '<h2>异常示例</h2>',
    `<pre>${escapeHtml(errorExample)}</pre>`,
    '<h2>状态码</h2>',
    tableOf(
      ['状态码', '状态描述', '解决方案'],
      statusRows.map((row) => [row.code, row.description, row.solution]),
    ),
  );

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<title>${escapeHtml(api.value.name ?? 'API')}【接口文档】</title>
<style>
  body { max-width: 860px; margin: 24px auto; font: 13px/1.7 "Microsoft YaHei", sans-serif; color: #262626; }
  h1 { font-size: 20px; margin-bottom: 16px; }
  h2 { font-size: 14px; margin: 20px 0 8px; padding-left: 8px; border-left: 3px solid #1677ff; }
  .meta { margin: 4px 0; color: #595959; }
  table { width: 100%; border-collapse: collapse; margin: 6px 0; }
  th, td { padding: 6px 10px; border: 1px solid #e8e8e8; text-align: left; word-break: break-all; }
  th { background: #fafafa; font-weight: 600; }
  pre { padding: 12px; background: #1f1f1f; color: #9cdcfe; border-radius: 6px;
        font: 12px/1.6 Consolas, monospace; white-space: pre-wrap; word-break: break-all; }
  @media print { pre { background: #f5f5f5; color: #262626; border: 1px solid #e8e8e8; } }
</style>
</head>
<body>${sections.join('\n')}</body>
</html>`;
}

function handleDownloadPdf() {
  const win = window.open('', '_blank');
  if (!win) {
    message.warning('浏览器拦截了新窗口，请允许弹出窗口后重试');
    return;
  }
  win.document.write(buildPrintHtml());
  win.document.close();
  win.focus();
  setTimeout(() => win.print(), 300);
}

function handleExportMarkdown() {
  const lines: string[] = [];
  const push = (text = '') => lines.push(text);
  push(`# ${api.value.name ?? ''}【接口文档】`);
  push();
  push(`- 服务名称：${api.value.name ?? '-'}`);
  push(`- 服务描述：${api.value.description ?? '-'}`);
  push(`- 服务类型：${serviceTypeLabel.value}`);
  push(
    `- 认证方式：${optionLabel(AUTH_TYPE_OPTIONS, api.value.authType ?? 'NONE')}`,
  );
  push(`- 接口地址：${api.value.method ?? 'POST'} ${fullUrl.value}`);
  push();
  if (headerRows.value.length > 0) {
    push(`## 请求头`);
    push();
    push(`| Key | Value |`);
    push(`| --- | --- |`);
    headerRows.value.forEach((row) => push(`| ${row.key} | ${row.value} |`));
    push();
    if (api.value.authType === 'SIGN') {
      push(
        '> 签名协议：HMAC-SM3（平台唯一协议）。签名原文依次为 HMAC-SM3、请求方法、API_PATH、规范化查询串（按发送顺序）、原始请求体 SM3 摘要、AppKey、毫秒时间戳、Nonce、Content-Type，每个字段写成"长度:值"并以换行结尾。查询串与请求体必须和实际发送的请求一致，且二者不得出现同名参数。',
      );
      push();
    }
  }
  push(`## ${api.value.method === 'GET' ? '请求参数' : '请求体'}`);
  push();
  push(`| 参数 | 名称 | 参数类型 | 必填 | 说明 |`);
  push(`| --- | --- | --- | --- | --- |`);
  requestRows.value.forEach((row) => {
    push(
      `| ${row.param} | ${row.label} | ${row.type} | ${row.required} | ${row.note} |`,
    );
  });
  push();
  push(`## 响应体`);
  push();
  push(`| 参数 | 名称 | 参数类型 | 说明 |`);
  push(`| --- | --- | --- | --- |`);
  const walk = (rows: DocRow[], depth: number) => {
    rows.forEach((row) => {
      push(
        `| ${'&nbsp;&nbsp;'.repeat(depth)}${row.param} | ${row.label} | ${row.type} | ${row.note} |`,
      );
      if (row.children) {
        walk(row.children, depth + 1);
      }
    });
  };
  walk(responseRows.value, 0);
  push();
  push(`## 请求示例`);
  push();
  push('```json');
  push(requestExample.value);
  push('```');
  push();
  push(`## 响应示例`);
  push();
  push('```json');
  push(responseExample.value);
  push('```');
  push();
  push(`## 异常示例`);
  push();
  push('```json');
  push(errorExample);
  push('```');
  push();
  push(`## 状态码`);
  push();
  push(`| 状态码 | 状态描述 | 解决方案 |`);
  push(`| --- | --- | --- |`);
  statusRows.forEach((row) =>
    push(`| ${row.code} | ${row.description} | ${row.solution} |`),
  );
  const blob = new Blob([lines.join('\n')], {
    type: 'text/markdown;charset=utf-8',
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${api.value.name ?? 'API'}-接口文档.md`;
  link.click();
  URL.revokeObjectURL(url);
  message.success('接口文档已导出');
}

/** ==================== 在线签名工具 ==================== */

const signVisible = ref(false);
const signing = ref(false);
const signForm = reactive({
  appKey: '',
  appSecret: '',
  body: '',
  contentType: 'application/json',
  nonce: '',
  queryString: '',
  timestamp: '',
});
const signResult = ref('');

function fillTimestamp() {
  signForm.timestamp = String(Date.now());
}

function fillNonce() {
  if (!globalThis.crypto?.getRandomValues) {
    message.error('当前环境不支持安全随机数，请使用 HTTPS 或 localhost');
    return;
  }
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  signForm.nonce = Array.from(bytes, (value) =>
    value.toString(16).padStart(2, '0'),
  ).join('');
}

async function handleSign() {
  if (!signForm.appKey || !signForm.appSecret) {
    message.warning('请输入 AppKey 与 AppSecret');
    return;
  }
  if (!signForm.timestamp || !signForm.nonce) {
    message.warning('请生成时间戳与随机数');
    return;
  }
  signing.value = true;
  try {
    signResult.value = await signDataApi({
      apiPath: gatewayPath.value,
      appKey: signForm.appKey,
      appSecret: signForm.appSecret,
      body: signForm.body,
      contentType: signForm.contentType,
      method: api.value.method ?? 'POST',
      nonce: signForm.nonce,
      queryString: signForm.queryString,
      timestamp: signForm.timestamp,
    });
    message.success('签名生成成功');
  } finally {
    signing.value = false;
  }
}

async function handleCopySign() {
  if (!signResult.value) {
    return;
  }
  await navigator.clipboard.writeText(signResult.value);
  message.success('签名已复制');
}
</script>

<template>
  <Drawer
    :closable="true"
    :footer="null"
    :open="visible"
    :push="false"
    :title="`接口文档 - ${api.name ?? ''}`"
    destroy-on-hidden
    placement="bottom"
    size="100%"
    @close="visible = false"
  >
    <div class="doc-page">
      <!-- 右上角操作 -->
      <div class="doc-page__actions">
        <Button
          v-if="api.authType === 'SIGN'"
          size="small"
          @click="signVisible = !signVisible"
        >
          <template #icon><IconifyIcon icon="lucide:key-round" /></template>
          在线签名
        </Button>
        <Button size="small" @click="handleExportMarkdown">
          <template #icon><IconifyIcon icon="lucide:file-down" /></template>
          导出 Markdown
        </Button>
        <Button size="small" type="primary" @click="handleDownloadPdf">
          <template #icon><IconifyIcon icon="lucide:download" /></template>
          下载 PDF
        </Button>
      </div>

      <div class="doc-card">
        <h1 class="doc-card__title">{{ api.name }}【接口文档】</h1>

        <!-- 服务信息 -->
        <div class="doc-meta">
          <div class="doc-meta__row">服务名称：{{ api.name || '-' }}</div>
          <div class="doc-meta__row">
            服务描述：{{ api.description || '-' }}
          </div>
          <div class="doc-meta__row">服务类型：{{ serviceTypeLabel }}</div>
          <div class="doc-meta__row">
            认证方式：{{
              optionLabel(AUTH_TYPE_OPTIONS, api.authType ?? 'NONE')
            }}
            <Tag class="ml-1" color="processing">
              {{ optionLabel(API_TYPE_OPTIONS, api.apiType ?? 'QUERY') }}
            </Tag>
          </div>
          <div class="doc-meta__row">
            接口地址：<span class="doc-meta__url">{{ fullUrl }}</span>
            <Button size="small" type="link" @click="handleCopyUrl">
              <template #icon><IconifyIcon icon="lucide:copy" /></template>
              复制
            </Button>
          </div>
          <div class="doc-meta__row">
            请求方式：
            <Tag :color="api.method === 'GET' ? 'green' : 'geekblue'">
              {{ api.method }}
            </Tag>
          </div>
        </div>

        <!-- 在线签名工具 -->
        <div v-if="signVisible" class="doc-sign">
          <div class="doc-sign__rule">
            HMAC-SM3 覆盖方法、路径、查询串、原始请求体、Content-Type、时间戳与
            Nonce；必须与实际发送内容一致。API_PATH 为 {{ gatewayPath }}
          </div>
          <div class="doc-sign__grid">
            <div class="doc-sign__item">
              <span>X-AppKey</span>
              <Input
                v-model:value="signForm.appKey"
                placeholder="应用 AppKey"
              />
            </div>
            <div class="doc-sign__item">
              <span>AppSecret</span>
              <Input
                v-model:value="signForm.appSecret"
                placeholder="应用秘钥"
              />
            </div>
            <div class="doc-sign__item">
              <span>X-Timestamp</span>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  v-model:value="signForm.timestamp"
                  placeholder="毫秒时间戳"
                />
                <Button size="small" type="link" @click="fillTimestamp">
                  <template #icon><IconifyIcon icon="lucide:clock" /></template>
                  在线获取
                </Button>
              </div>
            </div>
            <div class="doc-sign__item">
              <span>X-Nonce</span>
              <div class="flex flex-1 items-center gap-2">
                <Input v-model:value="signForm.nonce" placeholder="随机数" />
                <Button size="small" type="link" @click="fillNonce">
                  <template #icon>
                    <IconifyIcon icon="lucide:shuffle" />
                  </template>
                  随机生成
                </Button>
              </div>
            </div>
            <div class="doc-sign__item doc-sign__item--full">
              <span>原始查询串</span>
              <Input
                v-model:value="signForm.queryString"
                placeholder="不带 ?，需与实际请求一致"
              />
            </div>
            <div class="doc-sign__item doc-sign__item--full">
              <span>Content-Type</span>
              <Input
                v-model:value="signForm.contentType"
                placeholder="需与实际请求头一致"
              />
            </div>
            <div class="doc-sign__item doc-sign__item--full">
              <span>原始请求体</span>
              <Input.TextArea
                v-model:value="signForm.body"
                :rows="3"
                placeholder="粘贴实际发送的 UTF-8 原文；不要重新格式化 JSON"
              />
            </div>
            <div class="doc-sign__item doc-sign__item--full">
              <span>X-Sign</span>
              <div class="flex flex-1 items-center gap-2">
                <Input
                  :value="signResult"
                  placeholder="点击加密生成"
                  readonly
                />
                <Button size="small" type="link" @click="handleCopySign">
                  <template #icon><IconifyIcon icon="lucide:copy" /></template>
                  复制结果
                </Button>
              </div>
            </div>
          </div>
          <Button
            :loading="signing"
            size="small"
            type="primary"
            @click="handleSign"
          >
            <template #icon><IconifyIcon icon="lucide:key-round" /></template>
            加密
          </Button>
        </div>

        <!-- 请求头 -->
        <template v-if="headerRows.length > 0">
          <div class="doc-section">请求头：</div>
          <Table
            class="supply-font-table"
            :columns="headerColumns"
            :data-source="headerRows"
            :pagination="false"
            row-key="key"
            size="small"
          />
        </template>

        <!-- 请求体 -->
        <div class="doc-section">
          {{ api.method === 'GET' ? '请求参数：' : '请求体：' }}
        </div>
        <Table
          class="supply-font-table"
          :columns="requestColumns"
          :data-source="requestRows"
          :pagination="false"
          row-key="key"
          size="small"
        />

        <!-- 响应体 -->
        <div class="doc-section">响应体：</div>
        <Table
          class="supply-font-table"
          :columns="responseColumns"
          :data-source="responseRows"
          :default-expand-all-rows="true"
          :pagination="false"
          row-key="key"
          size="small"
        />

        <!-- 示例 -->
        <div class="doc-section">请求示例：</div>
        <pre class="doc-code">{{ requestExample }}</pre>

        <div class="doc-section">响应示例：</div>
        <pre class="doc-code">{{ responseExample }}</pre>

        <div class="doc-section">异常示例：</div>
        <pre class="doc-code">{{ errorExample }}</pre>

        <!-- 状态码 -->
        <div class="doc-section">状态码：</div>
        <Table
          class="supply-font-table"
          :columns="statusColumns"
          :data-source="statusRows"
          :pagination="false"
          row-key="code"
          size="small"
        />
      </div>
    </div>
  </Drawer>
</template>

<style scoped>
.supply-font-table :deep(.ant-table-thead > tr > th),
.supply-font-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.doc-page {
  position: relative;
  min-height: calc(100vh - 108px);
  padding: 4px 0 24px;
  background: hsl(var(--muted) / 40%);
}

.doc-page__actions {
  position: fixed;
  top: 76px;
  right: 32px;
  z-index: 10;
  display: flex;
  gap: 8px;
}

.doc-card {
  max-width: 960px;
  padding: 28px 36px;
  margin: 0 auto;
  background: hsl(var(--card));
  border-radius: 8px;
  box-shadow: 0 1px 4px rgb(0 0 0 / 6%);
}

.doc-card__title {
  margin-bottom: 16px;
  font-size: 18px;
  font-weight: 700;
}

.doc-meta__row {
  display: flex;
  align-items: center;
  margin: 4px 0;
  font-size: 14px;
  color: hsl(var(--foreground) / 85%);
}

.doc-meta__url {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.doc-section {
  padding-left: 8px;
  margin: 18px 0 8px;
  font-size: 16px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.doc-code {
  max-height: 420px;
  padding: 14px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: #9cdcfe;
  word-break: break-all;
  white-space: pre-wrap;
  background: #1f1f1f;
  border-radius: 6px;
}

.doc-sign {
  padding: 12px;
  margin-top: 12px;
  background: hsl(var(--muted) / 30%);
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.doc-sign__rule {
  padding: 8px 10px;
  margin-bottom: 10px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 50%);
  border-radius: 6px;
}

.doc-sign__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
  margin-bottom: 10px;
}

.doc-sign__item {
  display: flex;
  gap: 8px;
  align-items: center;
}

.doc-sign__item > span {
  flex-shrink: 0;
  width: 90px;
  font-size: 14px;
  text-align: right;
}

.doc-sign__item--full {
  grid-column: 1 / -1;
}
</style>

/* oxlint-disable unicorn/text-encoding-identifier-case -- 编码值遵循 qData 表单与 Java Charset 协议。 */
/** 字段来自 qData 公开配置页面与本次 REA 抓取的表单标签；连接凭据只由平台数据连接保管。 */
export interface ConnectorField {
  key: string;
  label: string;
  kind: 'json' | 'number' | 'select' | 'text' | 'textarea';
  required?: boolean;
  defaultValue?: number | string;
  min?: number;
  max?: number;
  options?: { disabled?: boolean; label: string; value: number | string }[];
  when?: { key: string; value: number | string };
  full?: boolean;
  /** Overrides the generated placeholder text. */
  placeholder?: string;
  /** Character limit with an in-box counter for text areas. */
  maxLength?: number;
  /** Helper text rendered under the control. */
  hint?: string;
  /** Hides the JSON validity message unless the value is invalid. */
  quiet?: boolean;
  /** Renders a select without its dropdown arrow, like a plain input. */
  plain?: boolean;
}
export interface ConnectorSchema {
  name: string;
  connection: boolean;
  fields: ConnectorField[];
  columnMode: 'index' | 'json' | 'plain' | 'redis';
  requiresColumns: boolean;
  direction: 'input' | 'output';
  execution: 'CREDENTIAL_ADAPTER' | 'MANAGED_DATAX' | 'PENDING_ADAPTER';
}
export interface ConnectorColumn {
  [key: string]: unknown;
  name: string;
  type: string;
  key?: string;
  index?: number;
  comment?: string;
  length?: number | string;
  precision?: number | string;
  format?: string;
}
export interface ConnectorIssue {
  field: string;
  message: string;
}
const choices = (...values: string[]) =>
  values.map((value) => ({ label: value, value }));
const encoding: ConnectorField = {
  key: 'encoding',
  label: '编码',
  kind: 'select',
  defaultValue: 'UTF-8',
  options: choices('UTF-8', 'GBK', 'GB2312', 'ISO-8859-1'),
};
const where: ConnectorField = {
  key: 'whereCondition',
  label: 'where条件',
  kind: 'textarea',
  full: true,
};
const batch: ConnectorField = {
  key: 'batchSize',
  label: '单次写入数据',
  kind: 'number',
  defaultValue: 1000,
  min: 1,
};
const sqlMode: ConnectorField = {
  key: 'ifSql',
  label: '连接方式',
  kind: 'select',
  defaultValue: 0,
  options: [
    { label: '数据连接', value: 0 },
    { label: 'SQL', value: 1 },
  ],
};
const table: ConnectorField = {
  key: 'tableName',
  label: '选择表',
  kind: 'text',
  required: true,
  when: { key: 'ifSql', value: 0 },
};
const sql: ConnectorField = {
  key: 'sql',
  label: 'SQL语句',
  kind: 'textarea',
  required: true,
  full: true,
  when: { key: 'ifSql', value: 1 },
};
const readMode: ConnectorField = {
  key: 'readMode',
  label: '读取模式',
  kind: 'select',
  defaultValue: 'FULL',
  options: [
    { label: '全量', value: 'FULL' },
    { label: 'ID增量', value: 'ID_INCREMENT' },
    { label: '时间范围增量', value: 'TIME_INCREMENT' },
  ],
};
const incrementFields: ConnectorField[] = [
  {
    key: 'incrementColumn',
    label: 'id字段',
    kind: 'text',
    required: true,
    when: { key: 'readMode', value: 'ID_INCREMENT' },
  },
  {
    key: 'incrementStart',
    label: '开始值',
    kind: 'text',
    required: true,
    when: { key: 'readMode', value: 'ID_INCREMENT' },
  },
  {
    key: 'dateColumn',
    label: '时间字段',
    kind: 'text',
    required: true,
    when: { key: 'readMode', value: 'TIME_INCREMENT' },
  },
  {
    key: 'dateFormat',
    label: '时间格式',
    kind: 'select',
    defaultValue: 'yyyy-MM-dd HH:mm:ss',
    options: choices('yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss'),
    when: { key: 'readMode', value: 'TIME_INCREMENT' },
  },
  {
    key: 'cursorTime',
    label: '初始游标时间',
    kind: 'text',
    required: true,
    when: { key: 'readMode', value: 'TIME_INCREMENT' },
  },
];
const writeMode: ConnectorField = {
  key: 'writeModeType',
  label: '写入模式',
  kind: 'select',
  defaultValue: 2,
  options: [
    { label: '全量', value: 1 },
    { label: '追加', value: 2 },
  ],
};
const tableWriteMode: ConnectorField = {
  key: 'writeModeType',
  label: '写入模式',
  kind: 'select',
  defaultValue: 2,
  options: [
    { label: '全量', value: 1 },
    { label: '追加', value: 2 },
  ],
};
const tabularInput: ConnectorField[] = [
  sqlMode,
  table,
  sql,
  readMode,
  ...incrementFields,
  where,
];
const tabularOutput: ConnectorField[] = [
  {
    key: 'tableName',
    label: '选择表',
    kind: 'text',
    required: true,
  },
  where,
  tableWriteMode,
  batch,
  {
    key: 'primaryKeys',
    label: '更新主键字段',
    kind: 'text',
    when: { key: 'writeModeType', value: 2 },
  },
];
function schema(
  name: string,
  fields: ConnectorField[],
  direction: ConnectorSchema['direction'],
  columnMode: ConnectorSchema['columnMode'] = 'plain',
  connection = true,
  requiresColumns = true,
  execution: ConnectorSchema['execution'] = 'PENDING_ADAPTER',
): ConnectorSchema {
  return {
    name,
    fields,
    direction,
    columnMode,
    connection,
    requiresColumns,
    execution,
  };
}
function fileFields(kind: 'ftp' | 'hdfs', output: boolean): ConnectorField[] {
  return [
    // File inputs follow the qData reference: required encoding shown as a plain box.
    output ? encoding : { ...encoding, required: true, plain: true },
    ...(kind === 'ftp' && !output ? [
      { key: 'syncMode', label: '同步方式', kind: 'select', defaultValue: 'SINGLE_FILE', options: [{label: '单文件', value: 'SINGLE_FILE'}, {label: '指定目录', value: 'DIRECTORY'}] } as ConnectorField,
      // Deleting source files is not supported by the FTP compiler, so "是" stays disabled.
      { key: 'syncDelete', label: '同步删除', kind: 'select', defaultValue: 0, hint: '同步删除来源数据。', options: [{label: '否', value: 0}, {label: '是', value: 1, disabled: true}] } as ConnectorField,
    ] : []),
    {
      key: 'path',
      label: '文件路径',
      kind: 'text',
      required: true,
      full: output,
    },
    {
      key: 'fileType',
      label: '文件类型',
      kind: 'select',
      required: !output,
      defaultValue: 'csv',
      options: choices('csv', 'text'),
    },
    {
      key: 'fieldDelimiter',
      label: '分隔符',
      kind: 'text',
      required: true,
      defaultValue: ',',
    },
    ...(kind === 'hdfs'
      ? [
          {
            key: 'nullFormat',
            label: 'NULL标记',
            kind: 'text',
            required: true,
            defaultValue: '\\N',
          } as ConnectorField,
          ...(output
            ? []
            : [
                {
                  key: 'hasHeader',
                  label: '首行表头',
                  kind: 'select',
                  defaultValue: 'false',
                  options: [
                    { label: '无表头', value: 'false' },
                    { label: '有表头', value: 'true' },
                  ],
                } as ConnectorField,
              ]),
        ]
      : []),
    ...(output
      ? [
          {
            key: 'compression',
            label: '压缩方式',
            kind: 'select',
            defaultValue: '',
            options: [
              { label: '不压缩', value: '' },
              ...choices('gzip', 'bzip2', 'lzo', 'snappy'),
            ],
          } as ConnectorField,
        ]
      : []),
    output
      ? {
          key: kind === 'ftp' ? 'ftpConfig' : 'hadoopConfig',
          label: '配置参数',
          kind: 'json',
          defaultValue: '{}',
          full: true,
        }
      : {
          // Optional on input: both compilers treat a missing object as empty.
          key: kind === 'ftp' ? 'ftpConfig' : 'hadoopConfig',
          label: '配置参数',
          kind: 'json',
          full: true,
          maxLength: 256,
          quiet: true,
          placeholder:
            kind === 'ftp'
              ? '例如: {"passiveMode":true,"connectTimeout":10000}'
              : '例如: {"kerberosKeytabFilePath":"/path/to/keytab/file"}',
        },
    ...(output
      ? [
          {
            key: 'fileName',
            label: '文件名前缀',
            kind: 'text',
            required: true,
            defaultValue: 'data',
          } as ConnectorField,
          kind === 'hdfs'
            ? ({
                ...writeMode,
                options: [{ label: '追加', value: 2 }, { label: '全量', value: 1, disabled: true }],
              } as ConnectorField)
            : writeMode,
        ]
      : []),
  ];
}
function mqFields(output: boolean): ConnectorField[] {
  return [
    {
      key: 'topic',
      label: '主题名称',
      kind: 'text',
      required: true,
      full: output,
    },
    {
      key: 'config',
      label: '配置参数',
      kind: 'json',
      defaultValue: '{}',
      full: true,
    },
    ...(output ? [batch] : []),
  ];
}
function ftpFields(output: boolean): ConnectorField[] {
  return [
    {
      key: 'resourceBindingId',
      label: '执行资源编号',
      kind: 'text',
      required: true,
      full: true,
    },
    {
      key: 'credentialRef',
      label: '凭据引用编号',
      kind: 'text',
      required: true,
      full: true,
    },
    {
      key: 'maxBytes',
      label: '最大文件字节数',
      kind: 'number',
      min: 1,
      max: 67_108_864,
      defaultValue: 8_388_608,
    },
    {
      key: 'maxRows',
      label: '最大处理行数',
      kind: 'number',
      min: 1,
      max: 1_000_000,
      defaultValue: 100_000,
    },
    ...fileFields('ftp', output).map((field) => {
      if (field.key === 'compression')
        return {
          ...field,
          options: field.options?.map((option) => ({
            ...option,
            disabled: !['', 'gzip', 'none'].includes(String(option.value)),
          })),
        };
      if (field.key === 'writeModeType')
        return {
          ...field,
          options: [
            { label: '追加', value: 2 },
            { label: '全量', value: 1, disabled: true },
          ],
        };
      return field;
    }),
    ...(output
      ? []
      : [
          {
            key: 'compression',
            label: '压缩方式',
            kind: 'select',
            defaultValue: '',
            options: [
              { label: '不压缩', value: '' },
              { label: 'gzip', value: 'gzip' },
            ],
          } as ConnectorField,
        ]),
  ];
}
function redisFields(output: boolean): ConnectorField[] {
  return [
    {
      key: 'dataType',
      label: '数据类型',
      kind: 'select',
      defaultValue: 'string',
      options: choices('string', 'hash', 'list'),
    },
    {
      key: 'keyPattern',
      label: output ? 'Key模式（字段模板）' : 'Key模式',
      kind: 'text',
      required: true,
      full: true,
    },
    ...(output ? [batch] : []),
  ];
}
const sqlHooks: ConnectorField[] = [
  { key: 'preSql', label: '前置SQL', kind: 'textarea', full: true },
  { key: 'postSql', label: '后置SQL', kind: 'textarea', full: true },
];
export const connectorSchemas: Readonly<Record<string, ConnectorSchema>> = {
  'read-csv': schema(
    'CSV输入组件',
    [
      {
        key: 'resourceBindingId',
        label: '受管文件资源',
        kind: 'text',
        required: true,
        full: true,
      },
      {
        key: 'path',
        label: '文件相对路径',
        kind: 'text',
        required: true,
        full: true,
      },
      encoding,
      {
        key: 'fieldDelimiter',
        label: '分隔符',
        kind: 'text',
        required: true,
        defaultValue: ',',
      },
      {
        key: 'hasHeader',
        label: '首行为表头',
        kind: 'select',
        defaultValue: 'true',
        options: [
          { label: '是', value: 'true' },
          { label: '否', value: 'false' },
        ],
      },
    ],
    'input',
    'index',
    false,
    true,
    'MANAGED_DATAX',
  ),
  'write-csv': schema(
    'CSV输出组件',
    [
      {
        key: 'resourceBindingId',
        label: '受管文件资源',
        kind: 'text',
        required: true,
        full: true,
      },
      {
        key: 'path',
        label: '输出目录相对路径',
        kind: 'text',
        required: true,
        full: true,
      },
      {
        key: 'fileName',
        label: '文件名前缀',
        kind: 'text',
        required: true,
        defaultValue: 'data',
      },
      encoding,
      {
        key: 'fieldDelimiter',
        label: '分隔符',
        kind: 'text',
        required: true,
        defaultValue: ',',
      },
      {
        key: 'includeHeader',
        label: '写入表头',
        kind: 'select',
        defaultValue: 'true',
        options: [
          { label: '是', value: 'true' },
          { label: '否', value: 'false' },
        ],
      },
    ],
    'output',
    'plain',
    false,
    true,
    'MANAGED_DATAX',
  ),
  'read-odps': schema(
    'ODPS输入组件',
    tabularInput,
    'input',
    'plain',
    true,
    true,
    'CREDENTIAL_ADAPTER',
  ),
  'write-odps': schema(
    'ODPS输出组件',
    [
      ...tabularOutput
        .filter(
          (field) =>
            field.key !== 'whereCondition' && field.key !== 'primaryKeys',
        )
        .map((field) =>
          field.key === 'batchSize' ? { ...field, defaultValue: 1024 } : field,
        ),
      ...sqlHooks,
    ],
    'output',
    'plain',
    true,
    true,
    'CREDENTIAL_ADAPTER',
  ),
  'read-hive': schema(
    'Hive输入组件',
    tabularInput.map((field) => {
      if (field.key === 'ifSql')
        return {
          ...field,
          options: field.options?.map((option) => ({
            ...option,
            disabled: option.value !== 0,
          })),
        };
      if (field.key === 'readMode')
        return {
          ...field,
          options: field.options?.map((option) => ({
            ...option,
            disabled: option.value !== 'FULL',
          })),
        };
      return field;
    }),
    'input',
  ),
  'write-hive': schema(
    'Hive输出组件',
    [
      ...tabularOutput.map((field) => {
        if (field.key === 'writeModeType')
          return {
            ...writeMode,
            options: writeMode.options?.map((option) => ({
              ...option,
              disabled: option.value !== 2,
            })),
          };
        if (field.key === 'batchSize')
          return {
            ...field,
            defaultValue: undefined,
            label: '单次写入条数（暂不支持，可清空）',
          };
        if (field.key === 'primaryKeys')
          return { ...field, label: '更新主键（暂不支持，可清空）' };
        if (field.key === 'whereCondition')
          return { ...field, label: '覆盖条件（暂不支持，可清空）' };
        return field;
      }),
      ...sqlHooks.map((field) => ({
        ...field,
        label: `${field.label}（暂不支持，可清空）`,
      })),
    ],
    'output',
  ),
  'read-mongodb': schema(
    'MongoDB输入组件',
    tabularInput,
    'input',
    'json',
    true,
    true,
    'MANAGED_DATAX',
  ),
  'write-mongodb': schema(
    'MongoDB输出组件',
    tabularOutput.map((field) => {
      if (field.key === 'writeModeType') return {
        ...field, defaultValue: 0, options: [
          { label: '追加', value: 0 },
          { label: '全量', value: 1, disabled: true },
          { label: '增量更新（历史配置）', value: 2 },
        ],
      };
      if (field.key === 'batchSize') return { ...field, defaultValue: 1024 };
      return field;
    }),
    'output',
    'json',
    true,
    true,
    'MANAGED_DATAX',
  ),
  'read-tdengine': schema(
    'TDengine输入组件',
    [
      {
        key: 'resourceBindingId',
        label: '执行资源编号',
        kind: 'text',
        full: true,
      },
      { key: 'topic', label: '订阅 Topic', kind: 'text', required: true },
      { key: 'groupId', label: '消费组 ID', kind: 'text', required: true },
      {
        key: 'offsetReset',
        label: '消费起始位置',
        kind: 'select',
        defaultValue: 'earliest',
        options: [
          { label: '从最早可消费位置开始', value: 'earliest' },
          { label: '从任务启动后的新数据开始', value: 'latest' },
        ],
      },
    ],
    'input',
  ),
  'write-tdengine': schema('TDengine输出组件', tabularOutput, 'output'),
  'read-hdfs': schema(
    'HDFS输入组件',
    fileFields('hdfs', false),
    'input',
    'index',
    true,
    true,
    'MANAGED_DATAX',
  ),
  'write-hdfs': schema(
    'HDFS输出组件',
    fileFields('hdfs', true),
    'output',
    'plain',
    true,
    true,
    'MANAGED_DATAX',
  ),
  'read-ftp': schema(
    'FTP输入组件',
    ftpFields(false),
    'input',
    'index',
    false,
    true,
    'CREDENTIAL_ADAPTER',
  ),
  'write-ftp': schema(
    'FTP输出组件',
    ftpFields(true),
    'output',
    'plain',
    false,
    true,
    'CREDENTIAL_ADAPTER',
  ),
  'read-rabbitmq': schema(
    'RabbitMQ输入组件',
    mqFields(false),
    'input',
    'json',
  ),
  'write-rabbitmq': schema(
    'RabbitMQ输出组件',
    mqFields(true).map((field) => {
      if (field.key === 'topic') return { ...field, label: '队列名称' };
      if (field.key === 'batchSize')
        return {
          ...field,
          defaultValue: 1,
          max: 1,
          label: '单次写入条数（逐条确认）',
        };
      return field;
    }),
    'output',
  ),
  'read-rocketmq': schema(
    'RocketMQ输入组件',
    [
      ...mqFields(false),
      {
        key: 'groupId',
        label: '消费组名称',
        kind: 'text',
        required: true,
        full: true,
      },
      {
        key: 'offsetReset',
        label: '无保存位点时起点',
        kind: 'select',
        defaultValue: 'earliest',
        options: [
          { label: '最早', value: 'earliest' },
          { label: '最新', value: 'latest' },
        ],
      },
    ],
    'input',
    'json',
  ),
  'write-rocketmq': schema(
    'RocketMQ输出组件',
    [
      ...mqFields(true).map((field) =>
        field.key === 'batchSize'
          ? {
              ...field,
              defaultValue: 1,
              max: 1,
              label: '单次写入条数（逐条确认）',
            }
          : field,
      ),
      {
        key: 'groupId',
        label: '生产组名称',
        kind: 'text',
        required: true,
        full: true,
      },
    ],
    'output',
  ),
  'read-redis': schema('Redis输入组件', redisFields(false), 'input', 'redis'),
  'write-redis': schema('Redis输出组件', redisFields(true), 'output'),
  'read-webservice': schema(
    'WebService输入组件',
    [
      {
        key: 'resourceBindingId',
        label: '执行资源编号',
        kind: 'text',
        required: true,
        full: true,
      },
      {
        key: 'apiUrl',
        label: 'API URL',
        kind: 'text',
        required: true,
        full: true,
      },
      { key: 'operationName', label: '请求接口', kind: 'text', required: true },
      { key: 'serviceName', label: '服务名称', kind: 'text' },
      { key: 'portName', label: '端口名称', kind: 'text' },
      {
        key: 'apiMethod',
        label: '请求方式',
        kind: 'select',
        required: true,
        defaultValue: 'soap11',
        options: [
          { label: 'SOAP 1.1', value: 'soap11' },
          { label: 'SOAP 1.2', value: 'soap12' },
          { label: 'HTTP GET', value: 'httpGet' },
          { label: 'HTTP POST', value: 'httpPost' },
        ],
      },
      { key: 'targetNamespace', label: '命名空间', kind: 'text', full: true },
      {
        key: 'requestParams',
        label: '请求参数',
        kind: 'json',
        defaultValue: '{}',
        full: true,
      },
      {
        key: 'headerParams',
        label: 'Header参数',
        kind: 'json',
        defaultValue: '{}',
        full: true,
      },
      {
        key: 'headerSecretRefs',
        label: '请求头凭据引用',
        kind: 'json',
        defaultValue: '{}',
        full: true,
      },
      {
        key: 'maxRows',
        label: '最大读取行数',
        kind: 'number',
        min: 1,
        max: 1_000_000,
        defaultValue: 100_000,
      },
      {
        key: 'maxResponseBytes',
        label: '最大响应字节数',
        kind: 'number',
        min: 1,
        max: 16_777_216,
        defaultValue: 8_388_608,
      },
      {
        key: 'returnFormat',
        label: '返回数据格式',
        kind: 'select',
        defaultValue: 'XML',
        options: choices('XML', 'JSON对象', 'JSON数组'),
      },
      { key: 'returnDataLine', label: '返回数据行所在字段', kind: 'text' },
    ],
    'input',
    'json',
    false,
  ),
};
export function getConnectorSchema(
  code: string,
  mode?: string,
): ConnectorSchema | undefined {
  if (code === 'read-tdengine' && mode === 'OFFLINE')
    return schema('TDengine输入组件', [
      { key: 'resourceBindingId', label: '执行资源编号', kind: 'text', full: true },
      { key: 'tableName', label: '选择表', kind: 'text', required: true },
      { key: 'readMode', label: '读取方式', kind: 'select', defaultValue: 'TABLE', options: [{ label: '表快照', value: 'TABLE' }] },
    ], 'input');
  return connectorSchemas[code];
}
export function connectorFieldVisible(
  field: ConnectorField,
  config: Record<string, unknown>,
) {
  return (
    !field.when ||
    (config[field.when.key] ?? getDefaultForField(field.when.key)) ===
      field.when.value
  );
}
function getDefaultForField(key: string) {
  if (key === 'ifSql') return 0;
  if (key === 'readMode') return 'FULL';
  return undefined;
}
export function connectorDefaults(code: string, mode?: string): Record<string, unknown> {
  const result: Record<string, unknown> = { columns: [] };
  for (const field of getConnectorSchema(code, mode)?.fields ?? [])
    if (
      field.defaultValue !== undefined &&
      connectorFieldVisible(field, result)
    )
      result[field.key] = field.defaultValue;
  return result;
}
export function tdengineLegacyConfigKeys(config: Record<string, unknown>, mode?: string) {
  if (mode === 'OFFLINE') return [...['topic', 'groupId', 'offsetReset', 'querySql', 'sql', 'whereCondition'].filter((key) => config[key] !== undefined), ...(config.readMode !== undefined && config.readMode !== 'TABLE' ? ['readMode'] : [])];
  return [
    'batchSize',
    'cursorTime',
    'dateColumn',
    'dateFormat',
    'fetchSize',
    'ifSql',
    'incrementColumn',
    'incrementStart',
    'postSql',
    'preSql',
    'querySql',
    'readMode',
    'readModeType',
    'sql',
    'tableName',
    'whereCondition',
  ].filter((key) => config[key] !== undefined);
}
function validHeaderSecretReferences(value: unknown): boolean {
  try {
    if (typeof value === 'string' && value.length > 4096) return false;
    const parsed: unknown =
      typeof value === 'string' ? JSON.parse(value) : value;
    return (
      !!parsed &&
      typeof parsed === 'object' &&
      !Array.isArray(parsed) &&
      Object.keys(parsed).length <= 32 &&
      Object.entries(parsed).every(
        ([name, ref]) =>
          /^[A-Za-z][A-Za-z0-9-]{0,99}$/u.test(name) &&
          typeof ref === 'string' &&
          /^[A-Za-z][A-Za-z0-9_.-]{0,99}$/u.test(ref),
      )
    );
  } catch {
    return false;
  }
}
function hasSecret(value: unknown, referenceMode?: 'FTP' | 'SOAP'): boolean {
  if (!value || typeof value !== 'object') return false;
  if (Array.isArray(value)) return value.some((item) => hasSecret(item));
  return Object.entries(value).some(
    ([key, item]) =>
      !(
        (referenceMode === 'SOAP' &&
          key === 'headerSecretRefs' &&
          validHeaderSecretReferences(item)) ||
        (referenceMode === 'FTP' &&
          key === 'credentialRef' &&
          typeof item === 'string' &&
          /^[A-Za-z][A-Za-z0-9_.-]{0,99}$/u.test(item))
      ) &&
      (/password|passwd|token|secret|access.?key|authorization|credential|sasl\.jaas/i.test(
        key,
      ) ||
        hasSecret(item)),
  );
}
/** JSON 输入按键即时反馈；成功只表示格式合法，不表示连接器已具备执行能力。 */
export function connectorJsonFeedback(
  value: unknown,
  label: string,
  secretReferences = false,
) {
  try {
    const parsed: unknown =
      typeof value === 'string' ? JSON.parse(value) : value;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
      return { valid: false, message: `${label}必须为有效的JSON对象` };
    if (secretReferences)
      return validHeaderSecretReferences(parsed)
        ? { valid: true, message: '凭据引用格式有效' }
        : {
            valid: false,
            message: `${label}只能填写请求头名称和已授权凭据引用编号`,
          };
    if (hasSecret(parsed))
      return {
        valid: false,
        message: `${label}不可保存凭据，请在平台数据连接中配置`,
      };
    return { valid: true, message: 'JSON对象格式有效' };
  } catch {
    return { valid: false, message: `${label}必须为有效的JSON对象` };
  }
}

/** 属性编辑复用同一字段约束，保留未知附加键，不用默认值覆盖旧字段。 */
export function validateConnectorColumn(
  column: ConnectorColumn,
  mode: ConnectorSchema['columnMode'],
  index = 0,
  redisType: unknown = 'string',
): string[] {
  const issues: string[] = [];
  if (!column.name?.trim() || !column.type?.trim())
    issues.push(`第${index + 1}行字段名称与类型不能为空`);
  for (const key of ['length', 'precision'] as const) {
    const value = column[key];
    if (
      value !== undefined &&
      value !== null &&
      value !== '' &&
      !/^\d+$/u.test(String(value))
    )
      issues.push(
        `第${index + 1}行${key === 'length' ? '字段长度' : '小数精度'}必须为非负整数`,
      );
  }
  if (
    mode === 'index' &&
    (!Number.isSafeInteger(column.index) || column.index! < 0)
  )
    issues.push(`第${index + 1}行索引必须为非负整数`);
  if (mode === 'redis') {
    const selector = column.redisField ?? column.key ?? column.name;
    if (typeof selector !== 'string' || !(/^(?:@key|@value|@index|[\p{L}_][\p{L}\p{N}_-]{0,127})$/u.test(selector) || (redisType !== 'hash' && /^\$\.[\p{L}_][\p{L}\p{N}_]*(?:\.[\p{L}_][\p{L}\p{N}_]*)*$/u.test(selector))))
      issues.push(`第${index + 1}行解析值须为字段名、JSON属性路径或@key/@value/@index`);
    else if ((selector === '@index' && redisType !== 'list') || (selector === '@value' && redisType === 'hash'))
      issues.push(`第${index + 1}行解析值与Redis数据类型不一致`);
    if (column.redisField !== undefined && column.key !== undefined && column.redisField !== column.key)
      issues.push(`第${index + 1}行解析值配置不一致，请重新修改该字段`);
  }
  return issues;
}
export function validateConnectorConfig(
  code: string,
  config: Record<string, unknown>,
  mode?: string,
): ConnectorIssue[] {
  const descriptor = getConnectorSchema(code, mode);
  if (!descriptor) return [{ field: 'toolCode', message: '未知连接器类型' }];
  const issues: ConnectorIssue[] = [];
  if (code === 'read-tdengine' && tdengineLegacyConfigKeys(config, mode).length > 0)
    issues.push({
      field: 'legacyReadOptions',
      message: mode === 'OFFLINE' ? '持续订阅或SQL参数不适用于表快照，请先明确清理' : '历史表读取参数不适用于持续订阅，请先明确清理',
    });
  let referenceMode: 'FTP' | 'SOAP' | undefined;
  if (code === 'read-webservice') referenceMode = 'SOAP';
  else if (['read-ftp', 'write-ftp'].includes(code)) referenceMode = 'FTP';
  if (hasSecret(config, referenceMode))
    issues.push({
      field: 'credentials',
      message: '节点配置不可保存凭据，请使用平台数据连接',
    });
  if (
    descriptor.connection &&
    (config.databaseId === undefined ||
      config.databaseId === null ||
      !/^\d+$/.test(String(config.databaseId)))
  )
    issues.push({ field: 'databaseId', message: '请选择平台数据连接' });
  for (const field of descriptor.fields) {
    if (!connectorFieldVisible(field, config)) continue;
    const value = config[field.key];
    const missing =
      value === undefined ||
      value === null ||
      (typeof value === 'string' && value.trim() === '');
    if (field.required && missing) {
      issues.push({ field: field.key, message: `请填写${field.label}` });
      continue;
    }
    if (missing) continue;
    if (
      field.key === 'resourceBindingId' &&
      (typeof value !== 'string' ||
        !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$/u.test(value))
    )
      issues.push({
        field: field.key,
        message: '请填写管理员为当前任务授权的资源绑定编号',
      });
    if (
      field.key === 'credentialRef' &&
      (typeof value !== 'string' ||
        !/^[A-Za-z][A-Za-z0-9_.-]{0,99}$/u.test(value))
    )
      issues.push({
        field: field.key,
        message: '请填写已授权的凭据引用编号，不可填写明文凭据',
      });
    if (
      descriptor.execution === 'MANAGED_DATAX' &&
      field.key === 'path' &&
      (typeof value !== 'string' ||
        value.startsWith('/') ||
        !/^[\w./-]+$/.test(value) ||
        value
          .split('/')
          .some((part) => part === '..' || part === '.' || part === ''))
    )
      issues.push({
        field: field.key,
        message: '请输入受管根目录下的相对路径，不含URL、通配符或目录跳转',
      });
    if (
      field.kind === 'number' &&
      (typeof value !== 'number' ||
        !Number.isSafeInteger(value) ||
        value < (field.min ?? 0) ||
        (field.max !== undefined && value > field.max))
    )
      issues.push({
        field: field.key,
        message:
          field.max === undefined
            ? `${field.label}必须为不小于${field.min ?? 0}的整数`
            : `${field.label}必须为${field.min ?? 0}至${field.max}的整数`,
      });
    if (
      field.kind === 'select' &&
      !field.options?.some((option) => option.value === value)
    )
      issues.push({ field: field.key, message: `请选择有效的${field.label}` });
    if (field.kind === 'json') {
      const feedback = connectorJsonFeedback(
        value,
        field.label,
        code === 'read-webservice' && field.key === 'headerSecretRefs',
      );
      if (!feedback.valid)
        issues.push({ field: field.key, message: feedback.message });
    }
    if (field.key === 'apiUrl' && typeof value === 'string') {
      try {
        const url = new URL(value);
        if (
          !['http:', 'https:'].includes(url.protocol) ||
          url.username ||
          url.password
        )
          throw new Error('地址格式');
      } catch {
        issues.push({
          field: field.key,
          message: '请填写不含凭据的HTTP或HTTPS地址',
        });
      }
    }
  }
  const columns = Array.isArray(config.columns)
    ? (config.columns as ConnectorColumn[])
    : [];
  if (descriptor.requiresColumns && columns.length === 0)
    issues.push({ field: 'columns', message: '请至少配置一个属性字段' });
  const names = new Set<string>();
  for (const [index, column] of columns.entries()) {
    issues.push(
      ...validateConnectorColumn(column, descriptor.columnMode, index, config.dataType).map(
        (message) => ({ field: 'columns', message }),
      ),
    );
    if (names.has(column.name))
      issues.push({ field: 'columns', message: `字段${column.name}重复` });
    names.add(column.name);
  }
  return issues;
}

import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { markRaw } from 'vue';

import { CommonStatusEnum, DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { handleTree } from '@vben/utils';

import { z } from '#/adapter/form';
import { getDeptList } from '#/api/system/dept';

import { isConnectOnlyType } from './connection-capabilities';
import {
  fileConnectionProtocol,
  isDataNodeAuthorities,
  isFileRootPath,
  isWebHdfsUrl,
} from './file-connection';
import TypeField from './modules/type-field.vue';
import {
  isConnectionEndpoint,
  nativeConnectionCode,
  serverAuthorities,
} from './native-connection';
import { hasSchemaConcept, schemaPlaceholder } from './schema-support';

/* oxlint-disable no-template-curly-in-string -- JDBC URL 模板使用 ${host} 等字面占位符 */

/** 用途分类选项：区分业务库（来源系统库）与中台库（数仓落地库）。 */
export const DATABASE_USAGE_OPTIONS = [
  { label: '业务库', value: 1, color: 'blue' },
  { label: '中台库', value: 2, color: 'purple' },
];

export function resolveJdbcUrl(
  jdbcUrl?: string,
  data?: Partial<FoundationDatabaseApi.Database>,
) {
  if (!jdbcUrl) {
    return '';
  }
  return jdbcUrl
    .replaceAll('${host}', data?.databaseIp || '')
    .replaceAll('${port}', data?.databasePort || '')
    .replaceAll('${dbName}', data?.databaseName || '')
    .replaceAll('${sid}', data?.databaseName || '')
    .replaceAll('${serviceName}', data?.databaseName || '')
    .replaceAll('${schema}', data?.databaseSchema || '');
}

export function isSqliteType(type?: FoundationDatabaseTypeApi.DatabaseType) {
  return (type?.productId || type?.typeCode)?.toUpperCase() === 'SQLITE3';
}

export function buildJdbcUrlFromValues(
  data?: Partial<FoundationDatabaseApi.Database>,
  type?: FoundationDatabaseTypeApi.DatabaseType,
) {
  if (fileConnectionProtocol(type) || nativeConnectionCode(type)) return '';
  const current = data?.jdbcUrl || '';
  const template = type?.urlTemplates || '';
  let source = current || template;
  if (current.includes('${')) {
    source = current;
  } else if (template.includes('${')) {
    source = template;
  }
  let next = resolveJdbcUrl(source, data);
  if (data?.databaseSchema && next.includes('currentSchema=')) {
    next = next.replace(
      /currentSchema=[^&]*/,
      `currentSchema=${data.databaseSchema}`,
    );
  }
  return next;
}

export interface DatabaseFormSchemaOptions {
  getSelectedType?: () => FoundationDatabaseTypeApi.DatabaseType | undefined;
  onEditType?: () => void;
  /** 编辑时保留自定义 URL，仅追踪仍由字段自动生成的 URL。 */
  preserveJdbcUrl?: boolean;
  getInitialValues?: () => Partial<FoundationDatabaseApi.Database> | undefined;
}

function connectionTimeoutField(): VbenFormSchema {
  return {
    fieldName: 'connectionTimeoutSeconds',
    label: '连接超时',
    component: 'InputNumber',
    defaultValue: 10,
    rules: z
      .number()
      .int('请输入整数秒')
      .min(1, '至少 1 秒')
      .max(30, '最多 30 秒'),
    componentProps: { min: 1, max: 30, precision: 0, addonAfter: '秒' },
    help: '每次测试的等待上限，默认 10 秒；调整超时可保留已保存的凭据。',
  };
}

/** 新增/修改的表单 */
export function useFormSchema(
  options: DatabaseFormSchemaOptions = {},
): VbenFormSchema[] {
  let lastAutomaticUrl: string | undefined;
  const schema: VbenFormSchema[] = [
    {
      fieldName: 'id',
      component: 'Input',
      dependencies: {
        triggerFields: [''],
        show: () => false,
      },
    },
    {
      fieldName: 'jdbcUrl',
      label: 'URL',
      component: 'Input',
      componentProps: () => ({
        allowClear: true,
        placeholder:
          options.getSelectedType?.()?.urlTemplates ||
          'mysql://user:password@host:port/database',
      }),
      dependencies: {
        triggerFields: [
          'databaseIp',
          'databasePort',
          'databaseName',
          'databaseSchema',
        ],
        trigger: (values, formApi) => {
          const type = options.getSelectedType?.();
          if (options.preserveJdbcUrl) {
            const initialUrl = buildJdbcUrlFromValues(
              { ...options.getInitialValues?.(), jdbcUrl: '' },
              type,
            );
            const currentUrl = values.jdbcUrl || '';
            if (
              !currentUrl ||
              (currentUrl !== initialUrl && currentUrl !== lastAutomaticUrl)
            )
              return;
          }
          const next = buildJdbcUrlFromValues(
            options.preserveJdbcUrl ? { ...values, jdbcUrl: '' } : values,
            type,
          );
          lastAutomaticUrl = next;
          if (next !== (values.jdbcUrl || '')) {
            formApi.setFieldValue('jdbcUrl', next);
          }
        },
      },
    },
    {
      fieldName: 'name',
      label: '连接名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '连接名称，留空则自动生成',
      },
    },
    {
      fieldName: 'databaseType',
      label: '连接类型',
      rules: 'required',
      component: markRaw(TypeField),
      componentProps: () => ({
        typeName: options.getSelectedType?.()?.typeName,
        typeCode:
          options.getSelectedType?.()?.typeCode ||
          options.getSelectedType?.()?.productId,
        iconPath: options.getSelectedType?.()?.iconPath,
        onEdit: options.onEditType,
      }),
    },
    {
      fieldName: 'usageType',
      label: '用途分类',
      component: 'RadioGroup',
      defaultValue: 1,
      componentProps: {
        options: DATABASE_USAGE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
      },
      help: '中台库用于承接数据同步入库（ODS 等数仓分层），业务库为来源系统库',
    },
    {
      fieldName: 'databaseIp',
      label: '主机',
      dependencies: {
        triggerFields: ['databaseType'],
        resolve: () => ({
          show: !isSqliteType(options.getSelectedType?.()),
          rules: isSqliteType(options.getSelectedType?.()) ? null : 'required',
        }),
      },
      formItemClass: 'col-span-8',
      component: 'Input',
      componentProps: {
        placeholder: '127.0.0.1',
      },
    },
    {
      fieldName: 'databasePort',
      label: '端口',
      dependencies: {
        triggerFields: ['databaseType'],
        resolve: () => ({
          show: !isSqliteType(options.getSelectedType?.()),
          rules: isSqliteType(options.getSelectedType?.()) ? null : 'required',
        }),
      },
      formItemClass: 'col-span-4 self-end',
      hideLabel: true,
      component: 'Input',
      componentProps: {
        placeholder: '3306',
      },
    },
    {
      fieldName: 'userName',
      label: '用户名',
      component: 'Input',
      componentProps: {
        placeholder: 'root',
      },
    },
    {
      fieldName: 'password',
      label: '密码',
      component: 'InputPassword',
      componentProps: {
        placeholder: '编辑时留空保留原密码',
      },
      help: '更改连接配置后，请重新输入密码再测试连接',
    },
    {
      fieldName: 'databaseName',
      label: '数据库',
      component: 'Input',
      componentProps: () => ({
        allowClear: true,
        placeholder: isSqliteType(options.getSelectedType?.())
          ? '请输入服务端可访问的 SQLite 数据库文件路径'
          : '可选',
      }),
    },
    {
      fieldName: 'databaseSchema',
      label: 'Schema',
      rules: 'required',
      component: 'Input',
      dependencies: {
        triggerFields: ['databaseType'],
        show: () => hasSchemaConcept(options.getSelectedType?.()?.typeCode),
      },
      componentProps: () => ({
        allowClear: true,
        placeholder: schemaPlaceholder(options.getSelectedType?.()?.typeCode),
      }),
    },
    {
      fieldName: 'extendConfig',
      label: 'URL 参数',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: 'charset=utf8mb4',
      },
    },
    {
      fieldName: 'orgId',
      label: '单位',
      component: 'ApiTreeSelect',
      componentProps: {
        api: async () => {
          const data = await getDeptList();
          return handleTree(data);
        },
        labelField: 'name',
        valueField: 'id',
        childrenField: 'children',
        allowClear: true,
        placeholder: '请选择单位',
        treeDefaultExpandAll: true,
      },
    },
    {
      fieldName: 'remark',
      label: '备注',
      component: 'TextArea',
      componentProps: {
        rows: 3,
        placeholder: '请勿在备注中明文保存密码',
      },
    },
  ];
  const nativeCode = nativeConnectionCode(options.getSelectedType?.());
  if (nativeCode)
    return nativeFormSchema(schema, nativeCode, options.preserveJdbcUrl);
  const protocol = fileConnectionProtocol(options.getSelectedType?.());
  if (!protocol)
    return isConnectOnlyType(options.getSelectedType?.())
      ? schema.filter((field) => field.fieldName !== 'usageType')
      : schema;
  const excluded = new Set([
    'databaseName',
    'databaseSchema',
    'extendConfig',
    'jdbcUrl',
    'usageType',
  ]);
  if (protocol === 'HDFS') excluded.add('password');
  const fields = schema.filter((field) => !excluded.has(field.fieldName));
  if (protocol === 'SFTP') {
    for (const field of fields) {
      if (
        field.fieldName === 'userName' ||
        (field.fieldName === 'password' && !options.preserveJdbcUrl)
      )
        field.rules = 'required';
    }
  }
  fields.push({
    fieldName: 'fileRootPath',
    label: '根目录',
    component: 'Input',
    rules: z
      .string()
      .trim()
      .min(1, '请输入根目录')
      .refine(isFileRootPath, '根目录须以 / 开头，不能包含上级目录或反斜杠'),
    componentProps: { placeholder: '/' },
    help: '文件管理仅允许访问此目录及其子目录。',
  });
  if (protocol === 'SFTP')
    fields.push(connectionTimeoutField(), {
      fieldName: 'fileHostKeyFingerprint',
      label: '主机指纹',
      component: 'Input',
      rules: z
        .string()
        .trim()
        .regex(
          /^SHA256:[A-Za-z\d+/]{43}$/u,
          '请输入服务端 SSH 主机的 SHA256 指纹',
        ),
      componentProps: { placeholder: 'SHA256:…' },
      help: '从服务端管理员或可信渠道核对 SSH 主机指纹，连接时严格校验。当前支持密码认证。',
    });
  if (protocol === 'HDFS')
    fields.push(
      {
        fieldName: 'fileWebHdfsUrl',
        label: 'WebHDFS 地址',
        component: 'Input',
        rules: z
          .string()
          .trim()
          .min(1, '请输入 WebHDFS 地址')
          .refine(
            isWebHdfsUrl,
            '请输入无账号密码、查询参数的 HTTP(S) WebHDFS 地址',
          ),
        componentProps: { placeholder: 'http://namenode:9870' },
        help: '填写 NameNode 的 HTTP 服务地址；当前支持简单用户认证，暂不支持 Kerberos。',
      },
      {
        fieldName: 'fileDataNodes',
        label: '允许的 DataNode',
        component: 'TextArea',
        rules: z
          .string()
          .optional()
          .refine(
            isDataNodeAuthorities,
            '每项填写 host:port，不包含协议、路径或账号密码',
          ),
        componentProps: { rows: 3, placeholder: 'datanode:9864，每行一项' },
        help: '上传和下载只跟随到这些 DataNode 的重定向；按集群实际地址填写。',
      },
    );
  return fields;
}

/** 原生协议按能力展示连接字段，复用相同的凭据与业务信息，不要求 JDBC URL 或 Schema。 */
function nativeFormSchema(
  schema: VbenFormSchema[],
  code: NonNullable<ReturnType<typeof nativeConnectionCode>>,
  editing = false,
): VbenFormSchema[] {
  const hostPort = ['RABBITMQ', 'REDIS'].includes(code);
  const noDatabase = ['KAFKA', 'RABBITMQ', 'ROCKETMQ'].includes(code);
  const labels: Record<
    string,
    { database: string; password: string; user: string }
  > = {
    OSS: { database: 'Bucket', user: 'Access Key', password: 'Secret Key' },
    MINIO: { database: 'Bucket', user: 'Access Key', password: 'Secret Key' },
    ODPS: { database: 'Project', user: 'Access Key', password: 'Secret Key' },
    INFLUXDB2: { database: 'Bucket', user: 'Organization', password: 'Token' },
    REDIS: { database: '数据库编号', user: 'ACL 用户名', password: '密码' },
    MILVUS: { database: '数据库', user: '用户名', password: '密码 / Token' },
  };
  const label = labels[code] ?? {
    database: '数据库',
    user: '用户名',
    password: '密码',
  };
  const credentialsRequired = (values: Record<string, unknown>) =>
    ['INFLUXDB2', 'MINIO', 'NEO4J', 'ODPS', 'OSS', 'RABBITMQ'].includes(code) ||
    (code === 'KAFKA' &&
      String(values.connectionSecurityProtocol ?? '').startsWith('SASL_')) ||
    (code === 'ROCKETMQ' && values.connectionAuthenticationMode === 'ACL') ||
    (code === 'MILVUS' && values.connectionAuthenticationMode !== 'NONE') ||
    (code === 'REDIS' && !!String(values.userName ?? '').trim());
  const excluded = new Set([
    'databaseSchema',
    'extendConfig',
    'jdbcUrl',
    'usageType',
  ]);
  if (!hostPort) {
    excluded.add('databaseIp');
    excluded.add('databasePort');
  }
  if (noDatabase) excluded.add('databaseName');
  const fields: VbenFormSchema[] = schema
    .filter((field) => !excluded.has(field.fieldName))
    .map((field): VbenFormSchema => {
      if (field.fieldName === 'userName' || field.fieldName === 'password') {
        const password = field.fieldName === 'password';
        const fieldLabel = password ? label.password : label.user;
        let placeholder = `请输入${fieldLabel}`;
        if (code === 'INFLUXDB2' && !password)
          placeholder = '组织名称或 16 位组织 ID';
        if (password && editing) placeholder = '留空保留原凭据';
        return {
          ...field,
          label: fieldLabel,
          componentProps: { placeholder },
          ...(password
            ? {
                help: '凭据只通过密码字段提交；更改目标或认证配置后需要重新输入。',
              }
            : {}),
          ...(code === 'INFLUXDB2' && !password
            ? {
                help: '填写组织名称或 16 位组织 ID；仅授予 Bucket 权限的 Token 建议填写组织 ID。',
              }
            : {}),
          dependencies: {
            triggerFields: [
              'connectionSecurityProtocol',
              'connectionAuthenticationMode',
              'userName',
            ],
            resolve: ({ values }) => ({
              rules:
                credentialsRequired(values) &&
                !(code === 'REDIS' && !password) &&
                !(password && editing) &&
                !(
                  code === 'MILVUS' &&
                  !password &&
                  values.connectionAuthenticationMode === 'TOKEN'
                )
                  ? 'required'
                  : null,
            }),
          },
        };
      }
      if (field.fieldName === 'databasePort')
        return {
          ...field,
          dependencies: undefined,
          rules: z
            .string()
            .regex(/^\d+$/u, '请输入端口')
            .refine(
              (value) => Number(value) >= 1 && Number(value) <= 65_535,
              '端口范围为 1–65535',
            ),
        };
      if (field.fieldName === 'databaseName')
        return {
          ...field,
          label: label.database,
          componentProps: {
            placeholder: code === 'REDIS' ? '0' : `请输入${label.database}`,
          },
          ...(code === 'NEO4J'
            ? {
                help: '填写实际数据库名，默认 neo4j。用户名和数据库名可以不同；测试会检查该库的访问权限。',
              }
            : {}),
          ...(['INFLUXDB2', 'MILVUS', 'MINIO', 'NEO4J', 'ODPS', 'OSS'].includes(
            code,
          )
            ? { rules: 'required' }
            : {}),
          ...(code === 'REDIS'
            ? {
                rules: z
                  .string()
                  .regex(/^\d*$/u, '数据库编号须为非负整数')
                  .optional(),
              }
            : {}),
        };
      return field;
    });
  const addInput = (
    fieldName: string,
    fieldLabel: string,
    placeholder: string,
    rules?: VbenFormSchema['rules'],
    help?: string,
  ) =>
    fields.push({
      fieldName,
      label: fieldLabel,
      component: 'Input',
      componentProps: { placeholder },
      rules,
      help,
    });
  const addSelect = (
    fieldName: string,
    fieldLabel: string,
    values: string[],
    defaultValue: string,
  ) =>
    fields.push({
      fieldName,
      label: fieldLabel,
      component: 'Select',
      defaultValue,
      componentProps: {
        options: values.map((value) => ({ label: value, value })),
      },
    });
  const addTls = () =>
    fields.push({
      fieldName: 'connectionTls',
      label: 'TLS',
      component: 'Switch',
      defaultValue: false,
    });
  const endpointHelp: Record<string, string> = {
    NEO4J:
      '单机使用 bolt://host:7687；集群路由使用 neo4j://。TLS 使用 bolt+s:// 或 neo4j+s:// 并校验可信证书；使用 Bolt 端口，不是浏览器端口 7474。',
    MILVUS: '填写支持 REST v2 的 Milvus 地址，通常使用 http://host:19530。',
    MINIO: '填写 S3 API 地址，通常端口 9000；不要填写控制台端口 9001。',
    ODPS: '填写所在地域的 MaxCompute API Endpoint，包含 /api 路径。',
  };
  if (!hostPort && !['KAFKA', 'ROCKETMQ'].includes(code))
    addInput(
      'connectionEndpoint',
      '服务地址',
      code === 'NEO4J' ? 'bolt://host:7687' : 'https://host:port',
      z
        .string()
        .trim()
        .refine(
          (value) => isConnectionEndpoint(value, code),
          code === 'NEO4J'
            ? '请输入 bolt://、neo4j:// 或对应 +s 地址；端口 1–65535，库名请在数据库字段填写'
            : '请输入带协议且不包含账号密码、查询参数的服务地址',
        ),
      endpointHelp[code],
    );
  if (['MINIO', 'OSS'].includes(code)) {
    addInput('connectionRegion', 'Region', '可选，按服务配置填写');
    fields.push({
      fieldName: 'connectionPathStyle',
      label: 'Path Style',
      component: 'Switch',
      defaultValue: code === 'MINIO',
    });
  }
  if (['KAFKA', 'ROCKETMQ'].includes(code))
    addInput(
      'connectionServers',
      code === 'KAFKA' ? 'Bootstrap Servers' : 'NameServers',
      'host:port，多项以逗号或换行分隔',
      z
        .string()
        .trim()
        .min(1, '请输入服务地址')
        .refine(
          (value) =>
            serverAuthorities(value).length <= (code === 'ROCKETMQ' ? 8 : 32) &&
            serverAuthorities(value).every((item) => /:\d+$/u.test(item)) &&
            isDataNodeAuthorities(value.replaceAll(';', ',')),
          `最多 ${code === 'ROCKETMQ' ? 8 : 32} 项 host:port，不包含协议、路径或凭据`,
        ),
    );
  if (code === 'KAFKA') {
    addSelect(
      'connectionSecurityProtocol',
      '安全协议',
      ['PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL'],
      'PLAINTEXT',
    );
    addSelect(
      'connectionSaslMechanism',
      'SASL 机制',
      ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512'],
      'PLAIN',
    );
  }
  if (code === 'RABBITMQ') {
    addInput('connectionVirtualHost', 'Virtual Host', '/');
    addTls();
  }
  if (code === 'REDIS') addTls();
  if (code === 'ROCKETMQ') {
    addInput('connectionTopic', 'Topic', '可选，用于只读路由探测');
    addSelect(
      'connectionAuthenticationMode',
      '认证方式',
      ['NONE', 'ACL'],
      'NONE',
    );
  }
  if (code === 'MILVUS')
    addSelect(
      'connectionAuthenticationMode',
      '认证方式',
      ['NONE', 'PASSWORD', 'TOKEN'],
      'PASSWORD',
    );
  fields.push(connectionTimeoutField());
  return fields;
}

/** 列表的搜索表单 */
export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '连接名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '请输入连接名称',
      },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: getDictOptions(DICT_TYPE.COMMON_STATUS, 'number'),
        placeholder: '请选择状态',
      },
    },
    {
      fieldName: 'usageType',
      label: '用途分类',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: DATABASE_USAGE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择用途分类',
      },
    },
  ];
}

/** 列表的字段 */
export function useGridColumns(
  onStatusChange?: (
    newStatus: number,
    row: FoundationDatabaseApi.Database,
  ) => PromiseLike<boolean | undefined>,
): VxeTableGridOptions<FoundationDatabaseApi.Database>['columns'] {
  return [
    { type: 'checkbox', width: 40 },
    {
      field: 'id',
      title: '编号',
      minWidth: 120,
    },
    {
      field: 'name',
      title: '连接名称',
      minWidth: 120,
    },
    {
      field: 'iconPath',
      title: '连接类型',
      width: 100,
      slots: { default: 'databaseType' },
    },
    {
      field: 'usageType',
      title: '用途分类',
      width: 90,
      formatter: ({ cellValue }) =>
        DATABASE_USAGE_OPTIONS.find((item) => item.value === cellValue)
          ?.label ?? '-',
    },
    {
      field: 'orgName',
      title: '单位',
      minWidth: 140,
    },
    {
      field: 'status',
      title: '状态',
      minWidth: 100,
      cellRender: {
        attrs: { beforeChange: onStatusChange },
        name: 'CellSwitch',
        props: {
          checkedValue: CommonStatusEnum.ENABLE,
          unCheckedValue: CommonStatusEnum.DISABLE,
        },
      },
    },
    {
      field: 'connectionStatus',
      title: '连通状态',
      width: 160,
      showOverflow: false,
      slots: { default: 'connectionStatus' },
    },
    {
      field: 'remark',
      title: '备注',
      minWidth: 120,
    },
    {
      field: 'creatorName',
      title: '创建者',
      minWidth: 100,
    },
    {
      field: 'createTime',
      title: '创建时间',
      minWidth: 120,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 260,
      minWidth: 260,
      fixed: 'right',
      showOverflow: false,
      className: 'database-action-column',
      slots: { default: 'actions' },
    },
  ];
}

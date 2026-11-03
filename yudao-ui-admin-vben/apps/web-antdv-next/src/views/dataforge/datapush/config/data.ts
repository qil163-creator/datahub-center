import type { VbenFormSchema } from '#/adapter/form';
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeDataPushConfigApi } from '#/api/dataforge/datapush/config';

import { markRaw } from 'vue';

import { handleTree } from '@vben/utils';

import { z } from '#/adapter/form';
import { getDatabaseSimpleList } from '#/api/dataforge/database';
import { getDeptList } from '#/api/system/dept';

import {
  createAdvancedToggle,
  createHiddenIdField,
  createSectionDivider,
} from '../../common/form-section';
import KeyValueList from '../../common/key-value-list.vue';
import {
  CHANNEL_TYPE,
  CHANNEL_TYPE_OPTIONS,
  HTTP_AUTH_PARAM_KEY_PATTERN,
  HTTP_AUTH_QUERY_PATTERN,
  HTTP_HEADER_SECRET_PATTERN,
  KAFKA_ACKS_OPTIONS,
  KAFKA_SASL_OPTIONS,
  KAFKA_SECURITY_OPTIONS,
  MQ_TYPE,
  MQ_TYPE_OPTIONS,
  PASSWORD_MASK,
  usesSasl,
  usesSsl,
} from './model';

export * from './model';

const httpUrlRules = z
  .string()
  .min(1, '请填写推送地址')
  .refine(
    (url) => /^https?:\/\//i.test(url.trim()),
    '推送地址仅支持 http/https 协议',
  )
  .refine(
    (url) => !HTTP_AUTH_QUERY_PATTERN.test(url.trim()),
    '推送地址 Query 不能包含 Token 等鉴权字段，请放到请求头',
  );

const httpParamsRules = z
  .array(
    z.object({
      key: z.string().optional(),
      value: z.string().optional(),
    }),
  )
  .refine(
    (rows) =>
      !rows.some((item) => HTTP_AUTH_PARAM_KEY_PATTERN.test(item.key ?? '')),
    '查询参数不能包含鉴权字段，请放到请求头',
  );

function formatDatabaseLabel(item: { name?: string; usageType?: number }) {
  return `${item.name ?? ''}${item.usageType === 2 ? '（中台库）' : ''}`;
}

/** 新增/修改的表单 */
export function useFormSchema(): VbenFormSchema[] {
  return [
    createHiddenIdField(),
    createSectionDivider('basicDivider', '基本信息'),
    {
      fieldName: 'name',
      label: '配置名称',
      rules: 'required',
      component: 'Input',
      componentProps: { placeholder: '如：人口数据 HTTP 接口' },
    },
    {
      fieldName: 'orgId',
      label: '所属单位',
      component: 'ApiTreeSelect',
      componentProps: {
        api: async () => handleTree(await getDeptList()),
        labelField: 'name',
        valueField: 'id',
        childrenField: 'children',
        allowClear: true,
        placeholder: '请选择所属单位',
        showSearch: true,
        treeDefaultExpandAll: true,
        treeNodeFilterProp: 'name',
      },
    },
    {
      fieldName: 'channelType',
      label: '通道类型',
      rules: 'selectRequired',
      component: 'RadioGroup',
      formItemClass: 'col-span-2',
      componentProps: {
        optionType: 'button',
        options: CHANNEL_TYPE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
      },
      dependencies: {
        triggerFields: ['id'],
        resolve: ({ values }) => ({ disabled: !!values.id }),
      },
    },
    {
      fieldName: 'description',
      label: '说明',
      component: 'TextArea',
      formItemClass: 'col-span-2',
      componentProps: { placeholder: '配置用途说明', rows: 2 },
    },
    createSectionDivider('httpDivider', 'HTTP 通道', {
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP,
        }),
      },
    }),
    {
      fieldName: 'httpUrl',
      label: '推送地址',
      component: 'Input',
      formItemClass: 'col-span-2',
      help: 'POST 请求体为 { objectName, dataList }，2xx 视为成功。4xx 不重试，超时 / 5xx / 网络错误按次数重试。',
      componentProps: {
        placeholder: 'https://consumer.example.com/push',
        allowClear: true,
      },
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => {
          const show = values.channelType === CHANNEL_TYPE.HTTP;
          return { show, rules: show ? httpUrlRules : null };
        },
      },
    },
    {
      fieldName: 'httpTimeoutMs',
      label: '超时（毫秒）',
      component: 'InputNumber',
      componentProps: {
        min: 1000,
        max: 300_000,
        class: 'w-full',
        placeholder: '默认 30000',
      },
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP,
        }),
      },
    },
    {
      fieldName: 'httpGzip',
      label: '请求体',
      component: 'Checkbox',
      renderComponentContent: () => ({
        default: () => ['gzip 压缩（Content-Encoding: gzip）'],
      }),
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP,
        }),
      },
    },
    createAdvancedToggle('httpAdvanced', '显示证书与重试', {
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP,
        }),
      },
    }),
    {
      fieldName: 'httpRetryTimes',
      label: '重试次数',
      component: 'InputNumber',
      componentProps: {
        min: 0,
        max: 10,
        class: 'w-full',
        placeholder: '默认 2（不含首次）',
      },
      dependencies: {
        triggerFields: ['channelType', 'httpAdvanced'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP && values.httpAdvanced,
        }),
      },
    },
    {
      fieldName: 'httpRetryIntervalMs',
      label: '重试间隔（毫秒）',
      component: 'InputNumber',
      formItemClass: 'col-span-2',
      componentProps: {
        min: 0,
        max: 60_000,
        class: 'w-full',
        placeholder: '默认 1000',
      },
      dependencies: {
        triggerFields: ['channelType', 'httpAdvanced'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP && values.httpAdvanced,
        }),
      },
    },
    {
      fieldName: 'httpTruststorePath',
      label: '信任库路径',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '服务端可读的绝对路径，如 /opt/certs/trust.jks',
      },
      dependencies: {
        triggerFields: ['channelType', 'httpAdvanced'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP && values.httpAdvanced,
        }),
      },
    },
    {
      fieldName: 'httpTruststorePassword',
      label: '信任库密码',
      component: 'InputPassword',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'httpAdvanced'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP && values.httpAdvanced,
        }),
      },
    },
    {
      fieldName: 'httpKeystorePath',
      label: '客户端证书库',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: 'mTLS 客户端证书，JKS / P12',
      },
      dependencies: {
        triggerFields: ['channelType', 'httpAdvanced'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP && values.httpAdvanced,
        }),
      },
    },
    {
      fieldName: 'httpKeystorePassword',
      label: '证书库密码',
      component: 'InputPassword',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'httpAdvanced'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP && values.httpAdvanced,
        }),
      },
    },
    {
      fieldName: 'httpHeaders',
      label: '请求头',
      component: markRaw(KeyValueList),
      formItemClass: 'col-span-2',
      help: '证书文件放在运行 dataforge-server 的机器上，这里只填路径，不上传。',
      componentProps: {
        keyPlaceholder: 'Header 名，如 Authorization',
        valuePlaceholder: 'Header 值',
        addText: '添加请求头',
        emptyText: '尚未配置请求头',
        secretKeyPattern: HTTP_HEADER_SECRET_PATTERN,
      },
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.HTTP,
        }),
      },
    },
    {
      fieldName: 'httpParams',
      label: '查询参数',
      component: markRaw(KeyValueList),
      formItemClass: 'col-span-2',
      help: 'Token / Authorization / api_key 等鉴权字段请放请求头，不能出现在地址或查询参数',
      componentProps: {
        keyPlaceholder: '参数名',
        valuePlaceholder: '参数值',
        addText: '添加查询参数',
        emptyText: '尚未配置查询参数',
      },
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => {
          const show = values.channelType === CHANNEL_TYPE.HTTP;
          return { show, rules: show ? httpParamsRules : null };
        },
      },
    },
    createSectionDivider('dbDivider', '数据库通道', {
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.DATABASE,
        }),
      },
    }),
    {
      fieldName: 'dbDatabaseId',
      label: '目标数据源',
      component: 'ApiSelect',
      componentProps: {
        api: async () => {
          const list = await getDatabaseSimpleList();
          return (list ?? []).map((item) => ({
            label: formatDatabaseLabel(item),
            value: item.id,
          }));
        },
        labelField: 'label',
        valueField: 'value',
        placeholder: '数据写入该数据源',
        showSearch: true,
        optionFilterProp: 'label',
      },
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => {
          const show = values.channelType === CHANNEL_TYPE.DATABASE;
          return { show, rules: show ? 'selectRequired' : null };
        },
      },
    },
    {
      fieldName: 'dbTargetTableName',
      label: '目标表名',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '留空则用任务对象名或源表名',
      },
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.DATABASE,
        }),
      },
    },
    {
      fieldName: 'dbTargetDrop',
      label: '同步选项',
      component: 'Checkbox',
      renderComponentContent: () => ({
        default: () => ['同步前删除重建目标表'],
      }),
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.DATABASE,
        }),
      },
    },
    {
      fieldName: 'dbSyncExist',
      label: ' ',
      component: 'Checkbox',
      renderComponentContent: () => ({
        default: () => ['目标表已存在时继续同步'],
      }),
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.DATABASE,
        }),
      },
    },
    {
      fieldName: 'dbChangeDataSync',
      label: ' ',
      component: 'Checkbox',
      formItemClass: 'col-span-2',
      renderComponentContent: () => ({
        default: () => ['按主键变化量同步'],
      }),
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.DATABASE,
        }),
      },
    },
    createSectionDivider('mqDivider', '消息队列通道', {
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => ({
          show: values.channelType === CHANNEL_TYPE.MQ,
        }),
      },
    }),
    {
      fieldName: 'mqType',
      label: '队列类型',
      component: 'RadioGroup',
      formItemClass: 'col-span-2',
      componentProps: {
        optionType: 'button',
        options: MQ_TYPE_OPTIONS,
      },
      dependencies: {
        triggerFields: ['channelType'],
        resolve: ({ values }) => {
          const show = values.channelType === CHANNEL_TYPE.MQ;
          return { show, rules: show ? 'selectRequired' : null };
        },
      },
    },
    {
      fieldName: 'kafkaBootstrapServers',
      label: 'Kafka 服务地址',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '127.0.0.1:9092，多个逗号分隔',
      },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'kafkaTopic',
      label: 'Topic',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: 'data-push' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'kafkaSecurityProtocol',
      label: '安全协议',
      component: 'Select',
      componentProps: { options: KAFKA_SECURITY_OPTIONS, class: 'w-full' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA,
        }),
      },
    },
    {
      fieldName: 'kafkaAcks',
      label: 'acks',
      component: 'Select',
      componentProps: { options: KAFKA_ACKS_OPTIONS, class: 'w-full' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA,
        }),
      },
    },
    {
      fieldName: 'kafkaSaslMechanism',
      label: 'SASL 机制',
      component: 'Select',
      componentProps: { options: KAFKA_SASL_OPTIONS, class: 'w-full' },
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSasl(values.kafkaSecurityProtocol as string);
          return { show, rules: show ? 'selectRequired' : null };
        },
      },
    },
    {
      fieldName: 'kafkaSaslUsername',
      label: 'SASL 用户名',
      component: 'Input',
      componentProps: { allowClear: true },
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSasl(values.kafkaSecurityProtocol as string);
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'kafkaSaslPassword',
      label: 'SASL 密码',
      component: 'InputPassword',
      formItemClass: 'col-span-2',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSasl(values.kafkaSecurityProtocol as string);
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'kafkaTruststorePath',
      label: '信任库路径',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '服务端可读的绝对路径',
      },
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSsl(values.kafkaSecurityProtocol as string),
        }),
      },
    },
    {
      fieldName: 'kafkaTruststorePassword',
      label: '信任库密码',
      component: 'InputPassword',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSsl(values.kafkaSecurityProtocol as string),
        }),
      },
    },
    {
      fieldName: 'kafkaKeystorePath',
      label: '客户端证书库',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '可选，双向 TLS' },
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSsl(values.kafkaSecurityProtocol as string),
        }),
      },
    },
    {
      fieldName: 'kafkaKeystorePassword',
      label: '证书库密码',
      component: 'InputPassword',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSsl(values.kafkaSecurityProtocol as string),
        }),
      },
    },
    {
      fieldName: 'kafkaDisableHostnameVerification',
      label: '主机名校验',
      component: 'Checkbox',
      formItemClass: 'col-span-2',
      renderComponentContent: () => ({
        default: () => ['关闭主机名校验（仅内网自签证书）'],
      }),
      dependencies: {
        triggerFields: ['channelType', 'mqType', 'kafkaSecurityProtocol'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.KAFKA &&
            usesSsl(values.kafkaSecurityProtocol as string),
        }),
      },
    },
    {
      fieldName: 'rabbitHost',
      label: '主机',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '127.0.0.1' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'rabbitPort',
      label: '端口',
      component: 'InputNumber',
      componentProps: { min: 1, max: 65_535, class: 'w-full' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'rabbitUsername',
      label: '用户名',
      component: 'Input',
      componentProps: { allowClear: true },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
    {
      fieldName: 'rabbitPassword',
      label: '密码',
      component: 'InputPassword',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
    {
      fieldName: 'rabbitVirtualHost',
      label: '虚拟主机',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '/' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
    {
      fieldName: 'rabbitExchange',
      label: '交换机',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: 'data.push' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'rabbitRoutingKey',
      label: '路由键',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: 'person' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => {
          const show =
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ;
          return { show, rules: show ? 'required' : null };
        },
      },
    },
    {
      fieldName: 'rabbitUseSsl',
      label: 'TLS',
      component: 'Checkbox',
      renderComponentContent: () => ({
        default: () => ['启用 SSL/TLS（默认端口常为 5671）'],
      }),
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
    {
      fieldName: 'rabbitTruststorePath',
      label: '信任库路径',
      component: 'Input',
      componentProps: {
        allowClear: true,
        placeholder: '可选，企业 CA / 自签',
      },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
    {
      fieldName: 'rabbitTruststorePassword',
      label: '信任库密码',
      component: 'InputPassword',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
    {
      fieldName: 'rabbitKeystorePath',
      label: '客户端证书库',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '可选，mTLS' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
    {
      fieldName: 'rabbitKeystorePassword',
      label: '证书库密码',
      component: 'InputPassword',
      help: `回显 ${PASSWORD_MASK} 表示保持不变`,
      componentProps: { autocomplete: 'new-password' },
      dependencies: {
        triggerFields: ['channelType', 'mqType'],
        resolve: ({ values }) => ({
          show:
            values.channelType === CHANNEL_TYPE.MQ &&
            values.mqType === MQ_TYPE.RABBITMQ,
        }),
      },
    },
  ];
}

export function useGridFormSchema(): VbenFormSchema[] {
  return [
    {
      fieldName: 'name',
      label: '配置名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入配置名称' },
    },
    {
      fieldName: 'channelType',
      label: '通道类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: CHANNEL_TYPE_OPTIONS.map(({ label, value }) => ({
          label,
          value,
        })),
        placeholder: '请选择通道类型',
      },
    },
  ];
}

export function useGridColumns(): VxeTableGridOptions<DataforgeDataPushConfigApi.Config>['columns'] {
  return [
    { field: 'id', title: '编号', width: 70 },
    { field: 'name', title: '配置名称', minWidth: 160 },
    {
      field: 'channelType',
      title: '通道类型',
      width: 110,
      slots: { default: 'channelType' },
    },
    {
      field: 'locked',
      title: '编辑',
      width: 90,
      slots: { default: 'locked' },
    },
    {
      field: 'orgName',
      title: '所属单位',
      width: 140,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'description',
      title: '说明',
      minWidth: 160,
      align: 'left',
      showOverflow: false,
      slots: { default: 'description' },
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 160,
      formatter: 'formatDateTime',
    },
    {
      title: '操作',
      width: 160,
      fixed: 'right',
      showOverflow: false,
      className: 'datapush-action-column',
      slots: { default: 'actions' },
    },
  ];
}

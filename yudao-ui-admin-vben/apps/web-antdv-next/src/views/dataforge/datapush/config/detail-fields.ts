import type { DataforgeDataPushConfigApi } from '#/api/dataforge/datapush/config';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { formatDateTime } from '@vben/utils';

import {
  CHANNEL_TYPE,
  CHANNEL_TYPE_OPTIONS,
  HTTP_HEADER_SECRET_PATTERN,
  maskSecret,
  MQ_TYPE,
  MQ_TYPE_OPTIONS,
  optionLabel,
  usesSasl,
  usesSsl,
} from './model';

function formatPairs(
  rows?: DataforgeDataPushConfigApi.KeyValue[],
  secret = false,
) {
  if (!rows?.length) {
    return '-';
  }
  return rows
    .map((item) => {
      const key = item.key ?? '';
      const value =
        secret && HTTP_HEADER_SECRET_PATTERN.test(key)
          ? maskSecret(item.value)
          : (item.value ?? '');
      return `${key}=${value}`;
    })
    .join('\n');
}

export function getConfigDetailFields(
  row: DataforgeDataPushConfigApi.Config,
): DetailFieldItem[] {
  const http = row.config?.http ?? {};
  const db = row.config?.db ?? {};
  const mq = row.config?.mq ?? {};
  const kafka = mq.kafka ?? {};
  const rabbit = mq.rabbit ?? {};
  const items: DetailFieldItem[] = [
    { key: 'id', label: '编号', value: row.id },
    { key: 'name', label: '配置名称', value: row.name },
    {
      key: 'channelType',
      label: '通道类型',
      value: optionLabel(CHANNEL_TYPE_OPTIONS, row.channelType),
    },
    { key: 'orgName', label: '所属单位', value: row.orgName },
    {
      key: 'locked',
      label: '编辑',
      value: row.locked ? '已锁定' : '可编辑',
    },
    {
      key: 'description',
      label: '说明',
      value: row.description,
      span: 2,
      type: 'textarea',
      rows: 2,
    },
  ];

  if (row.channelType === CHANNEL_TYPE.HTTP) {
    items.push(
      { key: 'httpUrl', label: '推送地址', value: http.url, span: 2 },
      { key: 'httpTimeoutMs', label: '超时（毫秒）', value: http.timeoutMs },
      { key: 'httpRetryTimes', label: '重试次数', value: http.retryTimes },
      {
        key: 'httpRetryIntervalMs',
        label: '重试间隔（毫秒）',
        value: http.retryIntervalMs,
      },
      { key: 'httpGzip', label: 'gzip 压缩', value: http.gzip ? '是' : '否' },
      {
        key: 'httpTruststorePath',
        label: '信任库路径',
        value: http.truststorePath,
      },
      {
        key: 'httpTruststorePassword',
        label: '信任库密码',
        value: maskSecret(http.truststorePassword),
      },
      {
        key: 'httpKeystorePath',
        label: '客户端证书库',
        value: http.keystorePath,
      },
      {
        key: 'httpKeystorePassword',
        label: '证书库密码',
        value: maskSecret(http.keystorePassword),
      },
      {
        key: 'httpHeaders',
        label: '请求头',
        value: formatPairs(http.headers, true),
        span: 2,
        type: 'textarea',
        rows: Math.min(Math.max(http.headers?.length ?? 1, 2), 6),
      },
      {
        key: 'httpParams',
        label: '查询参数',
        value: formatPairs(http.params),
        span: 2,
        type: 'textarea',
        rows: Math.min(Math.max(http.params?.length ?? 1, 2), 6),
      },
    );
  }

  if (row.channelType === CHANNEL_TYPE.DATABASE) {
    items.push(
      { key: 'dbDatabaseId', label: '目标数据源编号', value: db.databaseId },
      {
        key: 'dbTargetTableName',
        label: '目标表名',
        value: db.targetTableName,
      },
      {
        key: 'dbTargetDrop',
        label: '删除重建目标表',
        value: db.targetDrop ? '是' : '否',
      },
      {
        key: 'dbSyncExist',
        label: '表已存在继续同步',
        value: db.syncExist ? '是' : '否',
      },
      {
        key: 'dbChangeDataSync',
        label: '按主键变化量同步',
        value: db.changeDataSync ? '是' : '否',
      },
    );
  }

  if (row.channelType === CHANNEL_TYPE.MQ) {
    items.push({
      key: 'mqType',
      label: '队列类型',
      value: optionLabel(MQ_TYPE_OPTIONS, mq.mqType),
    });
    if (mq.mqType === MQ_TYPE.KAFKA) {
      items.push(
        {
          key: 'kafkaBootstrapServers',
          label: 'Kafka 服务地址',
          value: kafka.bootstrapServers,
          span: 2,
        },
        { key: 'kafkaTopic', label: 'Topic', value: kafka.topic },
        {
          key: 'kafkaSecurityProtocol',
          label: '安全协议',
          value: kafka.securityProtocol,
        },
        { key: 'kafkaAcks', label: 'acks', value: kafka.acks },
      );
      if (usesSasl(kafka.securityProtocol)) {
        items.push(
          {
            key: 'kafkaSaslMechanism',
            label: 'SASL 机制',
            value: kafka.saslMechanism,
          },
          {
            key: 'kafkaSaslUsername',
            label: 'SASL 用户名',
            value: kafka.saslUsername,
          },
          {
            key: 'kafkaSaslPassword',
            label: 'SASL 密码',
            value: maskSecret(kafka.saslPassword),
          },
        );
      }
      if (usesSsl(kafka.securityProtocol)) {
        items.push(
          {
            key: 'kafkaTruststorePath',
            label: '信任库路径',
            value: kafka.truststorePath,
          },
          {
            key: 'kafkaTruststorePassword',
            label: '信任库密码',
            value: maskSecret(kafka.truststorePassword),
          },
          {
            key: 'kafkaKeystorePath',
            label: '客户端证书库',
            value: kafka.keystorePath,
          },
          {
            key: 'kafkaKeystorePassword',
            label: '证书库密码',
            value: maskSecret(kafka.keystorePassword),
          },
          {
            key: 'kafkaDisableHostnameVerification',
            label: '关闭主机名校验',
            value: kafka.disableHostnameVerification ? '是' : '否',
          },
        );
      }
    }
    if (mq.mqType === MQ_TYPE.RABBITMQ) {
      items.push(
        { key: 'rabbitHost', label: '主机', value: rabbit.host },
        { key: 'rabbitPort', label: '端口', value: rabbit.port },
        { key: 'rabbitUsername', label: '用户名', value: rabbit.username },
        {
          key: 'rabbitPassword',
          label: '密码',
          value: maskSecret(rabbit.password),
        },
        {
          key: 'rabbitVirtualHost',
          label: '虚拟主机',
          value: rabbit.virtualHost,
        },
        { key: 'rabbitExchange', label: '交换机', value: rabbit.exchange },
        { key: 'rabbitRoutingKey', label: '路由键', value: rabbit.routingKey },
        {
          key: 'rabbitUseSsl',
          label: 'TLS',
          value: rabbit.useSsl ? '是' : '否',
        },
        {
          key: 'rabbitTruststorePath',
          label: '信任库路径',
          value: rabbit.truststorePath,
        },
        {
          key: 'rabbitTruststorePassword',
          label: '信任库密码',
          value: maskSecret(rabbit.truststorePassword),
        },
        {
          key: 'rabbitKeystorePath',
          label: '客户端证书库',
          value: rabbit.keystorePath,
        },
        {
          key: 'rabbitKeystorePassword',
          label: '证书库密码',
          value: maskSecret(rabbit.keystorePassword),
        },
      );
    }
  }

  items.push({
    key: 'createTime',
    label: '创建时间',
    value: formatDateTime(row.createTime),
  });
  return items;
}

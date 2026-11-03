import type { DataforgeDataPushConfigApi } from '#/api/dataforge/datapush/config';

/** 通道类型：1-HTTP 2-数据库 3-消息队列 */
export const CHANNEL_TYPE = {
  HTTP: 1,
  DATABASE: 2,
  MQ: 3,
} as const;

export const CHANNEL_TYPE_OPTIONS = [
  { label: 'HTTP', value: CHANNEL_TYPE.HTTP, color: 'processing' },
  { label: '数据库', value: CHANNEL_TYPE.DATABASE, color: 'success' },
  { label: '消息队列', value: CHANNEL_TYPE.MQ, color: 'warning' },
];

export const MQ_TYPE = {
  KAFKA: 'kafka',
  RABBITMQ: 'rabbitmq',
} as const;

export const MQ_TYPE_OPTIONS = [
  { label: 'Kafka', value: MQ_TYPE.KAFKA },
  { label: 'RabbitMQ', value: MQ_TYPE.RABBITMQ },
];

export const KAFKA_SECURITY_OPTIONS = [
  { label: 'PLAINTEXT', value: 'PLAINTEXT' },
  { label: 'SASL_PLAINTEXT', value: 'SASL_PLAINTEXT' },
  { label: 'SASL_SSL', value: 'SASL_SSL' },
  { label: 'SSL', value: 'SSL' },
];

export const KAFKA_ACKS_OPTIONS = [
  { label: 'all（全部 ISR，幂等重试，推荐）', value: 'all' },
  { label: '1（仅 leader 确认，不重试，旧配置兼容）', value: '1' },
];

export const KAFKA_SASL_OPTIONS = [
  { label: 'PLAIN', value: 'PLAIN' },
  { label: 'SCRAM-SHA-256', value: 'SCRAM-SHA-256' },
  { label: 'SCRAM-SHA-512', value: 'SCRAM-SHA-512' },
];

function normalizeKafkaAcks(value?: string) {
  const acks = value?.trim();
  // 旧 0/未知值保留回显，由后端拒绝；不得静默改变其语义。
  return !acks || acks === '-1' ? 'all' : acks;
}

/** 密码掩码：后端回显固定值，保存时原样回传表示保持不变 */
export const PASSWORD_MASK = '******';

export const HTTP_HEADER_SECRET_PATTERN =
  /authorization|token|secret|api[_-]?key/i;

export const HTTP_AUTH_QUERY_PATTERN =
  /(^|[?&])(token|access_token|authorization|api_key|apikey|secret)=/i;

export const HTTP_AUTH_PARAM_KEY_PATTERN =
  /token|secret|password|authorization|api[_-]?key/i;

export function optionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

export function optionColor(
  options: { color?: string; label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.color ?? 'default';
}

export function usesSasl(protocol?: string) {
  return !!protocol && protocol.includes('SASL');
}

export function usesSsl(protocol?: string) {
  return !!protocol && protocol.includes('SSL');
}

export function maskSecret(value?: null | string) {
  return value ? PASSWORD_MASK : '-';
}

/**
 * 表单模型（扁平）：把后端 config.http / config.db / config.mq 展平成单层字段，
 * 便于 useVbenForm 做校验与联动；提交时再由 toSaveReq 装配回嵌套结构。
 */
export interface ConfigFormValues {
  channelType?: number;
  dbChangeDataSync?: boolean;
  dbDatabaseId?: number | string;
  dbSyncExist?: boolean;
  dbTargetDrop?: boolean;
  dbTargetTableName?: string;
  description?: string;
  httpAdvanced?: boolean;
  httpGzip?: boolean;
  httpHeaders?: DataforgeDataPushConfigApi.KeyValue[];
  httpKeystorePassword?: string;
  httpKeystorePath?: string;
  httpParams?: DataforgeDataPushConfigApi.KeyValue[];
  httpRetryIntervalMs?: number;
  httpRetryTimes?: number;
  httpTimeoutMs?: number;
  httpTruststorePassword?: string;
  httpTruststorePath?: string;
  httpUrl?: string;
  id?: number | string;
  kafkaAcks?: string;
  kafkaBootstrapServers?: string;
  kafkaDisableHostnameVerification?: boolean;
  kafkaKeystorePassword?: string;
  kafkaKeystorePath?: string;
  kafkaSaslMechanism?: string;
  kafkaSaslPassword?: string;
  kafkaSaslUsername?: string;
  kafkaSecurityProtocol?: string;
  kafkaTopic?: string;
  kafkaTruststorePassword?: string;
  kafkaTruststorePath?: string;
  mqType?: string;
  name?: string;
  orgId?: number | string;
  rabbitExchange?: string;
  rabbitHost?: string;
  rabbitKeystorePassword?: string;
  rabbitKeystorePath?: string;
  rabbitPassword?: string;
  rabbitPort?: number;
  rabbitRoutingKey?: string;
  rabbitTruststorePassword?: string;
  rabbitTruststorePath?: string;
  rabbitUsername?: string;
  rabbitUseSsl?: boolean;
  rabbitVirtualHost?: string;
}

export function getDefaultFormValues(): ConfigFormValues {
  return {
    channelType: CHANNEL_TYPE.HTTP,
    dbTargetDrop: false,
    dbSyncExist: true,
    dbChangeDataSync: true,
    httpAdvanced: false,
    httpHeaders: [],
    httpParams: [],
    mqType: MQ_TYPE.KAFKA,
    kafkaSecurityProtocol: 'PLAINTEXT',
    kafkaSaslMechanism: 'PLAIN',
    kafkaAcks: 'all',
    rabbitPort: 5672,
    rabbitVirtualHost: '/',
    rabbitUseSsl: false,
  };
}

export function toFormValues(
  config: DataforgeDataPushConfigApi.Config,
): ConfigFormValues {
  const http = config.config?.http ?? {};
  const db = config.config?.db ?? {};
  const mq = config.config?.mq ?? {};
  const kafka = mq.kafka ?? {};
  const rabbit = mq.rabbit ?? {};
  return {
    ...getDefaultFormValues(),
    id: config.id,
    name: config.name,
    channelType: config.channelType ?? CHANNEL_TYPE.HTTP,
    orgId: config.orgId,
    description: config.description,
    httpUrl: http.url,
    httpAdvanced: !!(
      http.retryTimes ||
      http.retryIntervalMs ||
      http.truststorePath ||
      http.keystorePath
    ),
    httpHeaders: http.headers ?? [],
    httpParams: http.params ?? [],
    httpTimeoutMs: http.timeoutMs,
    httpRetryTimes: http.retryTimes,
    httpRetryIntervalMs: http.retryIntervalMs,
    httpGzip: http.gzip,
    httpTruststorePath: http.truststorePath,
    httpTruststorePassword: http.truststorePassword,
    httpKeystorePath: http.keystorePath,
    httpKeystorePassword: http.keystorePassword,
    dbDatabaseId: db.databaseId,
    dbTargetTableName: db.targetTableName,
    dbTargetDrop: db.targetDrop ?? false,
    dbSyncExist: db.syncExist ?? true,
    dbChangeDataSync: db.changeDataSync ?? true,
    mqType: mq.mqType ?? MQ_TYPE.KAFKA,
    kafkaBootstrapServers: kafka.bootstrapServers,
    kafkaTopic: kafka.topic,
    kafkaSecurityProtocol: kafka.securityProtocol ?? 'PLAINTEXT',
    kafkaSaslMechanism: kafka.saslMechanism ?? 'PLAIN',
    kafkaSaslUsername: kafka.saslUsername,
    kafkaSaslPassword: kafka.saslPassword,
    kafkaAcks: normalizeKafkaAcks(kafka.acks),
    kafkaTruststorePath: kafka.truststorePath,
    kafkaTruststorePassword: kafka.truststorePassword,
    kafkaKeystorePath: kafka.keystorePath,
    kafkaKeystorePassword: kafka.keystorePassword,
    kafkaDisableHostnameVerification: kafka.disableHostnameVerification,
    rabbitHost: rabbit.host,
    rabbitPort: rabbit.port ?? 5672,
    rabbitUsername: rabbit.username,
    rabbitPassword: rabbit.password,
    rabbitVirtualHost: rabbit.virtualHost ?? '/',
    rabbitExchange: rabbit.exchange,
    rabbitRoutingKey: rabbit.routingKey,
    rabbitUseSsl: rabbit.useSsl ?? false,
    rabbitTruststorePath: rabbit.truststorePath,
    rabbitTruststorePassword: rabbit.truststorePassword,
    rabbitKeystorePath: rabbit.keystorePath,
    rabbitKeystorePassword: rabbit.keystorePassword,
  };
}

function trimToUndefined(value?: string) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function keepSecret(value?: string) {
  return value || undefined;
}

function filterPairs(rows?: DataforgeDataPushConfigApi.KeyValue[]) {
  return (rows ?? []).filter((item) => item.key?.trim());
}

/**
 * 表单值 → 保存请求。
 * 按当前通道类型裁剪无关字段，避免切换类型后把残留配置提交给后端。
 * 密码字段原样回传（含 ******），由后端 DataPushSecretMasker 决定是否保持不变。
 */
export function toSaveReq(
  values: ConfigFormValues,
): DataforgeDataPushConfigApi.Config {
  const channelType = values.channelType ?? CHANNEL_TYPE.HTTP;
  const config: DataforgeDataPushConfigApi.ChannelConfig = {};

  if (channelType === CHANNEL_TYPE.HTTP) {
    config.http = {
      url: trimToUndefined(values.httpUrl),
      headers: filterPairs(values.httpHeaders),
      params: filterPairs(values.httpParams),
      timeoutMs: values.httpTimeoutMs,
      retryTimes: values.httpRetryTimes,
      retryIntervalMs: values.httpRetryIntervalMs,
      gzip: values.httpGzip,
      truststorePath: trimToUndefined(values.httpTruststorePath),
      truststorePassword: keepSecret(values.httpTruststorePassword),
      keystorePath: trimToUndefined(values.httpKeystorePath),
      keystorePassword: keepSecret(values.httpKeystorePassword),
    };
  } else if (channelType === CHANNEL_TYPE.DATABASE) {
    config.db = {
      databaseId: values.dbDatabaseId,
      targetTableName: trimToUndefined(values.dbTargetTableName),
      targetDrop: values.dbTargetDrop ?? false,
      syncExist: values.dbSyncExist ?? true,
      changeDataSync: values.dbChangeDataSync ?? true,
    };
  } else {
    const mqType = values.mqType ?? MQ_TYPE.KAFKA;
    config.mq = {
      mqType,
      kafka:
        mqType === MQ_TYPE.KAFKA
          ? {
              bootstrapServers: trimToUndefined(values.kafkaBootstrapServers),
              topic: trimToUndefined(values.kafkaTopic),
              securityProtocol: values.kafkaSecurityProtocol ?? 'PLAINTEXT',
              saslMechanism: values.kafkaSaslMechanism ?? 'PLAIN',
              saslUsername: trimToUndefined(values.kafkaSaslUsername),
              saslPassword: keepSecret(values.kafkaSaslPassword),
              acks: normalizeKafkaAcks(values.kafkaAcks),
              truststorePath: trimToUndefined(values.kafkaTruststorePath),
              truststorePassword: keepSecret(values.kafkaTruststorePassword),
              keystorePath: trimToUndefined(values.kafkaKeystorePath),
              keystorePassword: keepSecret(values.kafkaKeystorePassword),
              disableHostnameVerification:
                values.kafkaDisableHostnameVerification,
            }
          : undefined,
      rabbit:
        mqType === MQ_TYPE.RABBITMQ
          ? {
              host: trimToUndefined(values.rabbitHost),
              port: values.rabbitPort,
              username: trimToUndefined(values.rabbitUsername),
              password: keepSecret(values.rabbitPassword),
              virtualHost: trimToUndefined(values.rabbitVirtualHost) ?? '/',
              exchange: trimToUndefined(values.rabbitExchange),
              routingKey: trimToUndefined(values.rabbitRoutingKey),
              useSsl: values.rabbitUseSsl ?? false,
              truststorePath: trimToUndefined(values.rabbitTruststorePath),
              truststorePassword: keepSecret(values.rabbitTruststorePassword),
              keystorePath: trimToUndefined(values.rabbitKeystorePath),
              keystorePassword: keepSecret(values.rabbitKeystorePassword),
            }
          : undefined,
    };
  }

  return {
    id: values.id,
    name: trimToUndefined(values.name),
    channelType,
    description: trimToUndefined(values.description),
    orgId: values.orgId,
    config,
  };
}

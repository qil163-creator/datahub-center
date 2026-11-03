import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace DataforgeDataPushConfigApi {
  export interface KeyValue {
    key?: string;
    value?: string;
  }

  export interface HttpConfig {
    url?: string;
    headers?: KeyValue[];
    params?: KeyValue[];
    timeoutMs?: number;
    retryTimes?: number;
    retryIntervalMs?: number;
    gzip?: boolean;
    truststorePath?: string;
    truststorePassword?: string;
    keystorePath?: string;
    keystorePassword?: string;
  }

  export interface DbConfig {
    databaseId?: number | string;
    targetTableName?: string;
    targetDrop?: boolean;
    syncExist?: boolean;
    changeDataSync?: boolean;
  }

  export interface KafkaConfig {
    bootstrapServers?: string;
    topic?: string;
    securityProtocol?: string;
    saslMechanism?: string;
    saslUsername?: string;
    saslPassword?: string;
    acks?: string;
    truststorePath?: string;
    truststorePassword?: string;
    keystorePath?: string;
    keystorePassword?: string;
    disableHostnameVerification?: boolean;
  }

  export interface RabbitConfig {
    host?: string;
    port?: number;
    username?: string;
    password?: string;
    virtualHost?: string;
    exchange?: string;
    routingKey?: string;
    useSsl?: boolean;
    truststorePath?: string;
    truststorePassword?: string;
    keystorePath?: string;
    keystorePassword?: string;
  }

  export interface MqConfig {
    mqType?: string;
    kafka?: KafkaConfig;
    rabbit?: RabbitConfig;
  }

  export interface ChannelConfig {
    http?: HttpConfig;
    db?: DbConfig;
    mq?: MqConfig;
  }

  export interface Config {
    id?: number | string;
    name?: string;
    channelType?: number;
    config?: ChannelConfig;
    description?: string;
    orgId?: number | string;
    orgName?: string;
    createTime?: number | string;
    locked?: boolean;
  }

  export interface ConfigPageReqVO extends PageParam {
    name?: string;
    channelType?: number;
    orgId?: number | string;
  }
}

export function getDataPushConfigPage(
  params: DataforgeDataPushConfigApi.ConfigPageReqVO,
) {
  return requestClient.get<PageResult<DataforgeDataPushConfigApi.Config>>(
    '/dataforge/data-push-config/page',
    { params },
  );
}

export function getDataPushConfig(id: number | string) {
  return requestClient.get<DataforgeDataPushConfigApi.Config>(
    '/dataforge/data-push-config/get',
    { params: { id } },
  );
}

export function getDataPushConfigSimpleList() {
  return requestClient.get<DataforgeDataPushConfigApi.Config[]>(
    '/dataforge/data-push-config/simple-list',
  );
}

export function createDataPushConfig(data: DataforgeDataPushConfigApi.Config) {
  return requestClient.post<number | string>(
    '/dataforge/data-push-config/create',
    data,
  );
}

export function updateDataPushConfig(data: DataforgeDataPushConfigApi.Config) {
  return requestClient.put<boolean>('/dataforge/data-push-config/update', data);
}

export function deleteDataPushConfig(id: number | string) {
  return requestClient.delete<boolean>('/dataforge/data-push-config/delete', {
    params: { id },
  });
}

export function testDataPushConfigConnection(
  data: DataforgeDataPushConfigApi.Config,
) {
  return requestClient.post<boolean>(
    '/dataforge/data-push-config/test-connection',
    data,
  );
}

export function copyDataPushConfig(id: number | string) {
  return requestClient.post<number | string>(
    `/dataforge/data-push-config/copy?id=${id}`,
  );
}

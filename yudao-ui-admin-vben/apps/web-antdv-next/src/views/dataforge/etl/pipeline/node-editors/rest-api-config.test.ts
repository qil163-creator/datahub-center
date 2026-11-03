import { describe, expect, it } from 'vitest';

import { defaultRestApiNodeConfig } from './rest-api-config';

describe('etl 内嵌接口默认配置', () => {
  it('每个节点独立保存参数与字段，不共享接口资源或可变数组', () => {
    const first = defaultRestApiNodeConfig('节点一');
    const second = defaultRestApiNodeConfig('节点二');
    first.apiConfig.requestHeader!.push({
      name: 'Accept',
      value: 'application/json',
    });
    first.apiConfig.dataColumns!.push({ fieldName: 'id', fieldType: 4 });
    expect(second).toMatchObject({
      fetchSize: 500,
      apiConfig: {
        apiName: '节点二',
        requestMethod: 'GET',
        requestHeader: [{ name: 'Accept', value: '*/*' }],
        dataColumns: [],
        dataKeys: ['$.data.rows'],
      },
    });
    expect(first).not.toHaveProperty('apiId');
    expect(first.apiConfig).not.toHaveProperty('id');
  });
});

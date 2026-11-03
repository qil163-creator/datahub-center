import { describe, expect, it } from 'vitest';

import { validateNativeScript } from './native-script-schema';

const offline = { engine: 'SPARK', mode: 'OFFLINE', toolCode: 'java-script' };
const batch = { resourceBindingId: 'script.resource', code: 'public class EtlScript {}', workerState: 'BATCH', batchPolicy: 'PARTITION', maximumRows: 1000 };

describe('原生脚本显式批次合同', () => {
  it('明确声明离线完整分区和实时有限窗口，不自动替换历史批次参数', () => {
    expect(validateNativeScript(batch, offline)).toEqual([]);
    expect(validateNativeScript({ ...batch, batchSize: 1000 }, offline)).toContain('请清除历史批次行数，再明确设置最大批次行数');
    const live = { engine: 'FLINK', mode: 'REALTIME', toolCode: 'java-script' };
    expect(validateNativeScript(batch, live)).toHaveLength(2);
    expect(validateNativeScript({ ...batch, batchPolicy: 'COUNT_OR_TIME', flushMillis: 100 }, live)).toEqual([]);
    expect(validateNativeScript({ ...batch, workerState: undefined, maximumRows: 10_001 }, offline)).toHaveLength(2);
    expect(validateNativeScript({ ...batch, flushMillis: 100 }, offline)).toContain('请移除离线脚本不适用的等待时间');
    expect(validateNativeScript(batch, { toolCode: 'java-script' })).toHaveLength(2);
  });
  it('输出可继承输入，显式字段验证无损精度、重复名称和当前引擎边界', () => {
    const fields = [{ columnName: '编号', columnType: 'BIGINT', nullable: false }, { columnName: '金额', columnType: 'DECIMAL(30,4)' }, { columnName: '时间', columnType: 'TIMESTAMP(6)' }];
    expect(validateNativeScript({ ...batch, outputFields: fields }, offline)).toEqual([]);
    expect(validateNativeScript({ ...batch, outputFields: [{ name: 'id', type: 'LONG' }] }, offline)).toEqual([]);
    expect(validateNativeScript({ ...batch, outputFields: [{ columnName: 'n', columnType: 'DECIMAL(2,3)' }, { columnName: 'n', columnType: 'TIMESTAMP(9)' }] }, offline)).toHaveLength(3);
    expect(validateNativeScript({ ...batch, outputFields: fields.map((field) => ({ ...field, columnType: 'TIMESTAMP(9)' })) }, { ...offline, engine: 'FLINK' })).toEqual([]);
  });
  it('认证节点只保存变量编号，不把令牌字段合同当输出', () => {
    const token = { ...offline, toolCode: 'custom-token' };
    expect(validateNativeScript(batch, token)).toContain('请填写认证变量编号');
    expect(validateNativeScript({ ...batch, tokenKey: 'service.token' }, token)).toEqual([]);
    expect(validateNativeScript({ ...batch, tokenKey: 'service.token', outputFields: [{ columnName: 'token', columnType: 'STRING' }] }, token)).toContain('请清除初始化认证脚本不适用的输出字段');
  });
});

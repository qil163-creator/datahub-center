import { describe, expect, it, vi } from 'vitest';

import { useFormSchema } from './data';

const state = vi.hoisted(() => ({
  protocols: [] as { label: string; value: string }[],
}));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => state.protocols }));
vi.mock('#/components/connection-icon/connection-icon-upload.vue', () => ({
  default: {},
}));

describe('连接器协议维护', () => {
  it('原生协议不强制 JDBC URL 和测试 SQL，切回 JDBC 恢复必填', () => {
    for (const name of ['urlTemplates', 'validationQuery']) {
      const field = useFormSchema().find((item) => item.fieldName === name)!;
      const resolve = field.dependencies!.resolve!;
      expect(
        resolve({ values: { protocolType: 'S3' } } as unknown as Parameters<
          typeof resolve
        >[0]),
      ).toEqual({ rules: null });
      expect(
        resolve({ values: { protocolType: 'JDBC' } } as unknown as Parameters<
          typeof resolve
        >[0]),
      ).toEqual({ rules: 'required' });
    }
  });
  it('延迟加载的协议字典可更新标签，缺项保留与后端一致的协议值', () => {
    const field = useFormSchema().find(
      (item) => item.fieldName === 'protocolType',
    )!;
    const props = field.componentProps as () => {
      options: { label: string; value: string }[];
    };
    expect(props().options).toContainEqual({ label: 'SFTP', value: 'SFTP' });
    state.protocols = [{ label: '安全文件传输', value: 'SFTP' }];
    expect(props().options.filter((item) => item.value === 'SFTP')).toEqual([
      { label: '安全文件传输', value: 'SFTP' },
    ]);
    expect(props().options).toContainEqual({ label: 'BOLT', value: 'BOLT' });
  });
});

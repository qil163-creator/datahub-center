import { describe, expect, it, vi } from 'vitest';

import { getConfigDetailFields } from './detail-fields';
import { CHANNEL_TYPE } from './model';

vi.mock('@vben/utils', () => ({ formatDateTime: () => '' }));

describe('完整任务详情的接收端展示', () => {
  it('展示 HTTP 请求头和证书密码时保持脱敏，普通参数正常展示', () => {
    const fields = getConfigDetailFields({
      channelType: CHANNEL_TYPE.HTTP,
      config: {
        http: {
          url: 'https://consumer.example.com/push',
          headers: [
            { key: 'Authorization', value: 'fixture-authorization' },
            { key: 'X-API-Key', value: 'fixture-key' },
            { key: 'Content-Type', value: 'application/json' },
          ],
          keystorePassword: 'fixture-keystore',
        },
      },
    });
    expect(fields.find((field) => field.key === 'httpHeaders')?.value).toBe(
      'Authorization=******\nX-API-Key=******\nContent-Type=application/json',
    );
    expect(
      fields.find((field) => field.key === 'httpKeystorePassword')?.value,
    ).toBe('******');
    expect(JSON.stringify(fields)).not.toContain('fixture-');
  });

  it('展示 Kafka 相应安全配置并隐藏凭据', () => {
    const fields = getConfigDetailFields({
      channelType: CHANNEL_TYPE.MQ,
      config: {
        mq: {
          mqType: 'kafka',
          kafka: {
            topic: 'customer',
            securityProtocol: 'SASL_SSL',
            saslPassword: 'fixture-password',
            truststorePassword: 'fixture-truststore',
          },
        },
      },
    });
    expect(fields.find((field) => field.key === 'kafkaTopic')?.value).toBe(
      'customer',
    );
    expect(
      fields.find((field) => field.key === 'kafkaSaslPassword')?.value,
    ).toBe('******');
    expect(JSON.stringify(fields)).not.toContain('fixture-');
    expect(fields.some((field) => field.key === 'rabbitHost')).toBe(false);
  });
});

import { describe, expect, it } from 'vitest';

import {
  emptyIndicatorConfig,
  indicatorConfigError,
  parseIndicatorConfig,
  referencedParams,
  serializeIndicatorConfig,
} from './indicator-config';

describe('indicator api config', () => {
  it('round-trips without touching id precision', () => {
    const config = {
      ...emptyIndicatorConfig(),
      indicatorIds: ['9007199254740993'],
      dimensions: ['uid_region'],
      startParam: 'startDate',
      endParam: 'endDate',
      filters: [
        { param: 'region', fieldUid: 'uid_region' },
        { param: '', fieldUid: '' },
      ],
    };
    const json = serializeIndicatorConfig(config);
    expect(json).toContain('"9007199254740993"');
    expect(JSON.parse(json).filters).toHaveLength(1);
    expect(parseIndicatorConfig(json).indicatorIds).toEqual([
      '9007199254740993',
    ]);
    expect(parseIndicatorConfig('broken').indicatorIds).toEqual([]);
    expect(referencedParams(parseIndicatorConfig(json))).toEqual([
      'startDate',
      'endDate',
      'region',
    ]);
  });

  it('requires referenced params to be declared', () => {
    const config = {
      ...emptyIndicatorConfig(),
      indicatorIds: [1],
      filters: [{ param: 'region', fieldUid: 'u' }],
    };
    expect(indicatorConfigError(config, [])).toContain('region');
    expect(indicatorConfigError(config, ['region'])).toBeUndefined();
    expect(indicatorConfigError(emptyIndicatorConfig(), [])).toContain('1-10');
    expect(
      indicatorConfigError({ ...config, startParam: 's' }, ['region']),
    ).toContain('同时配置');
  });
});

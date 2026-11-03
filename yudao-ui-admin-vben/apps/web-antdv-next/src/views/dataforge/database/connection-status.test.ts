import { describe, expect, it } from 'vitest';

import {
  connectionStatusColor,
  connectionStatusLabel,
  DATABASE_CONNECTION_STATUS,
} from './connection-status';

describe('connectionStatusLabel', () => {
  it('maps backend status codes to the list labels', () => {
    expect(connectionStatusLabel(DATABASE_CONNECTION_STATUS.ONLINE)).toBe(
      '在线',
    );
    expect(connectionStatusLabel(DATABASE_CONNECTION_STATUS.OFFLINE)).toBe(
      '离线',
    );
    expect(connectionStatusLabel(DATABASE_CONNECTION_STATUS.UNTESTED)).toBe(
      '未检测',
    );
    expect(connectionStatusLabel(undefined)).toBe('未检测');
  });
});

describe('connectionStatusColor', () => {
  it('uses Tag colors matching the list badge style', () => {
    expect(connectionStatusColor(DATABASE_CONNECTION_STATUS.ONLINE)).toBe(
      'green',
    );
    expect(connectionStatusColor(DATABASE_CONNECTION_STATUS.OFFLINE)).toBe(
      'red',
    );
    expect(connectionStatusColor()).toBe('default');
  });
});

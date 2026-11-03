import { describe, expect, it } from 'vitest';

import {
  isDataNodeAuthorities,
  isFileRootPath,
  isWebHdfsUrl,
  readFileConnectionFields,
  writeFileConnectionFields,
} from './file-connection';

describe('文件连接配置', () => {
  it('文件协议 SFTP 超时数字往返，FTP 和 HDFS 不增加未支持字段', () => {
    const values = {
      id: 1,
      userName: 'fixture-user',
      fileRootPath: '/inbox',
      fileHostKeyFingerprint: `SHA256:${'a'.repeat(43)}`,
      connectionTimeoutSeconds: 25,
    };
    const result = writeFileConnectionFields(values, 'SFTP');
    expect(JSON.parse(result.extendConfig!).timeoutSeconds).toBe(25);
    expect(
      readFileConnectionFields(result.extendConfig).connectionTimeoutSeconds,
    ).toBe(25);
    expect(result).not.toHaveProperty('connectionTimeoutSeconds');
    for (const protocol of ['FTP', 'HDFS'] as const)
      expect(
        JSON.parse(writeFileConnectionFields(values, protocol).extendConfig!),
      ).not.toHaveProperty('timeoutSeconds');
  });
  it('根目录拒绝穿越、控制字符和反斜杠', () => {
    expect(isFileRootPath('/资料/文件')).toBe(true);
    expect(isFileRootPath('/')).toBe(true);
    expect(isFileRootPath('/资料/文件/')).toBe(true);
    expect(isFileRootPath('/100%+中文/')).toBe(true);
    for (const value of [
      'relative',
      '/a/../b',
      '/a/./b',
      String.raw`/a\b`,
      '/a\n',
      '/a//b',
      '/a%2fb',
      '/%25encoded',
      '//',
      '/   /b',
      `/${'a'.repeat(256)}`,
      `/${Array.from({ length: 17 }, () => 'a'.repeat(255)).join('/')}`,
    ]) {
      expect(isFileRootPath(value)).toBe(false);
    }
  });

  it('文件服务仅接受无凭据的 HTTP 地址', () => {
    expect(isWebHdfsUrl('https://namenode.test:9871/webhdfs/v1')).toBe(true);
    expect(isWebHdfsUrl('http://NODE:80')).toBe(true);
    expect(isWebHdfsUrl('http://node/')).toBe(true);
    for (const value of [
      'hdfs://node:8020',
      'http://name:secret@node',
      'http://node/?user.name=admin',
      'http://node/private',
      'http://node/#fragment',
      'http://node:9870/webhdfs/v1/',
      'http://node:0',
      'http://node:65536',
      'http://node/?',
      'http://node/#',
      'http://node/private/..',
      'http://node/\n',
      'http://@node/',
    ]) {
      expect(isWebHdfsUrl(value)).toBe(false);
    }
    expect(isDataNodeAuthorities('node-a:9864\nnode-b:9864')).toBe(true);
    expect(isDataNodeAuthorities('node:80\nNODE:9864\n[::1]:9864')).toBe(true);
    expect(isDataNodeAuthorities('node')).toBe(true);
    expect(isDataNodeAuthorities('')).toBe(true);
    expect(isDataNodeAuthorities('node:0')).toBe(false);
    expect(isDataNodeAuthorities('node:65536')).toBe(false);
    expect(isDataNodeAuthorities('node:9864/')).toBe(false);
    expect(isDataNodeAuthorities('node:9864?')).toBe(false);
    expect(isDataNodeAuthorities('node:9864#')).toBe(false);
    expect(
      isDataNodeAuthorities(
        Array.from({ length: 101 }, (_, index) => `node-${index}:9864`).join(
          '\n',
        ),
      ),
    ).toBe(false);
    expect(isDataNodeAuthorities('http://node:9864')).toBe(false);
    expect(isDataNodeAuthorities('name:secret@node:9864')).toBe(false);
  });

  it('序列化前剥离表单字段与 SQL 配置，连接编号保持无损', () => {
    const result = writeFileConnectionFields(
      {
        id: '9007199254740993',
        databaseType: '9007199254740995',
        userName: 'reader',
        fileRootPath: '/资料',
        fileWebHdfsUrl: 'http://namenode.test:9870',
        fileDataNodes: 'node:9864\nnode:9864',
        jdbcUrl: 'jdbc:old',
        databaseName: 'old',
        password: 'test-only-unused',
      },
      'HDFS',
    );
    expect(result.id).toBe('9007199254740993');
    expect(result.jdbcUrl).toBe('');
    expect(result.password).toBe('');
    expect(result).not.toHaveProperty('fileRootPath');
    expect(JSON.parse(result.extendConfig!)).toEqual({
      basePath: '/资料',
      webHdfsUrl: 'http://namenode.test:9870',
      allowedDataNodeAuthorities: ['node:9864'],
    });
  });

  it('编辑不改扩展配置时保留原字节，损坏配置不自动替换为根目录', () => {
    const original = '{ "basePath" : "/inbox" }';
    const fields = readFileConnectionFields(original);
    expect(
      writeFileConnectionFields(
        { id: 1, userName: 'reader', ...fields },
        'FTP',
        original,
      ).extendConfig,
    ).toBe(original);
    expect(readFileConnectionFields('not-json').fileRootPath).toBeUndefined();
  });
});

import { Buffer } from 'node:buffer';

import { describe, expect, it } from 'vitest';

import {
  buildSendRequest,
  createDraft,
  createRow,
  formatJson,
} from './request';

function draft() {
  return createDraft({ url: 'https://example.test/api' });
}

describe('接口测试请求草稿', () => {
  it('默认使用外部 GET，草稿与表格行有独立身份及独立状态', () => {
    const first = createDraft();
    const second = createDraft();
    expect(first).toMatchObject({
      mode: 'EXTERNAL',
      method: 'GET',
      bodyType: 'none',
      timeoutSeconds: 60,
      followRedirects: false,
      auth: { type: 'none' },
    });
    expect(first.id).not.toBe(second.id);
    expect(first.query[0]?.id).not.toBe(first.headers[0]?.id);
    first.auth.token = '只在当前草稿内';
    first.query.push(createRow('name', 'value'));
    expect(second.auth.token).toBe('');
    expect(second.query).toHaveLength(1);
    expect(createRow('id', '9007199254740993123', 'LONG')).toMatchObject({
      enabled: true,
      name: 'id',
      value: '9007199254740993123',
      paramType: 'LONG',
    });
  });

  it('保留参数原始字符串、重复键与空值，并忽略空白行和禁用项', () => {
    const current = draft();
    current.query = [
      createRow(),
      createRow('id', '9007199254740993123', 'LONG'),
      createRow('amount', '1234567890.12345678901234567890', 'DECIMAL'),
      createRow('tag', '第一项'),
      createRow('tag', ''),
      { ...createRow('disabled', 'secret'), enabled: false },
    ];
    expect(buildSendRequest(current).query).toEqual([
      { name: 'id', value: '9007199254740993123' },
      { name: 'amount', value: '1234567890.12345678901234567890' },
      { name: 'tag', value: '第一项' },
      { name: 'tag', value: '' },
    ]);
    expect(buildSendRequest(current)).not.toHaveProperty('body');
    expect(buildSendRequest(current)).not.toHaveProperty('apiId');
  });

  it('已注册接口保留大 ID 字符串，不提交草稿中外部请求地址', () => {
    const current = createDraft({
      mode: 'REGISTERED',
      apiId: '9223372036854775807',
      url: 'https://example.test/ignored',
    });
    const request = buildSendRequest(current);
    expect(request.apiId).toBe('9223372036854775807');
    expect(request).not.toHaveProperty('url');
    expect(buildSendRequest({ ...current, apiId: 12 }).apiId).toBe('12');
  });

  it.each([
    undefined,
    '',
    '0',
    '-1',
    '1.0',
    '1e3',
    ' 12 ',
    9_007_199_254_740_992,
  ])('拒绝无效或已经丢失精度的已注册接口 ID：%s', (apiId) => {
    expect(() =>
      buildSendRequest({ ...draft(), mode: 'REGISTERED', apiId }),
    ).toThrow('请选择有效的已注册接口');
  });

  it.each([0, 121, 1.5, Number.NaN, Number.POSITIVE_INFINITY])(
    '拒绝无效超时时间：%s',
    (timeoutSeconds) => {
      expect(() => buildSendRequest({ ...draft(), timeoutSeconds })).toThrow(
        '超时时间必须为 1 到 120 秒的整数',
      );
    },
  );

  it('支持有效超时边界与显式重定向设置，并规范 HTTP 方法', () => {
    expect(
      buildSendRequest({ ...draft(), method: ' post ', timeoutSeconds: 1 }),
    ).toMatchObject({ method: 'POST', timeoutSeconds: 1 });
    expect(
      buildSendRequest({
        ...draft(),
        timeoutSeconds: 120,
        followRedirects: true,
      }),
    ).toMatchObject({ timeoutSeconds: 120, followRedirects: true });
    expect(() => buildSendRequest({ ...draft(), method: 'TRACE' })).toThrow(
      '请求方式无效',
    );
  });
});

describe('请求编码与认证', () => {
  it('校验 JSON 语法后发送原文，不改变数字、重复键或转义', () => {
    const source = String.raw` {"id":9223372036854775807,"price":0.12345678901234567890,"id":-0,"text":"\u4e2d","exponent":1.0000e+0040} `;
    const request = buildSendRequest({
      ...draft(),
      method: 'POST',
      bodyType: 'json',
      rawBody: source,
    });
    expect(request.body).toBe(source);
    expect(request.headers).toContainEqual({
      name: 'Content-Type',
      value: 'application/json',
    });
  });

  it.each(['', '{', '{"value":NaN}', '{"value":1,}', '{"value":01}'])(
    '拒绝非法 JSON 请求体：%s',
    (rawBody) => {
      expect(() =>
        buildSendRequest({
          ...draft(),
          method: 'POST',
          bodyType: 'json',
          rawBody,
        }),
      ).toThrow('JSON 内容格式无效');
    },
  );

  it.each(['GET', 'HEAD'])('拒绝 %s 中的非空请求体', (method) => {
    expect(() =>
      buildSendRequest({ ...draft(), method, bodyType: 'text', rawBody: ' ' }),
    ).toThrow(`${method} 请求不能携带请求体`);
  });

  it.each(['GET', 'HEAD'])('%s 空文本请求体符合后端契约', (method) => {
    expect(
      buildSendRequest({ ...draft(), method, bodyType: 'text', rawBody: '' })
        .body,
    ).toBe('');
  });

  it.each(['', '<root>', '<root><item></root>', '<a/><b/>'])(
    '拒绝无效 XML：%s',
    (rawBody) => {
      expect(() =>
        buildSendRequest({
          ...draft(),
          method: 'POST',
          bodyType: 'xml',
          rawBody,
        }),
      ).toThrow('XML 内容格式无效');
    },
  );

  it.each([
    '<!DOCTYPE root SYSTEM "https://example.test/external.dtd"><root/>',
    '<!DOCTYPE root [<!ENTITY external SYSTEM "https://example.test/private">]><root>&external;</root>',
  ])('不解析 XML 外部声明：%s', (rawBody) => {
    expect(() =>
      buildSendRequest({
        ...draft(),
        method: 'POST',
        bodyType: 'xml',
        rawBody,
      }),
    ).toThrow('XML 请求体不支持 DTD 或实体声明');
  });

  it('表单编码保留多值键、空值、精度和 UTF-8 字符', () => {
    const request = buildSendRequest({
      ...draft(),
      method: 'POST',
      bodyType: 'x-www-form-urlencoded',
      urlencoded: [
        createRow('tag', '中文 空格'),
        createRow('tag', 'a+b&c'),
        createRow('id', '9223372036854775807'),
        createRow('empty', ''),
      ],
    });
    expect(request.body).toBe(
      'tag=%E4%B8%AD%E6%96%87+%E7%A9%BA%E6%A0%BC&tag=a%2Bb%26c&id=9223372036854775807&empty=',
    );
    expect(request.headers).toContainEqual({
      name: 'Content-Type',
      value: 'application/x-www-form-urlencoded',
    });
  });

  it('文本 multipart 包含匹配 boundary、正确字段转义和原始字段值', () => {
    const request = buildSendRequest({
      ...draft(),
      method: 'POST',
      bodyType: 'form-data',
      formData: [
        createRow(String.raw`field"\name`, '中文\n9007199254740993123'),
        createRow(String.raw`field"\name`, ''),
      ],
      headers: [createRow('content-type', 'multipart/form-data; boundary=old')],
    });
    const contentTypes = request.headers.filter(
      ({ name }) => name.toLowerCase() === 'content-type',
    );
    expect(contentTypes).toHaveLength(1);
    const boundary = contentTypes[0]?.value.split('boundary=')[1];
    expect(boundary).toMatch(/^----DataHub[\da-f]+$/);
    expect(request.body).toBe(
      `--${boundary}\r\nContent-Disposition: form-data; name="field\\"\\\\name"\r\n\r\n中文\n9007199254740993123\r\n--${boundary}\r\nContent-Disposition: form-data; name="field\\"\\\\name"\r\n\r\n\r\n--${boundary}--\r\n`,
    );
  });

  it.each([
    ['xml', '<root>中文</root>', 'application/xml'],
    ['text', '原始文本\r\n123', 'text/plain; charset=UTF-8'],
  ] as const)('发送 %s 原文并补充 Content-Type', (bodyType, rawBody, value) => {
    const request = buildSendRequest({
      ...draft(),
      method: 'PUT',
      bodyType,
      rawBody,
    });
    expect(request.body).toBe(rawBody);
    expect(request.headers).toContainEqual({ name: 'Content-Type', value });
  });

  it('请求 JSON 尊重手动指定的 Content-Type，Auth 替换已有同名头', () => {
    const current = draft();
    current.method = 'POST';
    current.bodyType = 'json';
    current.rawBody = '{}';
    current.headers = [
      createRow('content-TYPE', 'application/vnd.test+json'),
      createRow('authorization', 'previous'),
      createRow('Authorization', 'another previous'),
    ];
    current.auth.type = 'bearer';
    current.auth.token = 'explicit-test-token';
    expect(buildSendRequest(current).headers).toEqual([
      { name: 'content-TYPE', value: 'application/vnd.test+json' },
      { name: 'Authorization', value: 'Bearer explicit-test-token' },
    ]);
  });

  it('认证 Basic Auth 使用 UTF-8 Base64，API Key 只进入显式请求头', () => {
    const current = draft();
    current.auth.type = 'basic';
    current.auth.username = '用户名';
    current.auth.password = '密码:一';
    const request = buildSendRequest(current);
    expect(request.headers).toContainEqual({
      name: 'Authorization',
      value: `Basic ${Buffer.from('用户名:密码:一', 'utf8').toString('base64')}`,
    });
    current.auth.type = 'apikey';
    current.auth.key = 'X-Api-Key';
    current.auth.value = 'explicit-test-key';
    const apiKeyRequest = buildSendRequest(current);
    expect(apiKeyRequest.headers).toContainEqual({
      name: 'X-Api-Key',
      value: 'explicit-test-key',
    });
    expect(apiKeyRequest.query).toEqual([]);
  });

  it('请求 Cookie 来自显式启用项，不继承页面 cookie', () => {
    const current = draft();
    current.cookies = [
      createRow('session', 'explicit-cookie'),
      createRow('empty', ''),
      { ...createRow('disabled', 'secret'), enabled: false },
    ];
    expect(buildSendRequest(current).headers).toEqual([
      { name: 'Cookie', value: 'session=explicit-cookie; empty=' },
    ]);
    expect(buildSendRequest(draft()).headers).toEqual([]);
  });
});

describe('请求边界校验', () => {
  it.each([
    'https://user:password@example.test/api',
    'https://@example.test/api',
    'https://example.test/api#fragment',
    'https://example.test/api#',
    'file:///tmp/file',
    'javascript:alert(1)',
    'http:example.test',
    '/api',
    String.raw`https://example.test\path`,
    'https://example.test/line\npath',
  ])('拒绝非 HTTP 目标及地址歧义：%s', (url) => {
    expect(() => buildSendRequest({ ...draft(), url })).toThrow(/地址/);
  });

  it.each([
    'Host',
    'cOnTeNt-LeNgTh',
    'Transfer-Encoding',
    'Connection',
    'Keep-Alive',
    'Upgrade',
    'Expect',
    'Accept-Encoding',
    'Proxy-Authorization',
    'Forwarded',
    'X-Real-IP',
    'X-Forwarded-For',
    'X-Forwarded-Host',
    'WL-Proxy-Client-IP',
    'HTTP_CLIENT_IP',
    'HTTP_X_FORWARDED_FOR',
    'login-user',
    'tenant-id',
    'visit-tenant-id',
    'DATA-PERMISSION',
    'data-permission-enabled',
    'data-permission-custom',
    'tag',
  ])('拒绝内部身份及 HTTP 控制请求头：%s', (name) => {
    expect(() =>
      buildSendRequest({ ...draft(), headers: [createRow(name, 'value')] }),
    ).toThrow('不能设置平台内部身份或 HTTP 控制请求头');
    const current = draft();
    current.auth.type = 'apikey';
    current.auth.key = name;
    current.auth.value = 'explicit-value';
    expect(() => buildSendRequest(current)).toThrow(
      '不能设置平台内部身份或 HTTP 控制请求头',
    );
  });

  it('拒绝 Header、Cookie 与 multipart 字段中的行注入', () => {
    expect(() =>
      buildSendRequest({
        ...draft(),
        headers: [createRow('X-Test', 'a\r\nInjected: yes')],
      }),
    ).toThrow('请求头值不能包含');
    expect(() =>
      buildSendRequest({
        ...draft(),
        headers: [createRow('X-Test: invalid', 'value')],
      }),
    ).toThrow('请求头名称格式无效');
    expect(() =>
      buildSendRequest({
        ...draft(),
        cookies: [createRow('session', 'value; other=yes')],
      }),
    ).toThrow('Cookie 名称或值格式无效');
    expect(() =>
      buildSendRequest({
        ...draft(),
        cookies: [createRow('session', 'a\nInjected:yes')],
      }),
    ).toThrow('请求头值不能包含');
    expect(() =>
      buildSendRequest({
        ...draft(),
        method: 'POST',
        bodyType: 'form-data',
        formData: [createRow('field\r\nInjected', 'value')],
      }),
    ).toThrow('表单字段名称不能包含');
  });

  it('不把请求字段中的非字符串数据隐式转换成数值或文本', () => {
    const current = draft();
    current.query = [
      { ...createRow('id', ''), value: 12 as unknown as string },
    ];
    expect(() => buildSendRequest(current)).toThrow('参数名称和值必须为字符串');
    current.query = [createRow('', 'value without name')];
    expect(() => buildSendRequest(current)).toThrow('请填写参数名称');
  });
});

describe('无损 JSON 格式化', () => {
  it('保留数字词素、重复键及字符串转义，仅改变语法空白', () => {
    const source = String.raw`{"id":9223372036854775807,"amount":0.1234567890123456789000,"negativeZero":-0,"exponent":1.000e+0040,"id":1,"text":"\u4e2d,\"{}[]","empty":{},"array":[[],true,null,1e400]}`;
    const formatted = formatJson(source);
    expect(formatted).toContain('"id": 9223372036854775807');
    expect(formatted).toContain('"amount": 0.1234567890123456789000');
    expect(formatted).toContain('"negativeZero": -0');
    expect(formatted).toContain('"exponent": 1.000e+0040');
    expect(formatted).toContain(String.raw`"text": "\u4e2d,\"{}[]"`);
    expect(formatted).toContain('"empty": {}');
    expect(formatted).toContain('    1e400');
    expect(formatted.match(/"id":/g)).toHaveLength(2);
    expect(formatJson(formatted)).toBe(formatted);
  });

  it.each([
    'null',
    'true',
    '9223372036854775807',
    '-0',
    '1e400',
    '" spaced "',
    '[]',
    '{}',
  ])('支持 JSON 顶层原语和空容器：%s', (value) => {
    expect(formatJson(`  ${value}\n`)).toBe(value);
  });

  it('提供稳定的两空格缩进，并拒绝非法语法', () => {
    expect(formatJson('{"list":[{"name":"中文"},{}]}')).toBe(
      '{\n  "list": [\n    {\n      "name": "中文"\n    },\n    {}\n  ]\n}',
    );
    expect(() => formatJson('{invalid}')).toThrow('JSON 内容格式无效');
  });

  it('深层 JSON 保留原文发送，格式化拒绝过深层级以防展开失控', () => {
    const source = `${'['.repeat(129)}9223372036854775807${']'.repeat(129)}`;
    expect(() => formatJson(source)).toThrow('JSON 层级过深');
    expect(
      buildSendRequest({
        ...draft(),
        method: 'POST',
        bodyType: 'json',
        rawBody: source,
      }).body,
    ).toBe(source);
  });

  it('大型有效 JSON 限制词素数量和展开结果，而不生成无限缩进', () => {
    const numerousItems = `[${Array.from({ length: 50_001 }, () => '0').join(',')}]`;
    expect(() => formatJson(numerousItems)).toThrow('JSON 项目过多');
    const deepArray = `${'['.repeat(128)}${Array.from({ length: 20_000 }, () => '0').join(',')}${']'.repeat(128)}`;
    expect(() => formatJson(deepArray)).toThrow('JSON 格式化结果过大');
  });

  it('允许大小上限内的原始字符串，按 UTF-8 字节限制格式化输出', () => {
    const source = `"${'a'.repeat(4 * 1024 * 1024 - 2)}"`;
    expect(formatJson(source)).toBe(source);
    expect(() => formatJson(`"${'a'.repeat(4 * 1024 * 1024)}"`)).toThrow(
      'JSON 内容过大',
    );
    expect(() => formatJson(`"${'中'.repeat(1_398_101)}"`)).toThrow(
      'JSON 格式化结果过大',
    );
  });
});

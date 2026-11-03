import * as monaco from 'monaco-editor';

/**
 * 运行日志着色语言（参考 qData 用 Monaco 渲染任务日志的思路）：
 * 时间戳、日志级别、SQL 关键字、数字分别着色，便于快速定位错误
 */
export const LOG_LANGUAGE_ID = 'javalog';

let registered = false;

export function registerLogLanguage() {
  if (registered) {
    return;
  }
  registered = true;

  monaco.languages.register({ id: LOG_LANGUAGE_ID });
  monaco.languages.setMonarchTokensProvider(LOG_LANGUAGE_ID, {
    tokenizer: {
      root: [
        [/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}[,.]\d{3}/, 'log-timestamp'],
        [/\[?\b(ERROR|FATAL|Exception|失败|异常)\b\]?/, 'log-error'],
        [/\[?\b(WARN|WARNING|警告|跳过)\b\]?/, 'log-warn'],
        [/\[?\bINFO\b\]?/, 'log-info'],
        [/\[?\b(DEBUG|TRACE)\b\]?/, 'log-debug'],
        [/\b(成功|完成|SUCCESS)\b/, 'log-success'],
        [/\b(SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|FROM|WHERE|JOIN|GROUP BY|ORDER BY)\b/i, 'log-sql'],
        [/\b\d+(\.\d+)?\s*(ms|s|行|条)?\b/, 'log-number'],
      ],
    },
  });

  monaco.editor.defineTheme('javalog-light', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'log-timestamp', foreground: '008000' },
      { token: 'log-error', foreground: 'e51400', fontStyle: 'bold' },
      { token: 'log-warn', foreground: 'bf8803' },
      { token: 'log-info', foreground: '0451a5' },
      { token: 'log-debug', foreground: '767676' },
      { token: 'log-success', foreground: '22863a', fontStyle: 'bold' },
      { token: 'log-sql', foreground: 'af00db' },
      { token: 'log-number', foreground: '098658' },
    ],
    colors: {
      'editor.background': '#ffffff',
    },
  });
}

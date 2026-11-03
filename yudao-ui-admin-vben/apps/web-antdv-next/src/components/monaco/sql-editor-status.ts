/** 状态栏只消费当前 Monaco model 的真实状态；未收到诊断事件时不宣称语法通过。 */
export interface SqlEditorStatus {
  column: number;
  eol: 'CRLF' | 'LF';
  insertSpaces: boolean;
  language: string;
  lineNumber: number;
  syntaxErrors?: number;
  tabSize: number;
}

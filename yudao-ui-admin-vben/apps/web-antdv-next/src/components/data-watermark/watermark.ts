/** 数据水印文本：查看者 + 编号 + 时间，用于截图外传时溯源。 */
export function watermarkText(
  user:
    | null
    | undefined
    | { nickname?: string; userId?: number | string; username?: string },
  now: Date = new Date(),
): string {
  const name = user?.nickname || user?.username || '未登录';
  const id =
    user?.userId === undefined || user?.userId === null
      ? ''
      : ` ${String(user.userId)}`;
  const pad = (value: number) => String(value).padStart(2, '0');
  const time = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`;
  return `${name}${id} ${time}`;
}

/** 平铺水印背景（SVG data URL），文字转义后放入，避免注入标记。 */
export function watermarkBackground(text: string): string {
  const escaped = text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="260" height="160"><text x="10" y="90" transform="rotate(-20 130 80)" fill="rgba(128,128,128,0.16)" font-size="14" font-family="sans-serif">${escaped}</text></svg>`;
  return `url("data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}")`;
}

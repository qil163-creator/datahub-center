/**
 * kkFileView 在线预览工具。
 *
 * 对接官方接入说明：
 * window.open(baseUrl + '/onlinePreview?url=' + encodeURIComponent(base64Encode(fileUrl)))
 *
 * 1. 普通 HTTP/HTTPS 文件地址：直接 Base64 + encodeURIComponent
 * 2. 无扩展名的流式地址：先追加 fullfilename=xxx.pdf，再编码
 */

const DEFAULT_KK_FILE_VIEW_URL = 'http://127.0.0.1:8012';

/** 获取 kkFileView 服务地址 */
export function getKkFileViewBaseUrl(): string {
  const configured = import.meta.env.VITE_KK_FILE_VIEW_URL as string | undefined;
  return (configured || DEFAULT_KK_FILE_VIEW_URL).replace(/\/$/, '');
}

/**
 * Base64 编码（兼容中文等非 Latin1 字符）。
 * 对应 kkFileView 要求的 Base64 + URLEncode 双重编码中的 Base64 部分。
 */
function encodeBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

/** 为文件地址补充 fullfilename，便于 kkFileView 识别扩展名 */
export function appendFullFileName(
  fileUrl: string,
  fileName?: null | string,
): string {
  if (!fileName) {
    return fileUrl;
  }
  try {
    const url = new URL(fileUrl);
    if (!url.searchParams.has('fullfilename')) {
      url.searchParams.set('fullfilename', fileName);
    }
    return url.toString();
  } catch {
    const separator = fileUrl.includes('?') ? '&' : '?';
    return `${fileUrl}${separator}fullfilename=${encodeURIComponent(fileName)}`;
  }
}

/**
 * 构建 kkFileView 在线预览地址。
 * @param fileUrl 可被 kkFileView 服务端访问的文件 URL（http/https）
 * @param fileName 可选文件名，用于无扩展名 URL 时识别类型
 */
export function buildKkFileViewPreviewUrl(
  fileUrl: string,
  fileName?: null | string,
): string {
  const sourceUrl = appendFullFileName(fileUrl, fileName);
  const encoded = encodeURIComponent(encodeBase64(sourceUrl));
  return `${getKkFileViewBaseUrl()}/onlinePreview?url=${encoded}`;
}

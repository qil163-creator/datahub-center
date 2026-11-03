import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

export interface FileConnectionFields {
  fileRootPath?: string;
  fileWebHdfsUrl?: string;
  fileDataNodes?: string;
  fileHostKeyFingerprint?: string;
  connectionTimeoutSeconds?: number;
}

export function fileConnectionProtocol(
  type?: FoundationDatabaseTypeApi.DatabaseType,
) {
  const code = type?.typeCode?.toUpperCase();
  return code === 'FTP' || code === 'HDFS' || code === 'SFTP'
    ? code
    : undefined;
}

/** 文件连接配置在表单中展开，提交仍使用已有的连接扩展字段。 */
export function readFileConnectionFields(
  config?: string,
): FileConnectionFields {
  if (!config?.trim()) return { fileRootPath: '/' };
  try {
    const value: unknown = JSON.parse(config);
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
    const data = value as Record<string, unknown>;
    return {
      fileRootPath: typeof data.basePath === 'string' ? data.basePath : '/',
      ...(typeof data.timeoutSeconds === 'number' &&
      Number.isInteger(data.timeoutSeconds)
        ? { connectionTimeoutSeconds: data.timeoutSeconds }
        : {}),
      fileHostKeyFingerprint:
        typeof data.hostKeyFingerprint === 'string'
          ? data.hostKeyFingerprint
          : '',
      fileWebHdfsUrl:
        typeof data.webHdfsUrl === 'string' ? data.webHdfsUrl : '',
      fileDataNodes: Array.isArray(data.allowedDataNodeAuthorities)
        ? data.allowedDataNodeAuthorities
            .filter((item): item is string => typeof item === 'string')
            .join('\n')
        : '',
    };
  } catch {
    return {};
  }
}

export function isFileRootPath(value: string) {
  if (
    !value.startsWith('/') ||
    value.length > 4096 ||
    value.includes('\\') ||
    /%[\da-f]{2}/i.test(value) ||
    hasControlCharacters(value)
  )
    return false;
  if (value === '/') return true;
  const path = value.endsWith('/') ? value.slice(0, -1) : value;
  return path
    .slice(1)
    .split('/')
    .every(
      (part) =>
        part.trim().length > 0 &&
        part.length <= 255 &&
        part !== '.' &&
        part !== '..',
    );
}

export function isWebHdfsUrl(value: string) {
  try {
    if (hasControlCharacters(value) || /\s/u.test(value)) return false;
    // 检查原始结构，避免 URL 自动去掉空查询参数或归一化上级路径。
    const parts = /^https?:\/\/([^/?#]+)([^?#]*)$/i.exec(value);
    if (!parts?.[1] || !isFileServiceAuthority(parts[1])) return false;
    const path = decodeURIComponent(parts[2] ?? '');
    return path === '' || path === '/' || path === '/webhdfs/v1';
  } catch {
    return false;
  }
}

export function dataNodeAuthorities(value?: string) {
  return [
    ...new Set(
      (value ?? '')
        .split(/[\n,，]/)
        .map((part) => part.trim())
        .filter(Boolean),
    ),
  ];
}

export function isDataNodeAuthorities(value?: string) {
  const authorities = dataNodeAuthorities(value);
  return (
    authorities.length <= 100 &&
    authorities.every((authority) => isFileServiceAuthority(authority))
  );
}

function hasControlCharacters(value: string) {
  return [...value].some((char) => {
    const code = char.codePointAt(0) ?? 0;
    return code < 32 || code === 127;
  });
}

function isFileServiceAuthority(authority: string) {
  try {
    if (hasControlCharacters(authority) || /[\s%/?#@\\]/u.test(authority))
      return false;
    const parts = /^(\[[\da-f:.]+\]|[^:]+)(?::(\d+))?$/i.exec(authority);
    const hostname = parts?.[1];
    if (!hostname) return false;
    const port = parts?.[2];
    if (port !== undefined && (Number(port) < 1 || Number(port) > 65_535))
      return false;
    const url = new URL(`http://${authority}`);
    if (hostname.startsWith('[')) return !!url.hostname;
    return (
      /^[\da-z](?:[\da-z-]*[\da-z])?(?:\.[\da-z](?:[\da-z-]*[\da-z])?)*\.?$/i.test(
        hostname,
      ) && url.hostname.toLowerCase() === hostname.toLowerCase()
    );
  } catch {
    return false;
  }
}

export function writeFileConnectionFields(
  values: FileConnectionFields & FoundationDatabaseApi.Database,
  protocol: 'FTP' | 'HDFS' | 'SFTP',
  originalConfig?: string,
): FoundationDatabaseApi.Database {
  const {
    fileRootPath,
    fileWebHdfsUrl,
    fileDataNodes,
    fileHostKeyFingerprint,
    connectionTimeoutSeconds,
    ...connection
  } = values;
  const config = {
    basePath: fileRootPath?.trim() || '/',
    ...(protocol === 'SFTP'
      ? {
          schemaVersion: 1,
          hostKeyFingerprint: fileHostKeyFingerprint?.trim() || '',
          ...(connectionTimeoutSeconds === undefined
            ? {}
            : { timeoutSeconds: connectionTimeoutSeconds }),
        }
      : {}),
    ...(protocol === 'HDFS'
      ? {
          webHdfsUrl: fileWebHdfsUrl?.trim() || '',
          allowedDataNodeAuthorities: dataNodeAuthorities(fileDataNodes),
        }
      : {}),
  };
  // 编辑未改动配置时保留原始字节，避免触发旧凭据测连保护。
  const original = readFileConnectionFields(originalConfig);
  const unchanged =
    originalConfig?.trim() &&
    original.fileRootPath === config.basePath &&
    (protocol === 'FTP' ||
      (protocol === 'SFTP' &&
        original.fileHostKeyFingerprint === config.hostKeyFingerprint &&
        (original.connectionTimeoutSeconds ?? 10) ===
          (config.timeoutSeconds ?? 10)) ||
      (protocol === 'HDFS' &&
        original.fileWebHdfsUrl === config.webHdfsUrl &&
        JSON.stringify(dataNodeAuthorities(original.fileDataNodes)) ===
          JSON.stringify(config.allowedDataNodeAuthorities)));
  return {
    ...connection,
    ...(protocol === 'SFTP' ? { usageType: 1 } : {}),
    jdbcUrl: '',
    databaseName: '',
    databaseSchema: '',
    ...(protocol === 'HDFS' ? { password: '' } : {}),
    extendConfig: unchanged ? originalConfig : JSON.stringify(config),
  };
}

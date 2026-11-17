import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

const requests = new Map<string, string>();

/** 失败重试沿用同一请求号，成功回执后才释放。 */
export function masterSubmitCommand(
  owner: string,
  data: Omit<GovernanceMasterManageApi.SubmitReq, 'requestId'>,
) {
  const key = JSON.stringify([owner, data]);
  const request = requestFor(key);
  return { data: { ...data, requestId: request.requestId }, complete: request.complete };
}

export async function masterImportCommand(owner: string, modelId: string, file: File) {
  const bytes = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  const hash = Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('');
  return requestFor(JSON.stringify([owner, 'import', modelId, hash]));
}

function requestFor(key: string) {
  let requestId = requests.get(key);
  if (!requestId) {
    requestId = crypto.randomUUID();
    requests.set(key, requestId);
  }
  return { requestId, complete: () => requests.delete(key) };
}

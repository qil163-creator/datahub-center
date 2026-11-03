import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

const requests = new Map<string, string>();

/** 失败重试沿用同一请求号，成功回执后才释放。 */
export function masterSubmitCommand(
  owner: string,
  data: Omit<GovernanceMasterManageApi.SubmitReq, 'requestId'>,
) {
  const key = JSON.stringify([owner, data]);
  let requestId = requests.get(key);
  if (!requestId) {
    requestId = crypto.randomUUID();
    requests.set(key, requestId);
  }
  return { data: { ...data, requestId }, complete: () => requests.delete(key) };
}

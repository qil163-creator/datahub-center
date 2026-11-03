import { requestClient } from '#/api/request';

export interface ConsumptionReceipt {
  actualRows?: number;
  allocations: {
    metric: string;
    reserved: number | string;
    sourceKey: string;
    sourceSystem: string;
  }[];
  consumerKey: string;
  requestKey: string;
  rowLimit: number;
  state: 'CANCELLED' | 'COMMITTED' | 'EXPIRED' | 'RESERVED';
}
export interface ConsumptionBalance {
  metric: 'CALLS' | 'ROWS';
  quotaLimit: number | string;
  remainingUnits: number | string;
  reservedUnits: number | string;
  sourceKey: string;
  sourceSystem: string;
  usedUnits: number | string;
}
/** 每次明确的新查询生成新键；请求层的网络重试复用同一 headers。 */
export function consumptionHeaders(key: string = crypto.randomUUID()) {
  return { 'X-Consumption-Key': key };
}
export function getConsumptionHistory() {
  return requestClient.get<ConsumptionReceipt[]>(
    '/asset/market/access/consumption-history',
  );
}
export function getConsumptionBalances() {
  return requestClient.get<ConsumptionBalance[]>(
    '/asset/market/access/consumption-balances',
  );
}
export function getConsumptionReceipt(assetId: string, requestKey: string) {
  return requestClient.get<ConsumptionReceipt | null>(
    '/asset/market/access/consumption-receipt',
    { params: { assetId, requestKey } },
  );
}

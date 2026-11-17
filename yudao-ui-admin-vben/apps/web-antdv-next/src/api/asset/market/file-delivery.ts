import type { AxiosResponse } from '@vben/request';

import type { MarketApi } from './index';

import { requestClient } from '#/api/request';

export interface DeliveryFileBinding {
  catalogVersionId: MarketApi.Id;
  fileName: string;
  sha256: string;
  size: number;
}

const bindingPath = '/asset/market/file-delivery/binding';

export function getDeliveryFileBinding(assetId: MarketApi.Id, catalogVersionId: MarketApi.Id) {
  return requestClient.get<DeliveryFileBinding | null>(bindingPath, {
    params: { assetId, catalogVersionId },
  });
}

export function bindDeliveryFile(assetId: MarketApi.Id, catalogVersionId: MarketApi.Id, file: File) {
  return requestClient.upload<DeliveryFileBinding>(bindingPath, { assetId, catalogVersionId, file });
}

export async function downloadMyAssetFile(applyId: MarketApi.Id) {
  const response = await requestClient.download<AxiosResponse<Blob>>(
    '/asset/market/my-assets/download',
    { params: { id: applyId }, responseReturn: 'raw' },
  );
  const header = String(response.headers['content-disposition'] ?? '');
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header)?.[1];
  const plain = /filename\s*=\s*(?:"([^"]+)"|([^;]+))/i.exec(header);
  const name = encoded ? decodeURIComponent(encoded.trim()) : (plain?.[1] ?? plain?.[2]?.trim());
  return { fileName: name?.split(/[\\/]/).pop() || '资产文件', source: response.data };
}

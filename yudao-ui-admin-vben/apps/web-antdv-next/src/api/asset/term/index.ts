import { requestClient } from '#/api/request';

export namespace AssetTermApi {
  /** 数据治理业务术语在资产字段绑定中的只读投影。 */
  export interface Term {
    id: number | string;
    name: string;
    description?: string;
    status?: number;
  }

  export interface BindColumn {
    assetId: number | string;
    metaColumnId: number | string;
    /** 治理业务术语 ID；不传时解除绑定。 */
    termId?: number | string;
    remark?: string;
  }
}

export function getSimpleAssetTermList(keyword?: string) {
  return requestClient.get<AssetTermApi.Term[]>('/asset/term/simple-list', {
    params: { keyword },
  });
}

export function bindAssetTermColumn(data: AssetTermApi.BindColumn) {
  return requestClient.put<boolean>('/asset/term/bind-column', data);
}

import type { ComputedRef, InjectionKey } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

export const etlEnvironmentKey: InjectionKey<
  ComputedRef<DataforgeEtlApi.Id | undefined>
> = Symbol('etl-environment');

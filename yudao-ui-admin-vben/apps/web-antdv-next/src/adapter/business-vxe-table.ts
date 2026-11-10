import type { FormValues } from '@vben/common-ui';

import { useVbenVxeGrid as useGrid } from './vxe-table';

export * from './vxe-table';

export const useVbenVxeGrid = <
  T extends Record<string, any>,
  TFormValues extends FormValues = FormValues,
  TSubmitValues extends FormValues = TFormValues,
>(
  ...rest: Parameters<typeof useGrid<T, TFormValues, TSubmitValues>>
) => {
  const [options] = rest;
  rest[0] = {
    ...options,
    gridOptions: {
      ...options.gridOptions,
      toolbarConfig: {
        ...options.gridOptions?.toolbarConfig,
        zoom: false,
      },
    },
  };
  return useGrid<T, TFormValues, TSubmitValues>(...rest);
};

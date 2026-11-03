<script lang="ts" setup>
import type { FileConnectionFields } from '../file-connection';
import type { NativeConnectionFields } from '../native-connection';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { onMounted, ref } from 'vue';

import { useVbenForm } from '#/adapter/form';

import { isConnectOnlyType } from '../connection-capabilities';
import { buildJdbcUrlFromValues, useFormSchema } from '../data';
import {
  fileConnectionProtocol,
  readFileConnectionFields,
  writeFileConnectionFields,
} from '../file-connection';
import {
  nativeConnectionCode,
  readNativeConnectionFields,
  writeNativeConnectionFields,
} from '../native-connection';

const props = withDefaults(
  defineProps<{
    draft?: Partial<FoundationDatabaseApi.Database>;
    mode?: 'create' | 'edit';
    selectedType?: FoundationDatabaseTypeApi.DatabaseType;
  }>(),
  { mode: 'create', draft: undefined, selectedType: undefined },
);
const emit = defineEmits<{ changeType: [] }>();
const fileProtocol = fileConnectionProtocol(props.selectedType);
const nativeCode = nativeConnectionCode(props.selectedType);
const sameType =
  !props.selectedType ||
  String(props.draft?.databaseType) === String(props.selectedType.id);
const connectionDraft = sameType
  ? props.draft
  : {
      id: props.draft?.id,
      name: props.draft?.name,
      orgId: props.draft?.orgId,
      usageType: props.draft?.usageType,
      remark: props.draft?.remark,
    };
const initialValues = {
  ...connectionDraft,
  databaseType: props.selectedType?.id ?? props.draft?.databaseType,
  databasePort: sameType
    ? props.draft?.databasePort || props.selectedType?.portTemplates
    : props.selectedType?.portTemplates,
  jdbcUrl: sameType ? props.draft?.jdbcUrl : undefined,
  password: sameType ? props.draft?.password : '',
  extendConfig: sameType ? props.draft?.extendConfig : '',
  databaseSchema: sameType ? props.draft?.databaseSchema : '',
  usageType: isConnectOnlyType(props.selectedType)
    ? 1
    : (props.draft?.usageType ?? 1),
  ...(nativeCode || fileProtocol === 'SFTP'
    ? { connectionTimeoutSeconds: 10 }
    : {}),
  ...(['MILVUS', 'NEO4J'].includes(nativeCode ?? '')
    ? {
        databaseName:
          (sameType ? props.draft?.databaseName : undefined) ||
          (nativeCode === 'NEO4J' ? 'neo4j' : 'default'),
      }
    : {}),
  ...(fileProtocol
    ? readFileConnectionFields(sameType ? props.draft?.extendConfig : undefined)
    : {}),
  ...(nativeCode
    ? {
        connectionSecurityProtocol: 'PLAINTEXT',
        connectionSaslMechanism: 'PLAIN',
        connectionAuthenticationMode:
          nativeCode === 'MILVUS' ? 'PASSWORD' : 'NONE',
        connectionPathStyle: nativeCode === 'MINIO',
        connectionTls: false,
        connectionVirtualHost: '/',
        ...readNativeConnectionFields(
          sameType ? props.draft?.extendConfig : undefined,
        ),
      }
    : {}),
};
if (props.mode !== 'edit' || !sameType)
  initialValues.jdbcUrl = buildJdbcUrlFromValues(
    initialValues,
    props.selectedType,
  );
const initialized = ref(false);
const fieldOrder = new Map(
  [
    'id',
    'databaseType',
    'name',
    'databaseIp',
    'databasePort',
    'userName',
    'password',
    'fileRootPath',
    'fileWebHdfsUrl',
    'fileDataNodes',
    'fileHostKeyFingerprint',
    'connectionEndpoint',
    'connectionServers',
    'connectionRegion',
    'connectionPathStyle',
    'connectionSecurityProtocol',
    'connectionSaslMechanism',
    'connectionVirtualHost',
    'connectionTopic',
    'connectionTls',
    'connectionAuthenticationMode',
    'connectionTimeoutSeconds',
    'databaseName',
    'databaseSchema',
    'usageType',
    'orgId',
    'remark',
    'jdbcUrl',
    'extendConfig',
  ].map((field, index) => [field, index]),
);
const rowStarts = new Set([
  'databaseIp',
  'databaseName',
  'databaseType',
  'usageType',
  'userName',
]);
const pairedFields = new Set([
  ...rowStarts,
  'databasePort',
  'databaseSchema',
  'name',
  'orgId',
  'password',
]);

// 新增和修改共用字段、校验与双列布局，编辑保留已有凭据及自定义 URL。
const schema = useFormSchema({
  getSelectedType: () => props.selectedType,
  onEditType: () => emit('changeType'),
  preserveJdbcUrl: props.mode === 'edit',
  getInitialValues: () => initialValues,
})
  .map((field) => {
    return {
      ...field,
      formItemClass: pairedFields.has(field.fieldName)
        ? `col-span-12 sm:col-span-6${rowStarts.has(field.fieldName) ? ' sm:col-start-1' : ''}`
        : 'col-span-12',
      ...(field.fieldName === 'databaseType'
        ? {
            componentProps: () => ({
              typeName:
                props.selectedType?.typeName ||
                props.draft?.databaseTypeName ||
                (props.draft?.databaseType
                  ? `连接类型 ${props.draft.databaseType}`
                  : undefined),
              typeCode:
                props.selectedType?.typeCode ||
                props.selectedType?.productId ||
                (sameType ? props.draft?.typeCode || props.draft?.productId : undefined),
              iconPath:
                props.selectedType?.iconPath ||
                (sameType ? props.draft?.iconPath : undefined),
              readonly: props.mode !== 'edit',
              compact: true,
              onEdit:
                props.mode === 'edit' ? () => emit('changeType') : undefined,
            }),
          }
        : {}),
      ...(field.fieldName === 'databasePort' ? { hideLabel: false } : {}),
      ...(field.fieldName === 'password' && props.mode !== 'edit' && !nativeCode
        ? { componentProps: { placeholder: '请输入连接密码' }, help: undefined }
        : {}),
    };
  })
  .toSorted(
    (left, right) =>
      (fieldOrder.get(left.fieldName) ?? fieldOrder.size) -
      (fieldOrder.get(right.fieldName) ?? fieldOrder.size),
  );

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: { class: 'w-full' },
    formItemClass: 'col-span-12',
    labelWidth: 96,
  },
  wrapperClass: 'grid-cols-12 gap-x-6',
  compact: true,
  layout: 'horizontal',
  schema,
  showDefaultActions: false,
});

onMounted(async () => {
  await formApi.setValues(initialValues);
  initialized.value = true;
});

async function getValues() {
  let values = {
    ...(await formApi.getValues()),
  } as FileConnectionFields &
    FoundationDatabaseApi.Database &
    NativeConnectionFields;
  values.databaseType = props.selectedType?.id ?? values.databaseType;
  if (isConnectOnlyType(props.selectedType)) values.usageType = 1;
  if (props.mode !== 'edit')
    values.jdbcUrl =
      buildJdbcUrlFromValues(values, props.selectedType) || values.jdbcUrl;
  if (fileProtocol)
    values = writeFileConnectionFields(
      values,
      fileProtocol,
      sameType ? props.draft?.extendConfig : undefined,
    );
  if (nativeCode)
    values = writeNativeConnectionFields(
      values,
      nativeCode,
      sameType ? props.draft?.extendConfig : undefined,
    );
  delete (values as { secretKey?: string }).secretKey;
  if (props.mode !== 'edit')
    delete (values as { id?: FoundationDatabaseApi.Id }).id;
  return values;
}

async function readValues() {
  if (!initialized.value) return;
  const { valid } = await formApi.validate();
  if (valid) return getValues();
}

defineExpose({ getValues, readValues });
</script>

<template>
  <div class="connection-details">
    <Form class="connection-details__form" />
  </div>
</template>

<style scoped>
.connection-details {
  width: min(100%, 960px);
  margin: 0 auto;
}

.connection-details__form {
  padding-right: 12px;
}

@media (width <= 640px) {
  .connection-details__form {
    padding-right: 0;
  }
}
</style>

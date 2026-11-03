<script setup lang="ts">
import type { StandardDocApi } from '#/api/governance/doc';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Spin,
  TabPane,
  Tabs,
} from 'antdv-next';

import { getDoc } from '#/api/governance/doc';
import { DictTag } from '#/components/dict-tag';
import { useObjectRequest } from '#/hooks/use-object-request';

import { parseDocId } from './data';
import Form from './modules/form.vue';
import Preview from './modules/preview.vue';
import RelatedList from './modules/related-list.vue';
import StandardVersionModal from './modules/versions.vue';

defineOptions({ name: 'GovernanceStandardDocRegisterDetail' });
const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const active = ref(true);
const id = computed(() =>
  route.name === 'GovernanceStandardDocRegisterDetail'
    ? parseDocId(route.params.id)
    : undefined,
);
const canQuery = computed(() => hasAccessByCodes(['governance:doc:query']));
const detail = ref<StandardDocApi.Doc>();
const replacement = ref('');
const loading = ref(false);
const failed = ref(false);
const tab = ref('model');
const request = useObjectRequest(
  () => JSON.stringify([id.value, active.value, access.accessCodes]),
  () => {
    detail.value = undefined;
    replacement.value = '';
    loading.value = false;
  },
);
const [FormModal, formApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [PreviewModal, previewApi] = useVbenModal({
  connectedComponent: Preview,
  destroyOnClose: true,
});
const [VersionModal, versionApi] = useVbenModal({
  connectedComponent: StandardVersionModal,
  destroyOnClose: true,
});
const backPath = computed(() => {
  const value = route.query.back;
  return typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    value !== route.path
    ? value
    : String(route.meta.activePath || '/model/standard/doc-register');
});
async function load() {
  if (!active.value || !id.value || !canQuery.value) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const row = await getDoc(id.value);
    if (!current()) return;
    detail.value = row;
    replacement.value = row.replaceDocId ? String(row.replaceDocId) : '';
    if (row.replaceDocId) {
      try {
        const replaced = await getDoc(row.replaceDocId);
        if (current())
          replacement.value = [replaced.stdCode, replaced.nameCn]
            .filter(Boolean)
            .join(' ');
      } catch {
        /* 替代标准不可访问时保留编号回显。 */
      }
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
watch(
  () => [id.value, access.accessToken, access.refreshToken, access.accessCodes],
  () => {
    tab.value = 'model';
    void load();
  },
  { deep: true, immediate: true },
);
onDeactivated(() => {
  active.value = false;
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void load();
  }
});
</script>
<template>
  <Page>
    <FormModal @success="load" />
    <PreviewModal />
    <VersionModal />
    <div class="mb-3 flex justify-end">
      <Button @click="router.push(backPath)">返回列表</Button>
    </div>
    <Alert v-if="!id" type="error" message="标准编号无效" />
    <Alert
      v-else-if="!canQuery"
      type="warning"
      message="没有标准登记查询权限"
    />
    <Alert v-else-if="failed" type="error" message="标准详情加载失败">
      <template #action><Button @click="load">重试</Button></template>
    </Alert>
    <Spin v-else :spinning="loading">
      <template v-if="detail">
        <Card class="mb-3">
          <div class="mb-4 flex flex-wrap items-center gap-3">
            <span class="bg-primary/10 text-primary rounded px-2 py-1">{{
              detail.id
            }}</span>
            <h2 class="text-lg font-semibold">{{ detail.nameCn }}</h2>
            <DictTag
              :type="DICT_TYPE.STANDARD_IMPLEMENT_STATUS"
              :value="detail.implementStatus"
            />
            <div class="ml-auto flex flex-wrap gap-2">
              <Button
                :disabled="!detail.fileId && !detail.fileUrl"
                @click="previewApi.setData(detail).open()"
              >
                预览原文
              </Button>
              <Button
                @click="
                  versionApi
                    .setData({
                      objectType: 'DOC',
                      id: detail.id,
                      name: detail.nameCn,
                    })
                    .open()
                "
              >
                版本快照
              </Button>
              <Button
                v-if="hasAccessByCodes(['governance:doc:update'])"
                @click="formApi.setData({ id: detail.id }).open()"
              >
                修改
              </Button>
            </div>
          </div>
          <Descriptions bordered size="small" :column="{ xs: 1, md: 2, xl: 3 }">
            <DescriptionsItem label="标准类目">
              {{ detail.categoryName || detail.categoryId || '未分类' }}
            </DescriptionsItem>
            <DescriptionsItem label="标准号">
              {{ detail.stdCode || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="发布机构名称">
              {{ detail.publishOrg || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="描述" :span="3">
              {{ detail.summary || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="创建人">
              {{ detail.creatorName || detail.creator || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="创建时间">
              {{ formatDateTime(detail.createTime) || '-' }}
            </DescriptionsItem>
            <DescriptionsItem label="更新时间">
              {{ formatDateTime(detail.updateTime) || '-' }}
            </DescriptionsItem>
          </Descriptions>
        </Card>
        <Card>
          <Tabs v-model:active-key="tab">
            <TabPane key="model" tab="逻辑模型">
              <RelatedList
                v-if="tab === 'model' && active"
                :document-id="detail.id"
                :standard-type="detail.stdType"
                kind="model"
              />
            </TabPane>
            <TabPane key="element" tab="数据元">
              <RelatedList
                v-if="tab === 'element' && active"
                :document-id="detail.id"
                :standard-type="detail.stdType"
                kind="element"
              />
            </TabPane>
            <TabPane key="codeSet" tab="代码表">
              <RelatedList
                v-if="tab === 'codeSet' && active"
                :document-id="detail.id"
                :standard-type="detail.stdType"
                kind="codeSet"
              />
            </TabPane>
            <TabPane key="info" tab="详细信息">
              <Descriptions bordered size="small" :column="{ xs: 1, md: 2 }">
                <DescriptionsItem label="文件名称">
                  {{ detail.fileName || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="文件 URL">
                  <span class="break-all">{{ detail.fileUrl || '-' }}</span>
                </DescriptionsItem>
                <DescriptionsItem label="发布机构名称">
                  {{ detail.publishOrg || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="版本号">
                  {{ detail.versionNo || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="发布日期">
                  {{ detail.publishDate || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="实施日期">
                  {{ detail.implementDate || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="废止日期">
                  {{ detail.abolishDate || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="标准类型">
                  <DictTag
                    :type="DICT_TYPE.STANDARD_STD_TYPE"
                    :value="detail.stdType"
                  />
                </DescriptionsItem>
                <DescriptionsItem label="替代标准">
                  {{ replacement || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="启用状态">
                  <DictTag
                    :type="DICT_TYPE.COMMON_STATUS"
                    :value="detail.status"
                  />
                </DescriptionsItem>
                <DescriptionsItem label="入库编号">
                  {{ detail.registryNo || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="入库时间">
                  {{ formatDateTime(detail.registryTime) || '-' }}
                </DescriptionsItem>
                <DescriptionsItem label="备注" :span="2">
                  {{ detail.remark || '-' }}
                </DescriptionsItem>
              </Descriptions>
            </TabPane>
          </Tabs>
        </Card>
      </template>
    </Spin>
  </Page>
</template>

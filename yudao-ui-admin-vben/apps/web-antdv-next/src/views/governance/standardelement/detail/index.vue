<script lang="ts" setup>
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page, useVbenModal } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Button,
  Dropdown,
  Menu,
  MenuItem,
  message,
  Spin,
  Tabs,
} from 'antdv-next';

import { getDoc } from '#/api/governance/doc';
import { getStandardCodeSet } from '#/api/governance/standardcodeset';
import { getStandardElement } from '#/api/governance/standardelement';
import { useDescription } from '#/components/description';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import AssociationTable from '../../standardcommon/association-table.vue';
import ModelAssociations from '../../standardcommon/model-associations.vue';
import {
  formatCategoryName,
  formatDocumentName,
  formatDocumentStdCode,
  formatPersonName,
  formatUserMobile,
  loadElementNameCaches,
} from '../data';
import Form from '../modules/form.vue';
import { useDetailExtraSchema, useDetailSchema } from './data';
import ImpactPanel from './modules/impact-panel.vue';
import ItemGrid from './modules/item-grid.vue';
import MappedColumnsGrid from './modules/mapped-columns-grid.vue';
import ModelFieldsGrid from './modules/model-fields-grid.vue';
import RelateForm from './modules/relate-form.vue';
import RuleBindings from './modules/rule-bindings.vue';

import '../../standardcommon/standard.css';

defineOptions({ name: 'GovernanceStandardElementDetail' });

interface ElementDetailView
  extends GovernanceStandardElementApi.StandardElement {
  categoryName?: string;
  codeSetName?: string;
  contactNumber?: string;
  creatorName?: string;
  dictType?: string;
  documentName?: string;
  personChargeName?: string;
  stdCode?: string;
  stdType?: string;
  updaterName?: string;
}

const route = useRoute();
const router = useRouter();
const tabs = useTabs();
const access = useAccessStore();

const loading = ref(false);
const elementId = ref(0);
const element = ref<ElementDetailView>({} as ElementDetailView);
const activeTab = ref('models');
const extendedTabs = ref(false);
const detailRequest = useObjectRequest(
  () => String(route.params.id),
  () => {
    element.value = {} as ElementDetailView;
    loading.value = false;
  },
);
const tabItems = computed(() => [
  { key: 'models', label: '关联逻辑模型' },
  { key: 'assets', label: '关联资产' },
  { key: 'clean', label: '关联清洗规则' },
  { key: 'audit', label: '关联稽查规则' },
  { key: 'detail', label: '详细信息' },
  ...(extendedTabs.value
    ? [
        { key: 'codeset', label: '关联标准码表' },
        { key: 'mapping', label: '数据元落标' },
        { key: 'impact', label: '影响分析' },
      ]
    : []),
]);
function openExtended(tab: string) {
  extendedTabs.value = true;
  activeTab.value = tab;
}

const [ExtraDescriptions] = useDescription({
  bordered: true,
  column: { xs: 1, sm: 1, md: 2, lg: 2, xl: 2, xxl: 2 },
  size: 'middle',
  useCard: false,
  schema: [...useDetailSchema(), ...useDetailExtraSchema()],
});

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [RelateModal, relateModalApi] = useVbenModal({
  connectedComponent: RelateForm,
  destroyOnClose: true,
});

async function loadDetail() {
  const current = detailRequest.begin();
  const id = elementId.value;
  loading.value = true;
  try {
    await loadElementNameCaches();
    if (!current()) return;
    const data = await getStandardElement(id);
    if (!current()) return;
    const view: ElementDetailView = {
      ...data,
      categoryName:
        data.categoryName || String(formatCategoryName(data.categoryId)),
      contactNumber: data.contactNumber || formatUserMobile(data.personCharge),
      creatorName: data.creatorName || String(formatPersonName(data.creator)),
      documentName: String(formatDocumentName(data.documentId)),
      personChargeName:
        data.personChargeName || String(formatPersonName(data.personCharge)),
      stdCode: String(formatDocumentStdCode(data.documentId)),
      stdType: data.standardType,
      updaterName: String(formatPersonName(data.updater)),
    };
    if (data.documentId) {
      try {
        const doc = await getDoc(data.documentId);
        view.stdType = doc?.stdType;
        view.stdCode = doc?.stdCode || view.stdCode;
        view.documentName = doc?.nameCn || view.documentName;
      } catch {
        // 标准文件信息仅用于展示
      }
    }
    view.dictType = undefined;
    view.codeSetName = undefined;
    if (data.codeSetId) {
      try {
        const codeSet = await getStandardCodeSet(data.codeSetId);
        view.dictType = codeSet?.type;
        view.codeSetName = codeSet?.name;
      } catch {
        // 关联码表不存在时仍展示基础信息
      }
    }
    if (current()) element.value = view;
  } finally {
    if (current()) loading.value = false;
  }
}

function handleBack() {
  tabs.closeCurrentTab();
  router.push({
    path: String(
      route.meta.activePath || '/model/standard-data-element/data-element',
    ),
  });
}

function handleEdit() {
  formModalApi.setData({ id: elementId.value }).open();
}

function handleRelate() {
  relateModalApi
    .setData({
      id: elementId.value,
      codeSetId: element.value.codeSetId,
    })
    .open();
}

watch(
  () => [route.params.id, access.accessToken, access.refreshToken],
  () => {
    elementId.value = Number(route.params.id);
    if (!Number.isSafeInteger(elementId.value) || elementId.value <= 0) {
      message.warning('参数错误，标准数据元不能为空');
      handleBack();
      return;
    }
    const requestedTab = route.query.tab;
    activeTab.value =
      typeof requestedTab === 'string' &&
      [
        'audit',
        'clean',
        'codeset',
        'detail',
        'impact',
        'mapping',
        'models',
        'assets',
        'relation',
      ].includes(requestedTab)
        ? (requestedTab === 'relation' ? 'assets' : requestedTab)
        : 'models';
    extendedTabs.value = ['codeset', 'impact', 'mapping'].includes(
      activeTab.value,
    );
    void loadDetail();
  },
  { immediate: true },
);
</script>

<template>
  <Page
    auto-content-height
    class="qdata-standard"
    content-class="flex min-h-0 flex-col gap-[14px]"
  >
    <FormModal @success="loadDetail" /><RelateModal @success="loadDetail" />
    <Spin :spinning="loading" class="shrink-0">
      <section class="standard-detail-header">
        <div class="standard-detail-title">
          <span class="standard-detail-id" :title="String(element.id ?? '')">{{ element.id }}</span>
          <strong>{{ element.name || '数据元' }}</strong>
          <span
            class="standard-status-tag"
            :class="{ disabled: element.status !== 0 }"
          >
            {{ element.status === 0 ? '启用' : '禁用' }}
          </span>
          <DetailBackButton class="ml-auto" @click="handleBack" />
        </div>
        <dl class="standard-detail-grid">
          <dt>英文名称</dt>
          <dd>{{ element.engName || '-' }}</dd>
          <dt>标准数据元类目</dt>
          <dd>{{ element.categoryName || '-' }}</dd>
          <dt>类型</dt>
          <dd><span class="standard-type-tag">数据元</span></dd>
          <dt>描述</dt>
          <dd class="standard-description">{{ element.description || '-' }}</dd>
          <dt>创建人</dt>
          <dd>{{ element.creatorName || '-' }}</dd>
          <dt>创建时间</dt>
          <dd>
            {{
              element.createTime
                ? formatDate(element.createTime, 'YYYY-MM-DD HH:mm')
                : '-'
            }}
          </dd>
          <dt>更新时间</dt>
          <dd>
            {{
              element.updateTime
                ? formatDate(element.updateTime, 'YYYY-MM-DD HH:mm')
                : '-'
            }}
          </dd>
        </dl>
      </section>
    </Spin>
    <section class="standard-detail-body">
      <Tabs
        v-model:active-key="activeTab"
        :items="tabItems"
        class="standard-detail-tabs"
      >
        <template #rightExtra>
          <Dropdown>
            <Button size="small"
              ><template #icon
                ><IconifyIcon icon="lucide:ellipsis-vertical" /></template
              >更多</Button
            ><template #popupRender>
              <Menu>
                <MenuItem @click="openExtended('codeset')">
                  <template #icon><IconifyIcon icon="lucide:link" /></template>
                  关联标准码表 </MenuItem
                ><MenuItem @click="openExtended('mapping')"
                  ><template #icon
                    ><IconifyIcon icon="lucide:list-checks" /></template
                  >数据元落标</MenuItem
                ><MenuItem @click="openExtended('impact')"
                  ><template #icon
                    ><IconifyIcon icon="lucide:git-fork" /></template
                  >影响分析</MenuItem
                >
              </Menu>
            </template>
          </Dropdown>
        </template>
        <template #contentRender="{ item }">
          <RuleBindings
            v-if="item.key === 'clean' && activeTab === 'clean' && element.id"
            :element-id="elementId"
            kind="CLEAN"
          />
          <RuleBindings
            v-else-if="
              item.key === 'audit' && activeTab === 'audit' && element.id
            "
            :element-id="elementId"
            kind="AUDIT"
          />
          <div
            v-else-if="item.key === 'mapping' && activeTab === 'mapping'"
            class="standard-associations"
          >
            <div class="h-[420px]">
              <MappedColumnsGrid :element-id="elementId" />
            </div>
            <ModelFieldsGrid :element-id="elementId" />
          </div>
          <ModelAssociations
            v-else-if="item.key === 'models' && activeTab === 'models'"
            :owner-id="elementId"
            kind="element"
          />
          <AssociationTable
            v-else-if="item.key === 'assets' && activeTab === 'assets'"
            :owner-id="elementId"
            kind="element"
          />
          <ItemGrid
            v-else-if="item.key === 'codeset' && activeTab === 'codeset'"
            :code-set-name="element.codeSetName"
            :dict-type="element.dictType"
            @relate="handleRelate"
          />
          <ImpactPanel
            v-else-if="item.key === 'impact' && activeTab === 'impact'"
            :element-id="elementId"
          />
          <div
            v-else-if="item.key === 'detail' && activeTab === 'detail'"
            class="standard-extensions"
          >
            <Button
              v-access:code="['governance:standard-element:update']"
              class="mb-4"
              :disabled="element.lifecycleStatus === 'DEPRECATED'"
              @click="handleEdit"
            >
              <template #icon><IconifyIcon icon="lucide:edit" /></template>
              修改数据元 </Button
            ><ExtraDescriptions :data="element" />
          </div>
        </template>
      </Tabs>
    </section>
  </Page>
</template>

<style scoped>
.qdata-standard :deep(.standard-detail-table .ant-table-thead > tr > th),
.qdata-standard :deep(.standard-detail-table .ant-table-tbody > tr > td) {
  font-size: 12px;
}

.standard-detail-body {
  flex: none;
  min-height: 0;
}
</style>

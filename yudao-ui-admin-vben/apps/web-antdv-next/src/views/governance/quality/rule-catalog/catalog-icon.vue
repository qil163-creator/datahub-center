<script setup lang="ts">
defineProps<{
  name:
    | 'columns'
    | 'document'
    | 'eye'
    | 'folder'
    | 'refresh'
    | 'reset'
    | 'search';
}>();
</script>

<template>
  <svg
    class="catalog-icon"
    viewBox="0 0 20 20"
    fill="none"
    stroke="currentColor"
    stroke-width="1.4"
    aria-hidden="true"
  >
    <template v-if="name === 'search'">
      <circle cx="8" cy="8" r="5.5" />
      <path d="m12 12 5 5" />
    </template>
    <template v-else-if="name === 'refresh'">
      <path d="M16 6a7 7 0 1 0 1 7M16 2v5h-5" />
    </template>
    <template v-else-if="name === 'columns'">
      <path
        fill="currentColor"
        stroke="none"
        d="M3 3h5v5H3zm9 0h5v5h-5zM3 12h5v5H3zm9 0h5v5h-5z"
      />
    </template>
    <template v-else-if="name === 'folder'">
      <path d="M2 6V3h6l2 2h8v3M2 7h16l-2 10H2z" />
    </template>
    <template v-else-if="name === 'document'">
      <path d="M4 2h12v16H4zM7 6h6M7 10h6M7 14h4" />
    </template>
    <template v-else-if="name === 'eye'">
      <path d="M1 10s3-6 9-6 9 6 9 6-3 6-9 6-9-6-9-6Z" />
      <circle cx="10" cy="10" r="2.5" />
    </template>
    <template v-else>
      <path d="M3 8h14v9H3zM7 8V4h6v4M6 12v5M10 12v5M14 12v5" />
    </template>
  </svg>
</template>

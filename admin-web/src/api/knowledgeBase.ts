import { http } from '@/utils/http/axios'

function search(data, params: { current: number; size: number }) {
  return http.request({
    url: `/admin/kb/search?currentPage=${params.current}&pageSize=${params.size}`,
    method: 'post',
    data,
  })
}

function deleteOne(uuid: string) {
  return http.request({
    url: `/admin/kb/del/${uuid}`,
    method: 'POST',
  })
}

function edit(data) {
  return http.request({
    url: '/admin/kb/edit',
    method: 'post',
    data,
  })
}

/**
 * @description: 获取用户信息
 */
function getInfo(uuid: string) {
  return http.request({
    url: `/admin/kb/info/${uuid}`,
    method: 'get',
  })
}

async function uploadDocs(uuid: string, files: File[], indexTypes: string[] = ['embedding']) {
  const response = await http.uploadFiles<{
    success: boolean
    message?: string
    data: unknown
  }>(
    // `uploadFiles()` bypasses the JSON request wrapper, so keep the `/api`
    // prefix here and target the management-only endpoint explicitly.
    { url: `/api/admin/kb/uploadDocs/${uuid}` },
    files,
    {
      indexAfterUpload: 'true',
      indexTypes: indexTypes.join(','),
    }
  )
  if (!response.data.success) {
    throw new Error(response.data.message || '文档上传失败')
  }
  // Keep the same `{ data }` contract as the common admin request wrapper so
  // the workbench can consume a batch result without a separate code path.
  return { data: response.data.data }
}

function searchItems(kbUuid: string, params: { current: number; size: number }, keyword = '') {
  return http.request({
    url: '/admin/kb/items/search',
    method: 'get',
    params: {
      kbUuid,
      keyword,
      currentPage: params.current,
      pageSize: params.size,
    },
  })
}

function indexItems(uuids: string[], indexTypes: string[]) {
  const query = new URLSearchParams()
  uuids.forEach((uuid) => query.append('uuids', uuid))
  indexTypes.forEach((type) => query.append('indexTypes', type))
  return http.request({
    url: `/admin/kb/items/indexing-list?${query.toString()}`,
    method: 'post',
  })
}

function checkIndexing(kbUuid: string) {
  return http.request({
    url: '/admin/kb/indexing/check',
    method: 'get',
    params: { kbUuid },
  })
}

function listEmbeddings(itemUuid: string, current = 1, size = 10) {
  return http.request({
    url: `/admin/kb/items/embeddings/${itemUuid}`,
    method: 'get',
    params: { currentPage: current, pageSize: size },
  })
}

function listGraph(itemUuid: string, limit = 100) {
  return http.request({
    url: `/admin/kb/items/graph/${itemUuid}`,
    method: 'get',
    params: { maxVertexId: Number.MAX_SAFE_INTEGER, maxEdgeId: Number.MAX_SAFE_INTEGER, limit },
  })
}

export default {
  search,
  getInfo,
  edit,
  deleteOne,
  uploadDocs,
  searchItems,
  indexItems,
  checkIndexing,
  listEmbeddings,
  listGraph,
}

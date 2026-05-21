// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 触发图谱构建 POST /api/graph/build */
export async function triggerBuild(body?: { documentIds?: number[] }, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: { taskId: number; status: string }; message?: string }>(
    '/graph/build',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body || {},
      ...(options || {}),
    },
  )
}

/** 获取构建任务列表 GET /api/graph/tasks */
export async function listTasks(options?: { [key: string]: any }) {
  return request<{ code?: number; data?: API.GraphTask[]; message?: string }>('/graph/tasks', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 获取单个任务 GET /api/graph/tasks/{taskId} */
export async function getTask(taskId: number, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: API.GraphTask; message?: string }>(`/graph/tasks/${taskId}`, {
    method: 'GET',
    ...(options || {}),
  })
}

/** 搜索实体 GET /api/graph/search */
export async function searchEntities(
  params: { keyword: string; limit?: number },
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: API.GraphEntity[]; message?: string }>('/graph/search', {
    method: 'GET',
    params,
    ...(options || {}),
  })
}

/** 子图查询 GET /api/graph/subgraph */
export async function getSubgraph(
  params: { entityName: string; hops?: number },
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: { nodes: any[]; edges: any[] }; message?: string }>(
    '/graph/subgraph',
    {
      method: 'GET',
      params,
      ...(options || {}),
    },
  )
}

/** 图谱统计 GET /api/graph/stats */
export async function getGraphStats(options?: { [key: string]: any }) {
  return request<{ code?: number; data?: { nodeCount: number; relCount: number }; message?: string }>(
    '/graph/stats',
    {
      method: 'GET',
      ...(options || {}),
    },
  )
}

/** 图谱详细统计 GET /api/graph/stats/detail */
export async function getStatsDetail(options?: { [key: string]: any }) {
  return request<{
    code?: number
    data?: {
      nodeCount: number
      relCount: number
      typeDistribution: { type: string; cnt: number }[]
      recentTasks: API.GraphTask[]
    }
    message?: string
  }>('/graph/stats/detail', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 删除图谱 DELETE /api/graph/graph-data */
export async function deleteGraph(
  confirmToken: string,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: { deleted: number }; message?: string }>(
    '/graph/graph-data',
    {
      method: 'DELETE',
      params: { confirmToken },
      ...(options || {}),
    },
  )
}

/** SSE 订阅构建进度 GET /api/graph/build/progress/{taskId} */
export function getBuildProgressUrl(taskId: number): string {
  return `/api/graph/build/progress/${taskId}`
}

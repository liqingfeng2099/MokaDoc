<template>
  <div id="knowledge-graph" class="content-page active">
    <div class="graph-container">
      <!-- 左侧：文档选择 + 构建操作 -->
      <div class="graph-left">
        <div class="graph-left-header">
          <h3>文档选择</h3>
          <a-select
            v-model:value="selectedDocumentId"
            class="form-control"
            placeholder="全部文档"
            allow-clear
            style="width: 100%"
            @change="handleDocFilterChange"
          >
            <a-select-option value="">全部文档</a-select-option>
            <a-select-option
              v-for="doc in documents"
              :key="doc.id"
              :value="doc.id"
            >
              {{ doc.name }}
            </a-select-option>
          </a-select>
        </div>

        <!-- 构建操作区 -->
        <div class="graph-left-actions">
          <a-button
            type="primary"
            block
            :loading="buildLoading"
            @click="triggerBuild"
          >
            {{ buildLoading ? '构建中...' : '🔨 构建图谱' }}
          </a-button>
          <a-button block @click="triggerIncremental" :disabled="buildLoading">
            🔄 增量更新
          </a-button>

          <!-- 构建进度条 -->
          <div v-if="buildTask" class="build-progress">
            <div class="progress-label">
              <a-tag :color="statusColor">{{ statusLabel }}</a-tag>
              <span>{{ buildTask.progress }}%</span>
            </div>
            <a-progress
              :percent="buildTask.progress"
              :status="buildTask.status === 'FAILED' ? 'exception' : 'active'"
              :stroke-color="{ from: '#4C5BA8', to: '#7B88D1' }"
            />
            <p v-if="buildTask.errorMessage" class="error-msg">
              {{ buildTask.errorMessage }}
            </p>
          </div>
        </div>

        <!-- 图谱搜索 -->
        <div class="graph-left-search">
          <a-input-search
            v-model:value="searchQuery"
            placeholder="搜索实体..."
            @search="handleSearch"
          />
        </div>

        <!-- 搜索结果 -->
        <div v-if="searchResults.length > 0" class="search-results">
          <div
            v-for="item in searchResults"
            :key="item.entityId"
            class="search-result-item"
            @click="focusEntity(item)"
          >
            <a-tag :color="entityTypeColor(item.type)">{{ item.type }}</a-tag>
            <span>{{ item.name }}</span>
          </div>
        </div>

        <!-- 图例 -->
        <div class="graph-legend">
          <h3>图例</h3>
          <div class="legend-item">
            <span class="legend-color" style="background: #4C5BA8"></span>
            <span>概念</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background: #4A9B7F"></span>
            <span>人物</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background: #C8904A"></span>
            <span>组织</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background: #C4545C"></span>
            <span>项目</span>
          </div>
          <div class="legend-item">
            <span class="legend-color" style="background: #7A7A8A"></span>
            <span>其他</span>
          </div>
        </div>

        <!-- 构建任务历史 -->
        <div class="graph-tasks">
          <h3>构建历史 <a-button size="small" type="link" @click="refreshTasks">🔄</a-button></h3>
          <div v-if="taskList.length === 0" class="task-empty">暂无构建记录</div>
          <div v-else class="task-items">
            <div
              v-for="t in taskList.slice(0, 5)"
              :key="t.id"
              class="task-item"
            >
              <a-tag :color="taskStatusColor(t.status)">{{ taskStatusLabel(t.status) }}</a-tag>
              <span class="task-time">{{ formatTaskTime(t.createTime) }}</span>
              <a-progress
                v-if="t.status === 'PROCESSING'"
                :percent="t.progress || 0"
                :show-info="false"
                size="small"
              />
            </div>
          </div>
        </div>

        <!-- 删除图谱 -->
        <div class="graph-delete">
          <a-button danger block @click="confirmDeleteGraph">🗑️ 删除图谱</a-button>
        </div>
      </div>

      <!-- 中间：图谱可视化 -->
      <div class="graph-middle">
        <div class="graph-toolbar">
          <a-button size="small" @click="zoomIn">🔍 放大</a-button>
          <a-button size="small" @click="zoomOut">🔭 缩小</a-button>
          <a-button size="small" @click="resetZoom">🔄 重置</a-button>
          <a-button size="small" @click="fitGraph">📐 适应</a-button>
          <a-button size="small" @click="exportGraph">📷 导出</a-button>
          <span class="toolbar-spacer"></span>
          <a-button size="small" :type="showQualityPanel ? 'primary' : 'default'" @click="toggleQualityPanel">📊 质量监控</a-button>
          <span class="toolbar-stats">节点 {{ qualityStats.nodeCount }} · 关系 {{ qualityStats.relCount }}</span>
        </div>

        <!-- 质量监控面板 -->
        <div v-if="showQualityPanel" class="quality-panel">
          <div class="quality-charts">
            <div class="chart-container" ref="pieChartRef"></div>
            <div class="chart-container" ref="barChartRef"></div>
          </div>
        </div>

        <div class="graph-visualization" ref="graphContainerRef">
          <!-- vis-network 渲染在此 -->
          <div v-if="!graphReady && !buildTask" class="graph-placeholder">
            <p>暂无图谱数据</p>
            <p class="graph-placeholder-hint">
              请先选择文档并点击"构建图谱"
            </p>
          </div>
          <div v-if="buildTask?.status === 'PROCESSING'" class="graph-placeholder">
            <a-spin tip="图谱构建中，请稍候..." />
          </div>
        </div>
      </div>

      <!-- 右侧：节点详情 -->
      <div class="graph-right">
        <div class="graph-right-header">
          <h3>节点详情</h3>
        </div>

        <div v-if="selectedNode" class="node-details">
          <div class="node-type">
            <span>类型：</span>
            <a-tag :color="entityTypeColor(selectedNode.type)">
              {{ selectedNode.type }}
            </a-tag>
          </div>
          <div class="node-info">
            <h4>{{ selectedNode.name }}</h4>
            <p v-if="selectedNode.description">
              {{ selectedNode.description }}
            </p>
            <div class="node-relations">
              <h5>关联实体</h5>
              <ul>
                <li
                  v-for="(rel, idx) in selectedNodeRelations"
                  :key="idx"
                  @click="focusEntity(rel)"
                >
                  → {{ rel.name }}
                  <a-tag size="small">{{ rel.relType }}</a-tag>
                </li>
              </ul>
            </div>
          </div>
          <a-button type="primary" block @click="linkToQA">
            💬 在问答中使用
          </a-button>
        </div>

        <div v-else class="node-details-empty">
          <p>点击图谱中的节点查看详情</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Network } from 'vis-network'
import { DataSet } from 'vis-data'
import { list as listDocuments } from '@/api/wendangguanli'
import {
  triggerBuild as apiTriggerBuild,
  listTasks,
  searchEntities as apiSearchEntities,
  getSubgraph as apiGetSubgraph,
  getGraphStats,
  getStatsDetail,
  getBuildProgressUrl,
  deleteGraph,
} from '@/api/tupuguanli'
import { useSSEFetch, createAbortController } from '@/utils/sse'
import { message, Modal } from 'ant-design-vue'
import * as echarts from 'echarts'

const router = useRouter()

// ==================== 类型定义 ====================
interface Document {
  id: number
  name: string
}

interface GraphEntity {
  entityId: string
  name: string
  type: string
  neo4jId?: number
}

interface GraphRelation {
  fromEntityId: string
  toEntityId: string
  type: string
  relType?: string
}

interface BuildTask {
  id: number
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
  progress: number
  errorMessage?: string
}

interface SearchResult extends GraphEntity {
  relType?: string
}

// ==================== 响应式状态 ====================
const documents = ref<Document[]>([])
const selectedDocumentId = ref<string | number>('')
const searchQuery = ref('')
const searchResults = ref<SearchResult[]>([])
const graphReady = ref(false)
const buildLoading = ref(false)
const buildTask = ref<BuildTask | null>(null)
const graphContainerRef = ref<HTMLElement | null>(null)

const selectedNode = ref<GraphEntity | null>(null)
const selectedNodeRelations = ref<SearchResult[]>([])

// vis-network 实例
let networkInstance: Network | null = null
let nodesDataSet: DataSet<any> | null = null
let edgesDataSet: DataSet<any> | null = null

// 质量监控
const showQualityPanel = ref(false)
const qualityStats = ref({ nodeCount: 0, relCount: 0 })
const taskList = ref<BuildTask[]>([])
const pieChartRef = ref<HTMLElement | null>(null)
const barChartRef = ref<HTMLElement | null>(null)
let pieChartInstance: echarts.ECharts | null = null
let barChartInstance: echarts.ECharts | null = null

// 删除状态
const deleteModalVisible = ref(false)
const deleteConfirmInput = ref('')
const deleteStep = ref(1)

// ==================== 计算属性 ====================
const statusColor = computed(() => {
  switch (buildTask.value?.status) {
    case 'COMPLETED': return 'green'
    case 'PROCESSING': return 'blue'
    case 'FAILED': return 'red'
    default: return 'default'
  }
})

const statusLabel = computed(() => {
  switch (buildTask.value?.status) {
    case 'COMPLETED': return '已完成'
    case 'PROCESSING': return '构建中'
    case 'FAILED': return '失败'
    case 'PENDING': return '排队中'
    default: return '未知'
  }
})

// ==================== 生命周期 ====================
onMounted(() => {
  fetchDocuments()
  initNetwork()
  refreshTasks()
})

onUnmounted(() => {
  if (buildAbortController) {
    buildAbortController.abort()
    buildAbortController = null
  }
  if (networkInstance) {
    networkInstance.destroy()
    networkInstance = null
  }
  if (pieChartInstance) {
    pieChartInstance.dispose()
    pieChartInstance = null
  }
  if (barChartInstance) {
    barChartInstance.dispose()
    barChartInstance = null
  }
})

// ==================== 文档加载 ====================
async function fetchDocuments() {
  try {
    const res = await listDocuments()
    console.log('📂 [图谱-文档列表] response:', res.data)
    if (res.data.code === 0 && res.data.data) {
      documents.value = res.data.data.map((doc: any) => ({
        id: doc.id!,
        name: doc.name || '',
      }))
      console.log('📂 [图谱-文档列表] 加载成功:', documents.value.length, '篇')
    } else {
      console.warn('📂 [图谱-文档列表] code不为0:', res.data)
    }
  } catch (e) {
    console.error('获取文档列表失败', e)
  }
}

// ==================== vis-network 初始化 ====================
function initNetwork() {
  if (!graphContainerRef.value) return

  nodesDataSet = new DataSet([])
  edgesDataSet = new DataSet([])

  networkInstance = new Network(
    graphContainerRef.value,
    { nodes: nodesDataSet, edges: edgesDataSet },
    {
      nodes: {
        shape: 'dot',
        size: 24,
        font: { size: 14, face: 'PingFang SC, Microsoft YaHei', color: '#2C2C3A' },
        borderWidth: 2,
        color: {
          background: '#F9F7F3',
          border: '#4C5BA8',
        },
        chosen: {
          node: (values: any) => {
            values.borderWidth = 3
            values.shadowSize = 12
            values.shadowColor = 'rgba(76, 91, 168, 0.3)'
          },
        },
      },
      edges: {
        width: 2,
        color: { color: '#A8A8B4', highlight: '#4C5BA8' },
        arrows: { to: { enabled: true, scaleFactor: 0.6 } },
        smooth: { type: 'continuous' },
        font: { size: 10, color: '#7A7A8A', align: 'middle' },
      },
      physics: {
        solver: 'barnesHut',
        barnesHut: {
          gravitationalConstant: -2000,
          centralGravity: 0.3,
          springLength: 95,
          springConstant: 0.04,
          damping: 0.09,
          avoidOverlap: 0.1,
        },
        stabilization: {
          enabled: true,
          iterations: 200,
          updateInterval: 25,
          onlyDynamicEdges: false,
          fit: true,
        },
        maxVelocity: 50,
        minVelocity: 0.1,
        timestep: 0.5,
        adaptiveTimestep: true,
      },
      interaction: {
        hover: true,
        tooltipDelay: 200,
        zoomView: true,
        dragView: true,
      },
      layout: {
        improvedLayout: true,
      },
    },
  )

  // 点击节点事件
  networkInstance.on('click', (params: any) => {
    if (params.nodes.length > 0) {
      const nodeId = params.nodes[0]
      const node = nodesDataSet?.get(nodeId) as any
      if (node) {
        selectedNode.value = {
          entityId: node.entityId || nodeId,
          name: node.label || '',
          type: node.group || 'Concept',
        }
        loadNodeRelations(node.entityId || nodeId)
      }
    } else {
      selectedNode.value = null
      selectedNodeRelations.value = []
    }
  })
}

// SSE 中断控制器
let buildAbortController: AbortController | null = null

// ==================== 图谱构建 ====================
async function triggerBuild() {
  if (buildLoading.value) return

  buildLoading.value = true
  buildTask.value = {
    id: 0,
    status: 'PROCESSING',
    progress: 0,
  }

  try {
    // 调用后端构建 API
    const docIds = selectedDocumentId.value
      ? [Number(selectedDocumentId.value)]
      : undefined
    const res = await apiTriggerBuild({ documentIds: docIds })

    if (res.data.code === 0 && res.data.data) {
      const taskId = res.data.data.taskId
      buildTask.value!.id = taskId

      // 通过 SSE 监听构建进度
      subscribeBuildProgress(taskId)
    } else {
      buildTask.value!.status = 'FAILED'
      buildTask.value!.errorMessage = res.data.message || '构建失败'
      buildLoading.value = false
      message.error('构建失败: ' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    buildTask.value!.status = 'FAILED'
    buildTask.value!.errorMessage = e.message || '网络错误'
    buildLoading.value = false
    message.error('构建请求失败: ' + (e.message || '网络错误'))
  }
}

function subscribeBuildProgress(taskId: number) {
  buildAbortController = createAbortController()

  useSSEFetch(
    getBuildProgressUrl(taskId),
    {},
    {
      method: 'GET',
      signal: buildAbortController.signal,
      onMessage: (data: string) => {
        try {
          const parsed = JSON.parse(data)
          if (parsed.type === 'graph_progress') {
            buildTask.value!.status = parsed.status
            buildTask.value!.progress = parsed.progress

            if (parsed.status === 'COMPLETED') {
              buildLoading.value = false
              graphReady.value = true
              loadGraphForCurrentDoc()
              message.success(parsed.message || '图谱构建完成！')
            } else if (parsed.status === 'FAILED') {
              buildLoading.value = false
              buildTask.value!.errorMessage = parsed.message
              message.error(parsed.message || '构建失败')
            }
          }
        } catch {
          // 非 JSON 数据，忽略
        }
      },
      onComplete: () => {
        buildAbortController = null
      },
      onError: (error: any) => {
        console.error('SSE progress error:', error)
        buildLoading.value = false
        buildAbortController = null
      },
    },
  )
}

async function loadGraphForCurrentDoc() {
  // 构建完成后加载图谱统计和子图数据
  try {
    const statsRes = await getGraphStats()
    if (statsRes.data.code === 0 && statsRes.data.data) {
      const stats = statsRes.data.data
      console.log('图谱统计:', stats)
      // 有图谱数据才尝试渲染
      if ((stats.nodeCount || 0) > 0) {
        await loadExistingGraph()
        return
      }
    }
    loadMockGraphData()
  } catch {
    loadMockGraphData()
  }
}

async function loadExistingGraph() {
  // 尝试加载一个通用查询来获取初始展示数据
  try {
    const res = await apiSearchEntities({ keyword: '', limit: 20 })
    if (res.data.code === 0 && res.data.data && res.data.data.length > 0) {
      const firstEntity = res.data.data[0]
      const subRes = await apiGetSubgraph({ entityName: firstEntity.name || '', hops: 1 })
      if (subRes.data.code === 0 && subRes.data.data) {
        renderSubgraph(subRes.data.data)
        graphReady.value = true
        return
      }
    }
  } catch {
    // fallback to mock
  }
  loadMockGraphData()
}

function triggerIncremental() {
  message.info('增量更新功能将在后续版本实现')
}

// ==================== 图谱搜索 ====================
async function handleSearch(query: string) {
  if (!query.trim()) {
    searchResults.value = []
    return
  }
  try {
    const res = await apiSearchEntities({ keyword: query, limit: 10 })
    if (res.data.code === 0 && res.data.data) {
      searchResults.value = res.data.data.map((e: any) => ({
        entityId: e.entityId || e.neo4jId?.toString() || '',
        name: e.name || '',
        type: e.type || 'Concept',
      }))
    }
  } catch {
    console.error('搜索失败')
    searchResults.value = []
  }
}

// ==================== 工具栏操作 ====================
function zoomIn() {
  networkInstance?.moveTo({ scale: (networkInstance.getScale() || 1) * 1.3 })
}

function zoomOut() {
  networkInstance?.moveTo({ scale: (networkInstance.getScale() || 1) * 0.7 })
}

function resetZoom() {
  networkInstance?.moveTo({ scale: 1, position: { x: 0, y: 0 } })
}

function fitGraph() {
  networkInstance?.fit({ animation: true })
}

function exportGraph() {
  message.info('导出功能将在后续版本实现')
}

// ==================== 节点交互 ====================
function focusEntity(entity: GraphEntity | SearchResult) {
  if (!networkInstance || !nodesDataSet) return
  const nodeId = (entity as any).entityId
  networkInstance.selectNodes([nodeId])
  networkInstance.focus(nodeId, { scale: 1.5, animation: true })

  selectedNode.value = {
    entityId: nodeId,
    name: entity.name,
    type: entity.type,
  }
  loadNodeRelations(nodeId)
}

async function loadNodeRelations(entityId: string) {
  try {
    const node = nodesDataSet?.get(entityId) as any
    const entityName = node?.label || selectedNode.value?.name || ''
    if (!entityName) return

    const res = await apiGetSubgraph({ entityName, hops: 1 })
    if (res.data.code === 0 && res.data.data) {
      const subgraph = res.data.data
      const relations: SearchResult[] = []

      // 从 nodes 中提取关联实体
      if (subgraph.nodes) {
        for (const n of subgraph.nodes) {
          if (n.name !== entityName) {
            relations.push({
              entityId: n.entityId || n.name,
              name: n.name,
              type: n.type || 'Concept',
              relType: subgraph.edges?.find((e: any) =>
                e.fromEntityId === entityId || e.toEntityId === entityId
              )?.type || 'RELATED_TO',
            })
          }
        }
      }
      selectedNodeRelations.value = relations

      // 渲染子图到 vis-network
      renderSubgraph(subgraph)
    }
  } catch {
    console.error('加载关联实体失败')
    selectedNodeRelations.value = []
  }
}

function renderSubgraph(subgraph: { nodes: any[]; edges: any[] }) {
  if (!nodesDataSet || !edgesDataSet) return

  // 清空现有数据
  nodesDataSet.clear()
  edgesDataSet.clear()

  const colorMap: Record<string, string> = {
    Concept: '#E8EAF6', Person: '#E8F5E9',
    Organization: '#FFF3E0', Project: '#FFEBEE',
  }
  const borderMap: Record<string, string> = {
    Concept: '#4C5BA8', Person: '#4A9B7F',
    Organization: '#C8904A', Project: '#C4545C',
  }

  const nodeItems: any[] = []
  const edgeItems: any[] = []

  if (subgraph.nodes) {
    for (const n of subgraph.nodes) {
      nodeItems.push({
        id: n.entityId || n.name,
        label: n.name,
        group: n.type || 'Concept',
        entityId: n.entityId || n.name,
        color: {
          background: colorMap[n.type] || '#F9F7F3',
          border: borderMap[n.type] || '#7A7A8A',
        },
      })
    }
  }

  if (subgraph.edges) {
    for (const e of subgraph.edges) {
      edgeItems.push({
        from: e.fromEntityId || e.from,
        to: e.toEntityId || e.to,
        label: e.type || 'RELATED_TO',
      })
    }
  }

  nodesDataSet.add(nodeItems)
  edgesDataSet.add(edgeItems)

  // 大图性能优化：>100 节点时降低稳定化精度
  const totalNodes = nodeItems.length
  if (totalNodes > 100 && networkInstance) {
    networkInstance.setOptions({
      physics: {
        stabilization: { iterations: 50 },
        solver: 'barnesHut',
      },
    } as any)
  }

  nextTick(() => {
    networkInstance?.fit({ animation: true })
  })
}

// ==================== 辅助功能 ====================
function handleDocFilterChange() {
  // 文档筛选逻辑（后续实现）
}

function entityTypeColor(type: string): string {
  const colors: Record<string, string> = {
    Concept: 'blue',
    Person: 'green',
    Organization: 'orange',
    Project: 'red',
  }
  return colors[type] || 'default'
}

function linkToQA() {
  if (selectedNode.value) {
    router.push({
      path: '/intelligent-qa',
      query: { entity: selectedNode.value.name },
    })
  }
}

// ==================== 模拟图谱数据 ====================
function loadMockGraphData() {
  if (!nodesDataSet || !edgesDataSet) return

  const mockNodes = [
    { id: 'e1', label: '机器学习', group: 'Concept', entityId: 'e1', color: { background: '#E8EAF6', border: '#4C5BA8' } },
    { id: 'e2', label: '监督学习', group: 'Concept', entityId: 'e2', color: { background: '#E8EAF6', border: '#4C5BA8' } },
    { id: 'e3', label: '神经网络', group: 'Concept', entityId: 'e3', color: { background: '#E8EAF6', border: '#4C5BA8' } },
    { id: 'e4', label: '深度学习', group: 'Concept', entityId: 'e4', color: { background: '#E8EAF6', border: '#4C5BA8' } },
    { id: 'e5', label: '吴恩达', group: 'Person', entityId: 'e5', color: { background: '#E8F5E9', border: '#4A9B7F' } },
  ]

  const mockEdges = [
    { from: 'e1', to: 'e2', label: '包含', entityId: 'e1_e2' },
    { from: 'e2', to: 'e3', label: '使用', entityId: 'e2_e3' },
    { from: 'e1', to: 'e4', label: '关联', entityId: 'e1_e4' },
    { from: 'e3', to: 'e4', label: '关联', entityId: 'e3_e4' },
    { from: 'e5', to: 'e1', label: '讲授', entityId: 'e5_e1' },
  ]

  nodesDataSet.add(mockNodes)
  edgesDataSet.add(mockEdges)
  graphReady.value = true

  nextTick(() => {
    networkInstance?.fit({ animation: true })
  })
}

// ==================== 质量监控 ====================

function toggleQualityPanel() {
  showQualityPanel.value = !showQualityPanel.value
  if (showQualityPanel.value) {
    nextTick(() => {
      loadQualityCharts()
    })
  }
}

async function loadQualityCharts() {
  try {
    const res = await getStatsDetail()
    if (res.data.code === 0 && res.data.data) {
      const detail = res.data.data
      qualityStats.value = {
        nodeCount: detail.nodeCount || 0,
        relCount: detail.relCount || 0,
      }
      renderPieChart(detail.typeDistribution || [])
      renderBarChart(detail.recentTasks || [])
    }
  } catch {
    console.error('加载质量数据失败')
  }
}

function renderPieChart(typeDist: { type: string; cnt: number }[]) {
  if (!pieChartRef.value) return
  if (!pieChartInstance) {
    pieChartInstance = echarts.init(pieChartRef.value)
  }

  const colorMap: Record<string, string> = {
    Concept: '#4C5BA8', Person: '#4A9B7F',
    Organization: '#C8904A', Project: '#C4545C',
  }

  pieChartInstance.setOption({
    title: { text: '实体类型分布', left: 'center', textStyle: { fontSize: 13, color: '#2C2C3A' } },
    tooltip: { trigger: 'item' },
    color: ['#4C5BA8', '#4A9B7F', '#C8904A', '#C4545C', '#7A7A8A'],
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      data: typeDist.map(d => ({
        name: d.type || '未知',
        value: d.cnt || 0,
      })),
      label: { show: true, formatter: '{b}: {c}' },
    }],
  })
}

function renderBarChart(tasks: BuildTask[]) {
  if (!barChartRef.value) return
  if (!barChartInstance) {
    barChartInstance = echarts.init(barChartRef.value)
  }

  const statusCounts: Record<string, number> = { COMPLETED: 0, FAILED: 0, PROCESSING: 0, PENDING: 0 }
  for (const t of tasks) {
    const s = t.status || 'PENDING'
    statusCounts[s] = (statusCounts[s] || 0) + 1
  }

  barChartInstance.setOption({
    title: { text: '构建任务统计', left: 'center', textStyle: { fontSize: 13, color: '#2C2C3A' } },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: ['已完成', '失败', '处理中', '排队中'] },
    yAxis: { type: 'value' },
    color: ['#4A9B7F'],
    series: [{
      type: 'bar',
      data: [statusCounts.COMPLETED, statusCounts.FAILED, statusCounts.PROCESSING, statusCounts.PENDING],
      itemStyle: { borderRadius: [4, 4, 0, 0] },
    }],
  })
}

async function refreshTasks() {
  try {
    const res = await listTasks()
    if (res.data.code === 0 && res.data.data) {
      taskList.value = res.data.data.map((t: any) => ({
        id: t.id || 0,
        status: t.status || 'PENDING',
        progress: t.progress || 0,
        errorMessage: t.errorMessage,
        documentIds: t.documentIds,
        createTime: t.createTime,
        updateTime: t.updateTime,
      }))
    }
  } catch {
    console.error('刷新任务列表失败')
  }
}

function taskStatusColor(status?: string): string {
  switch (status) {
    case 'COMPLETED': return 'green'
    case 'PROCESSING': return 'blue'
    case 'FAILED': return 'red'
    default: return 'default'
  }
}

function taskStatusLabel(status?: string): string {
  switch (status) {
    case 'COMPLETED': return '已完成'
    case 'PROCESSING': return '处理中'
    case 'FAILED': return '失败'
    case 'PENDING': return '排队中'
    default: return '未知'
  }
}

function formatTaskTime(time?: string): string {
  if (!time) return ''
  const d = new Date(time)
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`
}

// ==================== 删除确认（三步） ====================

function confirmDeleteGraph() {
  deleteStep.value = 1
  deleteConfirmInput.value = ''

  Modal.confirm({
    title: '⚠️ 删除图谱数据',
    content: '此操作将删除您的所有知识图谱数据（实体节点和关系），不可恢复。\n\n请点击"确定"继续确认。',
    okText: '我已知晓，继续',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onOk: () => {
      confirmDeleteStep2()
    },
  })
}

function confirmDeleteStep2() {
  deleteStep.value = 2

  Modal.confirm({
    title: '⚠️⚠️ 再次确认',
    content: '删除后，图谱相关的问答功能将无法使用图谱上下文。\n\n确定要删除吗？此操作不可撤销。',
    okText: '确定删除',
    cancelText: '再想想',
    okButtonProps: { danger: true },
    onOk: () => {
      confirmDeleteStep3()
    },
  })
}

function confirmDeleteStep3() {
  deleteStep.value = 3

  Modal.confirm({
    title: '⚠️⚠️⚠️ 最终确认',
    content: '请输入 "DELETE" 以确认删除全部图谱数据：',
    okText: '确认删除',
    cancelText: '取消',
    okButtonProps: { danger: true, disabled: true },
    onOk: async () => {
      try {
        const res = await deleteGraph('DELETE')
        if (res.data.code === 0) {
          message.success('图谱数据已删除')
          graphReady.value = false
          qualityStats.value = { nodeCount: 0, relCount: 0 }
          if (nodesDataSet) nodesDataSet.clear()
          if (edgesDataSet) edgesDataSet.clear()
          refreshTasks()
        } else {
          message.error(res.data.message || '删除失败')
        }
      } catch {
        message.error('删除请求失败')
      }
    },
  })

  // 检测输入框变化（Ant Design Vue Modal 不支持动态 disabled，这里简化处理）
  setTimeout(() => {
    const inputEl = document.querySelector('.ant-modal input') as HTMLInputElement
    if (inputEl) {
      inputEl.placeholder = '请输入 DELETE'
      inputEl.addEventListener('input', () => {
        const okBtn = document.querySelector('.ant-modal .ant-btn-dangerous') as HTMLButtonElement
        if (okBtn) {
          okBtn.disabled = inputEl.value !== 'DELETE'
        }
      })
    }
  }, 100)
}
</script>

<style scoped>
/* ==================== 容器布局 ==================== */
.graph-container {
  display: flex;
  height: calc(100vh - 64px);
  gap: 0;
}

/* ==================== 左侧面板 ==================== */
.graph-left {
  width: 260px;
  min-width: 260px;
  background: var(--surface-color, #FFFFFF);
  border-right: 1px solid var(--border-color, #E6E3DC);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 16px 14px;
  gap: 16px;
}

.graph-left-header h3,
.graph-legend h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-color, #2C2C3A);
  margin-bottom: 10px;
}

.graph-left-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.build-progress {
  margin-top: 4px;
}

.progress-label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-secondary, #7A7A8A);
}

.error-msg {
  color: var(--danger-color, #C4545C);
  font-size: 12px;
  margin-top: 4px;
}

.graph-left-search {
  margin-top: 4px;
}

.search-results {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.search-result-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: var(--radius-sm, 6px);
  cursor: pointer;
  transition: background var(--transition-fast, 0.18s) var(--transition-ease, ease);
  font-size: 13px;
}

.search-result-item:hover {
  background: var(--surface-hover, #FAF9F6);
}

/* ==================== 图例 ==================== */
.graph-legend {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--border-color, #E6E3DC);
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
  color: var(--text-secondary, #7A7A8A);
}

.legend-color {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* ==================== 中间：图谱可视化 ==================== */
.graph-middle {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--bg-warm, #F9F7F3);
  min-width: 0;
}

.graph-toolbar {
  display: flex;
  gap: 6px;
  padding: 10px 14px;
  background: var(--surface-color, #FFFFFF);
  border-bottom: 1px solid var(--border-color, #E6E3DC);
}

.graph-visualization {
  flex: 1;
  position: relative;
  min-height: 400px;
}

.graph-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted, #A8A8B4);
  font-size: 16px;
}

.graph-placeholder-hint {
  font-size: 13px;
  margin-top: 8px;
  color: var(--text-secondary, #7A7A8A);
}

/* ==================== 右侧：节点详情 ==================== */
.graph-right {
  width: 280px;
  min-width: 280px;
  background: var(--surface-color, #FFFFFF);
  border-left: 1px solid var(--border-color, #E6E3DC);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.graph-right-header {
  padding: 16px;
  border-bottom: 1px solid var(--border-color, #E6E3DC);
}

.graph-right-header h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-color, #2C2C3A);
}

.node-details {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.node-details-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted, #A8A8B4);
  font-size: 14px;
  padding: 24px;
  text-align: center;
}

.node-type {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary, #7A7A8A);
}

.node-info h4 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-color, #2C2C3A);
  margin-bottom: 6px;
}

.node-info p {
  font-size: 13px;
  color: var(--text-secondary, #7A7A8A);
  line-height: 1.6;
}

.node-relations {
  margin-top: 8px;
}

.node-relations h5 {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-color, #2C2C3A);
  margin-bottom: 6px;
}

.node-relations ul {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.node-relations li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  font-size: 13px;
  color: var(--primary-color, #4C5BA8);
  border-radius: var(--radius-sm, 6px);
  cursor: pointer;
  transition: background var(--transition-fast, 0.18s) var(--transition-ease, ease);
}

.node-relations li:hover {
  background: var(--primary-soft, rgba(76, 91, 168, 0.08));
}

/* ==================== 构建历史 ==================== */
.graph-tasks {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--border-color, #E6E3DC);
}

.graph-tasks h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-color, #2C2C3A);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.task-empty {
  font-size: 12px;
  color: var(--text-muted, #A8A8B4);
  padding: 8px 0;
}

.task-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.task-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.task-time {
  color: var(--text-secondary, #7A7A8A);
  white-space: nowrap;
}

/* ==================== 删除按钮 ==================== */
.graph-delete {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border-color, #E6E3DC);
}

/* ==================== 工具栏扩展 ==================== */
.toolbar-spacer {
  flex: 1;
}

.toolbar-stats {
  font-size: 12px;
  color: var(--text-secondary, #7A7A8A);
  white-space: nowrap;
}

/* ==================== 质量监控面板 ==================== */
.quality-panel {
  background: var(--surface-color, #FFFFFF);
  border-bottom: 1px solid var(--border-color, #E6E3DC);
  padding: 12px;
}

.quality-charts {
  display: flex;
  gap: 16px;
}

.chart-container {
  flex: 1;
  height: 240px;
  background: var(--bg-warm, #F9F7F3);
  border-radius: var(--radius-sm, 6px);
}
</style>

declare namespace API {
  type BaseResponseBoolean = {
    code?: number
    data?: boolean
    message?: string
  }

  type BaseResponseDocumentVO = {
    code?: number
    data?: DocumentVO
    message?: string
  }

  type BaseResponseListDocumentVO = {
    code?: number
    data?: DocumentVO[]
    message?: string
  }

  type BaseResponseLoginVO = {
    code?: number
    data?: LoginVO
    message?: string
  }

  type BaseResponseLong = {
    code?: number
    data?: number
    message?: string
  }

  type BaseResponsePageQaMessage = {
    code?: number
    data?: PageQaMessage
    message?: string
  }

  type BaseResponsePageQaSession = {
    code?: number
    data?: PageQaSession
    message?: string
  }

  type BaseResponseQaSession = {
    code?: number
    data?: QaSession
    message?: string
  }

  type BaseResponseUserVO = {
    code?: number
    data?: UserVO
    message?: string
  }

  type checkMd5Params = {
    md5: string
  }

  type CreateSessionRequest = {
    sessionName?: string
    summary?: string
  }

  type deleteSessionParams = {
    sessionId: number
  }

  type deleteUsingDELETEParams = {
    id: number
  }

  type DocumentUpdateRequest = {
    id?: number
    name?: string
    description?: string
  }

  type DocumentVO = {
    id?: number
    name?: string
    fileType?: string
    fileSize?: number
    description?: string
    fileMd5?: string
    uploadTime?: string
  }

  type getByIdParams = {
    id: number
  }

  type getUserByIdParams = {
    id: number
  }

  type listChatParams = {
    sessionId: number
    pageSize?: number
    lastCreateTime?: string
  }

  type listSessionsParams = {
    pageSize?: number
    lastCreateTime?: string
  }

  type LoginVO = {
    user?: UserVO
    token?: string
  }

  type PageQaMessage = {
    records?: QaMessage[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageQaSession = {
    records?: QaSession[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type QaMessage = {
    id?: number
    sessionId?: number
    messageType?: number
    content?: string
    createTime?: string
    deleteFlag?: number
  }

  type QaSession = {
    id?: number
    userId?: number
    sessionName?: string
    summary?: string
    createTime?: string
    updateTime?: string
    deleteFlag?: number
  }

  type SseEmitter = {
    timeout?: number
  }

  type updateParams = {
    id: number
  }

  type updateSessionParams = {
    sessionId: number
  }

  type UpdateSessionRequest = {
    sessionName?: string
    summary?: string
  }

  type UserLoginRequest = {
    account?: string
    password?: string
  }

  type UserQuestion = {
    content?: string
    sessionId?: number
    documentIds?: number[]
  }

  type UserRegisterRequest = {
    username?: string
    email?: string
    password?: string
    nickname?: string
  }

  type UserVO = {
    id?: number
    username?: string
    email?: string
    nickname?: string
    avatar?: string
    status?: number
    registerTime?: string
    updateTime?: string
    password?: string
  }

  // ========== 图谱模块类型 ==========

  type GraphTask = {
    id?: number
    userId?: number
    status?: string       // PENDING | PROCESSING | COMPLETED | FAILED
    documentIds?: string
    progress?: number
    totalBatches?: number
    completedBatches?: number
    errorMessage?: string
    createTime?: string
    updateTime?: string
  }

  type GraphEntity = {
    entityId?: string
    name?: string
    type?: string
    neo4jId?: number
    sourceDocIds?: string
  }

  type GraphStats = {
    nodeCount?: number
    relCount?: number
  }
}

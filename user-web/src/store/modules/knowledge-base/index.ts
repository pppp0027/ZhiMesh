import { defineStore } from 'pinia'

export const useKbStore = defineStore('kb-store', {
  state: (): KnowledgeBase.KbState => {
    return {
      selectedKbType: 'mine',
      activeKbUuid: 'default',
      myKbInfos: [],
      teamKbInfos: [],
      companyKbInfos: [],
      kbUuidToQaRecords: new Map<string, KnowledgeBase.QaRecordInfo[]>(),
      kbUuidToStarInfo: new Map<string, KnowledgeBase.KbStarInfo>(),
      qaRecordToEmbeddingRef: new Map<string, KnowledgeBase.QaRecordEmbeddingRef[]>(),
      qaRecordToGraphRef: new Map<string, KnowledgeBase.QaRecordGraphRef>(),
      loadingGraphRef: new Map<string, boolean>(),
      loadingRecords: new Map<string, boolean>(),
      loaddingKbList: false,
      kbListLoaded: false,
      reloadKbInfosSignal: false,
    }
  },

  getters: {
    getRecords(state: KnowledgeBase.KbState) {
      return (kbUuid: string) => {
        const records = state.kbUuidToQaRecords.get(kbUuid)
        if (records)
          return records

        return []
      }
    },
    getReferences(state: KnowledgeBase.KbState) {
      return (qaRecordUuid: string) => {
        const references = state.qaRecordToEmbeddingRef.get(qaRecordUuid)
        if (references)
          return references
        return []
      }
    },
    getGraphRef(state: KnowledgeBase.KbState) {
      return (qaRecordUuid: string) => {
        const graphRef = state.qaRecordToGraphRef.get(qaRecordUuid)
        if (graphRef)
          return graphRef
        return null
      }
    },
    isLoadingGraphRef(state: KnowledgeBase.KbState) {
      return (qaRecordUuid: string) => {
        const loading = state.loadingGraphRef.get(qaRecordUuid)
        if (loading)
          return loading
        return false
      }
    },
    getSelectedKb(state: KnowledgeBase.KbState) {
      let kbInfo = state.myKbInfos.find(item => item.uuid === state.activeKbUuid)
      if (kbInfo)
        return kbInfo
      kbInfo = state.teamKbInfos.find(item => item.uuid === state.activeKbUuid)
      if (kbInfo)
        return kbInfo
      kbInfo = state.companyKbInfos.find(item => item.uuid === state.activeKbUuid)
      if (kbInfo)
        return kbInfo
      return null
    },
  },

  actions: {
    setActive(kbUuid: string) {
      this.activeKbUuid = kbUuid
    },
    setLoadingKbList(status: boolean) {
      this.loaddingKbList = status
    },
    setKbListLoaded(status: boolean) {
      this.kbListLoaded = status
    },
    setLoadingRecords(currKbUuid: string, status: boolean) {
      this.loadingRecords.set(currKbUuid, status)
    },
    setReloadKbInfosSignal(signal: boolean) {
      this.reloadKbInfosSignal = signal
    },
    setMyKbInfos(infos: KnowledgeBase.Info[]) {
      this.myKbInfos = infos
    },
    setTeamKbInfos(infos: KnowledgeBase.Info[]) {
      this.teamKbInfos = infos
    },
    setCompanyKbInfos(infos: KnowledgeBase.Info[]) {
      this.companyKbInfos = infos
    },
    appendMyNewKbInfo(kbInfo: KnowledgeBase.Info) {
      this.myKbInfos.unshift(kbInfo)
    },
    /** 按 ownerType 把库 upsert 进对应分区列表（我的/团队/企业）。 */
    upsertKbInfo(kbInfo: KnowledgeBase.Info) {
      const target = kbInfo.ownerType === 'TEAM'
        ? this.teamKbInfos
        : kbInfo.ownerType === 'COMPANY' ? this.companyKbInfos : this.myKbInfos
      const index = target.findIndex(item => item.uuid === kbInfo.uuid)
      if (index >= 0)
        Object.assign(target[index], kbInfo)
      else
        target.unshift(kbInfo)
    },
    deleteKbInfo(kbUuid: string) {
      const removeFrom = (infos: KnowledgeBase.Info[]) => {
        const index = infos.findIndex(item => item.uuid === kbUuid)
        if (index >= 0)
          infos.splice(index, 1)
      }

      const records = this.kbUuidToQaRecords.get(kbUuid) || []
      records.forEach((record) => {
        this.qaRecordToEmbeddingRef.delete(record.uuid)
        this.qaRecordToGraphRef.delete(record.uuid)
        this.loadingGraphRef.delete(record.uuid)
      })
      removeFrom(this.myKbInfos)
      removeFrom(this.teamKbInfos)
      removeFrom(this.companyKbInfos)
      this.kbUuidToQaRecords.delete(kbUuid)
      this.kbUuidToStarInfo.delete(kbUuid)
      this.loadingRecords.delete(kbUuid)
      if (this.activeKbUuid === kbUuid)
        this.activeKbUuid = 'default'
    },
    appendRecord(kbUuid: string, record: KnowledgeBase.QaRecordInfo) {
      let existRecords = this.kbUuidToQaRecords.get(kbUuid)
      if (!existRecords)
        existRecords = []
      existRecords.forEach(item => item.loading = false)
      // The first live record can arrive before the initial history request.
      // Keep it in the map and make appends idempotent so a history refresh or
      // an SSE reconnect cannot render/process the same QA twice.
      const existing = existRecords.find(item => item.uuid === record.uuid)
      if (existing)
        Object.assign(existing, record)
      else
        existRecords.push(record)
      this.kbUuidToQaRecords.set(kbUuid, existRecords)
    },
    appendRecords(kbUuid: string, records: KnowledgeBase.QaRecordInfo[]) {
      let existRecords = this.kbUuidToQaRecords.get(kbUuid)
      if (!existRecords) {
        existRecords = []
        this.kbUuidToQaRecords.set(kbUuid, existRecords)
      }
      // API pages are newest-first. Do not mutate the response array and do
      // not append an item already inserted optimistically by the composer.
      for (const record of [...records].reverse()) {
        const existing = existRecords.find(item => item.uuid === record.uuid)
        if (existing) {
          Object.assign(existing, record)
          continue
        }
        const recordTime = Date.parse(record.createTime || '')
        const insertAt = existRecords.findIndex((item) => {
          const itemTime = Date.parse(item.createTime || '')
          return Number.isFinite(recordTime) && Number.isFinite(itemTime) && recordTime < itemTime
        })
        if (insertAt < 0)
          existRecords.push(record)
        else
          existRecords.splice(insertAt, 0, record)
      }
    },
    appendChunk(kbUuid: string, tmpRecordUuid: string, chunk: string) {
      const existRecords = this.kbUuidToQaRecords.get(kbUuid)
      if (!existRecords)
        return
      const hitRecord = existRecords.find((item: { uuid: string }) => item.uuid === tmpRecordUuid)
      if (hitRecord)
        hitRecord.answer = hitRecord.answer + chunk
    },
    updateRecord(kbUuid: string, tmpRecordUuid: string, source: KnowledgeBase.QaRecordInfo) {
      const existRecords = this.kbUuidToQaRecords.get(kbUuid)
      if (!existRecords)
        return
      const hitRecord = existRecords.find((item: { uuid: string }) => item.uuid === tmpRecordUuid)
      if (hitRecord)
        Object.assign(hitRecord, source)
    },
    updateFirst(kbUuid: string, source: KnowledgeBase.QaRecordInfo) {
      const existRecords = this.kbUuidToQaRecords.get(kbUuid)
      if (!existRecords || existRecords.length < 1)
        return
      Object.assign(existRecords[0], source)
    },

    unshiftRecord(kbUuid: string, record: KnowledgeBase.QaRecordInfo) {
      let existRecords = this.kbUuidToQaRecords.get(kbUuid)
      if (!existRecords) {
        existRecords = []
        this.kbUuidToQaRecords.set(kbUuid, existRecords)
      }
      existRecords.unshift(record)
    },

    deleteRecord(kbUuid: string, recordUuid: string) {
      const existRecords = this.kbUuidToQaRecords.get(kbUuid)
      if (!existRecords)
        return

      const index = existRecords.findIndex((item: { uuid: string }) => item.uuid === recordUuid)
      if (index >= 0)
        existRecords.splice(index, 1)
    },

    clearRecords(kbUuid: string) {
      this.kbUuidToQaRecords.set(kbUuid, [])
    },

    appStarInfos(starInfos: KnowledgeBase.KbStarInfo[]) {
      starInfos.forEach((item) => {
        // Default true(from remote server)
        if (!item.star)
          item.star = true

        this.kbUuidToStarInfo.set(item.kbUuid, item)
      })
    },

    insertOrUpdateStarInfo(starInfo: KnowledgeBase.KbStarInfo) {
      const existData = this.kbUuidToStarInfo.get(starInfo.kbUuid)
      if (!existData)
        this.kbUuidToStarInfo.set(starInfo.kbUuid, starInfo)
      else
        existData.star = starInfo.star
    },

    setQaRecordReferences(qaRecordUuid: string, references: KnowledgeBase.QaRecordEmbeddingRef[]) {
      this.qaRecordToEmbeddingRef.set(qaRecordUuid, !references ? [] : references)
    },

    setQaRecordGraphRef(qaRecordUuid: string, graphRef: KnowledgeBase.QaRecordGraphRef) {
      this.qaRecordToGraphRef.set(qaRecordUuid, graphRef)
    },

    setLoadingGraphRef(qaRecordUuid: string, loading: boolean) {
      this.loadingGraphRef.set(qaRecordUuid, loading)
    },
  },
})

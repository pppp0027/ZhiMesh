declare namespace Team {
  interface Info {
    id: string
    uuid: string
    name: string
    remark: string
    myRole: TeamRole
    memberCount?: number
    createTime?: string
    updateTime?: string
  }

  interface InfoListResp {
    total: number
    records: Info[]
  }

  type TeamRole = 'OWNER' | 'CONTRIBUTOR' | 'READER'

  interface EditReq {
    id?: string
    uuid?: string
    name: string
    remark?: string
  }

  interface MemberAddReq {
    teamUuid: string
    email: string
    role?: TeamRole
  }

  interface MemberUpdateReq {
    teamUuid: string
    userId: string
    role: TeamRole
  }

  interface MemberInfo {
    userId: string
    uuid: string
    name: string
    email: string
    avatar: string
    role: TeamRole
    createTime?: string
  }
}

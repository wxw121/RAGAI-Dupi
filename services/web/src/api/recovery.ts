import { apiDelete, apiGet, apiPost } from './client'
import type { RecoveryArchive, RecoveryRestore } from '@/types'

const base = (kbId: string) => `/api/v1/knowledge-bases/${kbId}/recovery`

export const listArchives = (kbId: string) => apiGet<RecoveryArchive[]>(`${base(kbId)}/archives`)
export const createArchive = (kbId: string) => apiPost<RecoveryArchive>(`${base(kbId)}/archives`)
export const retryArchive = (kbId: string, archiveId: string) => apiPost<RecoveryArchive>(`${base(kbId)}/archives/${archiveId}/retry`)
export const deleteArchive = (kbId: string, archiveId: string) => apiDelete(`${base(kbId)}/archives/${archiveId}`)
export const getArchiveDownloadUrl = (kbId: string, archiveId: string) => `${base(kbId)}/archives/${archiveId}/download`
export const listRestores = (kbId: string) => apiGet<RecoveryRestore[]>(`${base(kbId)}/restores`)
export const createRestore = (kbId: string, archiveId: string) => apiPost<RecoveryRestore>(`${base(kbId)}/restores`, { archiveId })
export const retryRestore = (kbId: string, jobId: string) => apiPost<RecoveryRestore>(`${base(kbId)}/restores/${jobId}/retry`)
export const abandonRestore = (kbId: string, jobId: string) => apiDelete(`${base(kbId)}/restores/${jobId}`)

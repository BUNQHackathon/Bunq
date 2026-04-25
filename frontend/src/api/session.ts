import { API_BASE, getJson, postJson } from './client';

// --- Types ---

export type SessionState =
  | 'CREATED'
  | 'UPLOADING'
  | 'EXTRACTING'
  | 'MAPPING'
  | 'SCORING'
  | 'SANCTIONS'
  | 'COMPLETE'
  | 'FAILED';

export type PipelineStage =
  | 'INGEST'
  | 'EXTRACT_OBLIGATIONS'
  | 'EXTRACT_CONTROLS'
  | 'SANCTIONS_SCREEN'
  | 'MAP_OBLIGATIONS_CONTROLS'
  | 'GAP_ANALYZE'
  | 'GROUND_CHECK'
  | 'NARRATE';

export type CounterpartyType = 'individual' | 'company' | 'organization' | 'government' | 'unknown';
export type Severity = 'low' | 'medium' | 'high' | 'critical';
export type DeonticOperator = 'O' | 'F' | 'P';
export type ObligationType = 'preventive' | 'detective' | 'corrective' | 'disclosure';
export type ControlType = 'technical' | 'organizational' | 'procedural';
export type ControlCategory = 'preventive' | 'detective' | 'corrective';
export type ImplementationStatus = 'planned' | 'in_progress' | 'implemented' | 'unclear';
export type TestingStatus = 'passed' | 'failed' | 'pending' | 'unknown';
export type MappingType = 'direct' | 'partial' | 'requires_multiple';
export type GapStatus = 'satisfied' | 'gap' | 'partial' | 'under_review';
export type GapType = 'control_missing' | 'control_weak' | 'control_untested' | 'control_expired';
export type SanctionMatchStatus = 'clear' | 'flagged' | 'under_review';
export type Priority = 'high' | 'medium' | 'low';

export interface Counterparty {
  name: string;
  country: string;
  type: CounterpartyType;
}

export interface Session {
  id: string;
  state: SessionState;
  regulation?: string;
  policy?: string;
  counterparties?: string[];
  documentIds?: string[];
  verdict?: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSessionRequest {
  regulation?: string;
  policy?: string;
}

// --- Document library types ---

export interface DocumentPresignRequest {
  filename: string;
  contentType: string;
  sha256: string;
}

export interface DocumentPresignResponse {
  incomingKey: string;
  uploadUrl: string;
  expiresInSeconds: number;
}

export type DocumentKind = 'regulation' | 'policy' | 'brief' | 'evidence' | 'audio' | 'other';

export interface DocumentFinalizeRequest {
  incomingKey: string;
  filename: string;
  contentType: string;
  kind: DocumentKind;
}

export interface LibraryDocument {
  id: string;
  filename: string;
  displayName?: string | null;
  contentType: string;
  sizeBytes: number;
  kind: string;
  firstSeenAt: string;
  lastUsedAt: string;
  extractedText: string | null;
  extractedAt: string | null;
  pageCount: number | null;
  obligationsExtracted: boolean;
  controlsExtracted: boolean;
}

export interface DocumentFinalizeResponse {
  document: LibraryDocument;
  deduped: boolean;
}

export interface AttachDocumentResponse {
  sessionId: string;
  documentIds: string[];
}

export interface DocumentListResponse {
  documents: LibraryDocument[];
  nextCursor: string | null;
}

// --- Evidence types ---

export interface EvidencePresignRequest {
  filename: string;
  contentType: string;
  sha256: string;
}

export interface EvidencePresignResponse {
  s3Key: string;
  uploadUrl: string;
  expiresInSeconds: number;
}

export interface EvidenceFinalizeRequest {
  s3Key: string;
  mappingId?: string;
  description?: string;
}

export interface PipelineStartRequest {
  regulation?: string;
  policy?: string;
  counterparties?: Counterparty[];
  briefText?: string;
}

export interface Obligation {
  id: string;
  source: {
    regulation?: string;
    article?: string;
    section?: string;
    paragraph?: number;
    sourceText?: string;
    retrievedFromKbChunkId?: string;
  };
  obligationType: ObligationType;
  deontic: DeonticOperator;
  subject?: string;
  action?: string;
  conditions?: string[];
  riskCategory?: string;
  applicableJurisdictions?: string[];
  applicableEntities?: string[];
  severity: Severity;
  regulatoryPenaltyRange?: string;
  extractedAt: string;
  extractionConfidence?: number;
  sessionId: string;
  regulationId?: string;
}

export interface Control {
  id: string;
  controlType: ControlType;
  category: ControlCategory;
  description?: string;
  owner?: string;
  testingCadence?: string;
  evidenceType?: string;
  lastTested?: string;
  testingStatus: TestingStatus;
  implementationStatus: ImplementationStatus;
  mappedStandards?: string[];
  linkedTools?: string[];
  sourceDocRef?: { bank?: string; doc?: string; sectionId?: string; kbChunkId?: string };
  sessionId: string;
  bankId?: string;
}

export interface Mapping {
  id: string;
  obligationId: string;
  controlId: string;
  mappingConfidence?: number;
  mappingType: MappingType;
  gapStatus: GapStatus;
  semanticReason?: string;
  structuralMatchTags?: string[];
  evidenceLinks?: string[];
  reviewerNotes?: string;
  lastReviewed?: string;
  sessionId: string;
}

export interface Gap {
  id: string;
  obligationId: string;
  gapType: GapType;
  gapStatus: GapStatus;
  severityDimensions?: {
    regulatoryUrgency?: number;
    penaltySeverity?: number;
    probability?: number;
    businessImpact?: number;
    combinedRiskScore?: number;
  };
  recommendedActions?: Array<{
    action?: string;
    priority?: Priority;
    effortDays?: number;
    suggestedOwner?: string;
  }>;
  remediationDeadline?: string;
  escalationRequired?: boolean;
  narrative?: string;
  sessionId: string;
}

export interface SanctionHit {
  id: string;
  sessionId: string;
  counterparty: Counterparty;
  matchStatus: SanctionMatchStatus;
  hits?: Array<{
    listSource?: string;
    entityName?: string;
    aliases?: string[];
    matchScore?: number;
    listVersionTimestamp?: string;
  }>;
  entityMetadata?: Record<string, string>;
  screenedAt: string;
}

export interface Evidence {
  id: string;
  relatedMappingId: string;
  evidenceType?: string;
  source?: string;
  collectedAt?: string;
  evidenceUrl?: string;
  sha256?: string;
  expiresAt?: string;
  confidenceScore?: number;
  humanReviewed?: boolean;
  reviewerId?: string;
  reviewTimestamp?: string;
  auditTrail?: Array<{
    action?: string;
    timestamp?: string;
    actor?: string;
    decision?: string;
    prevHash?: string;
  }>;
  sessionId: string;
}

export interface DagNode {
  id: string;
  type?: string;
  data?: Record<string, unknown>;
  label?: string;
  [k: string]: unknown;
}

export interface DagEdge {
  id?: string;
  source: string;
  target: string;
  data?: Record<string, unknown>;
  [k: string]: unknown;
}

export interface GraphDAG {
  nodes: DagNode[];
  edges?: DagEdge[];
  links?: DagEdge[];
}

// --- Session lifecycle ---

export function createSession(req?: CreateSessionRequest): Promise<Session> {
  return postJson<Session>('/sessions', req ?? {});
}

export function getSession(id: string): Promise<Session> {
  return getJson<Session>(`/sessions/${encodeURIComponent(id)}`);
}

export function listSessions(): Promise<Session[]> {
  return getJson<Session[]>('/sessions');
}

// --- Pipeline ---

export function startPipeline(sessionId: string, req?: PipelineStartRequest): Promise<void> {
  return postJson<void>(`/sessions/${encodeURIComponent(sessionId)}/pipeline/start`, req ?? {});
}

// --- Obligations / Controls / Mappings / Gaps / Sanctions ---

export function listObligations(sessionId: string): Promise<Obligation[]> {
  return getJson<Obligation[]>(`/sessions/${encodeURIComponent(sessionId)}/obligations`);
}

export function listControls(sessionId: string): Promise<Control[]> {
  return getJson<Control[]>(`/sessions/${encodeURIComponent(sessionId)}/controls`);
}

export function listMappings(sessionId: string): Promise<Mapping[]> {
  return getJson<Mapping[]>(`/sessions/${encodeURIComponent(sessionId)}/mappings`);
}

export function listGaps(sessionId: string): Promise<Gap[]> {
  return getJson<Gap[]>(`/gaps/list?sessionId=${encodeURIComponent(sessionId)}`);
}

export function listSanctions(sessionId: string): Promise<SanctionHit[]> {
  return getJson<SanctionHit[]>(`/sessions/${encodeURIComponent(sessionId)}/sanctions`);
}

// --- Document library ---

export function presignDocument(req: DocumentPresignRequest): Promise<DocumentPresignResponse> {
  return postJson<DocumentPresignResponse>('/documents/presign', req);
}

export function finalizeDocument(req: DocumentFinalizeRequest): Promise<DocumentFinalizeResponse> {
  return postJson<DocumentFinalizeResponse>('/documents/finalize', req);
}

export function attachDocument(sessionId: string, documentId: string): Promise<AttachDocumentResponse> {
  return postJson<AttachDocumentResponse>(
    `/sessions/${encodeURIComponent(sessionId)}/documents/${encodeURIComponent(documentId)}`,
    {}
  );
}

export async function detachDocument(sessionId: string, documentId: string): Promise<void> {
  const res = await fetch(
    `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/documents/${encodeURIComponent(documentId)}`,
    { method: 'DELETE' }
  );
  if (!res.ok) throw new Error(`Detach failed: ${res.status}`);
}

export function listLibraryDocuments(kind?: string, limit: number = 50): Promise<DocumentListResponse> {
  const qs = new URLSearchParams();
  if (kind) qs.set('kind', kind);
  qs.set('limit', String(limit));
  return getJson<DocumentListResponse>(`/documents?${qs.toString()}`);
}

export function getLibraryDocument(id: string): Promise<LibraryDocument> {
  return getJson<LibraryDocument>(`/documents/${encodeURIComponent(id)}`);
}

// --- Evidence / Proof-tree / Compliance map ---

export function presignEvidence(sessionId: string, req: EvidencePresignRequest): Promise<EvidencePresignResponse> {
  return postJson<EvidencePresignResponse>(
    `/sessions/${encodeURIComponent(sessionId)}/evidence/presign`,
    req
  );
}

export function finalizeEvidence(sessionId: string, req: EvidenceFinalizeRequest): Promise<Evidence> {
  return postJson<Evidence>(
    `/sessions/${encodeURIComponent(sessionId)}/evidence/finalize`,
    req
  );
}

export function getEvidence(id: string): Promise<Evidence> {
  return getJson<Evidence>(`/evidence/${encodeURIComponent(id)}`);
}

export function getProofTree(mappingId: string): Promise<GraphDAG> {
  return getJson<GraphDAG>(`/proof-tree/${encodeURIComponent(mappingId)}`);
}

export function getComplianceMap(sessionId: string): Promise<GraphDAG> {
  return getJson<GraphDAG>(`/sessions/${encodeURIComponent(sessionId)}/compliance-map`);
}

// --- Report ---

export function getReportUrl(sessionId: string): string {
  return `${API_BASE}/sessions/${encodeURIComponent(sessionId)}/report.pdf`;
}

export async function fetchReport(sessionId: string): Promise<Blob> {
  const res = await fetch(getReportUrl(sessionId));
  if (!res.ok) throw new Error(`Report not ready: ${res.status}`);
  return res.blob();
}

// --- Upload helper (PUT to presigned S3 URL) ---

export async function computeSha256Base64(file: File | Blob): Promise<string> {
  const buffer = await (file as Blob).arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return btoa(String.fromCharCode(...new Uint8Array(digest)));
}

export async function putToPresignedUrl(
  presignedUrl: string,
  file: File | Blob,
  contentType: string,
  sha256Base64: string,
): Promise<void> {
  const res = await fetch(presignedUrl, {
    method: 'PUT',
    headers: {
      'Content-Type': contentType,
      'x-amz-checksum-sha256': sha256Base64,
    },
    body: file,
  });
  if (!res.ok) throw new Error(`S3 upload failed: ${res.status}`);
}

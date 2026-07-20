# dupi-RAG

<!-- language-switch -->
[中文](README.zh-CN.md) | **English**


> V1.5.0 is the current release. It adds Parent-Child and QA-assisted indexing, a filterable Profile V2 Milvus superset, Combined weighted RRF, revision-bound quality gates, and Web readiness/gate comparisons. API and Web version: `1.5.0`.

See the [V1.5.0 release notes](docs/v1.5-release-notes.md) and [release runbook](docs/v1.5-release-runbook.md) before upgrading an existing deployment. Keep `CLASSIC` as the default until the current index revision passes the candidate-vs-classic quality gate.

> V1.4.2 added a read-only GET /api/v1/ops/governance-summary endpoint for OPS_ADMIN operators plus a smoke script and Pester check for the V1.4.1 upload, ingest, outbox, notification, and vector cleanup state.

## V1.4.2 Governance Ops

GET /api/v1/ops/governance-summary returns a compact read-only snapshot with generatedAt, uploadQuota, ingestJobs, ingestOutbox, failureNotifications, vectorCleanup, and alerts.

Smoke check: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-governance-summary.ps1 -BaseUrl http://localhost:8080 -ApiKey $env:DUPI_API_KEY -OutFile evidence/governance-summary-smoke.json

Focused Pester coverage currently passes 4 of 4: powershell -NoProfile -ExecutionPolicy Bypass -Command Import-Module Pester; Invoke-Pester -Path scripts/tests/smoke-governance-summary.Tests.ps1 -CI

Local Web validation on this workstation must use project npm scripts so services/web/scripts/node16-webcrypto.cjs loads the Node 16 WebCrypto shim. Do not invoke raw vite or vitest directly on Node 16.

> V1.4.1 adds persisted tenant/user upload quotas, idempotent per-file uploads, cancellable and leased ingest executions, stale callback protection, and deduplicated terminal-failure events with optional webhook delivery. API version: `1.4.1-SNAPSHOT`; Web version: `1.4.1`.

## V1.4.1 Upload Governance

The Web uploads each file independently with bounded concurrency and an `Idempotency-Key`. It shows retained and rolling-window quota, keeps failures isolated per file, supports transport abort/retry, and calls the ingest cancellation API after a job exists. Polling is serialized and aborted on unmount so an older response cannot overwrite newer state.

PostgreSQL is authoritative for upload reservations and ingest execution. Upload reservations move through `PENDING -> COMMITTED -> RELEASED`; retained quota counts active `PENDING` + `COMMITTED` reservations, while `RELEASED` reservations no longer consume retained bytes/documents. `attemptId` / `attemptExpiresAt` lease in-flight uploads so the stale-upload reconciler can either commit durable doc/job/outbox attempts or clean partial objects/jobs/docs before release. A retry of the same released idempotency key rechecks retained quota but does not double-charge rolling-window bytes. Ingest retries rotate `executionId`; Worker callbacks carry a monotonic `sequence`; stale, duplicate, or terminal-state callbacks are acknowledged as ignored. Redis uses ready and processing lists, a bounded reaper moves `requeueEligible` processing payloads back to ready, and a processing item is acknowledged only after terminal handling.

Terminal `FAILED`/`DEAD_LETTER` notifications are persisted once per job execution/status. With a webhook configured, due `PENDING`/`FAILED` rows are delivered with bounded backoff; 2xx responses become `DELIVERED`, and rows that reach the attempt limit become `EXHAUSTED`. Webhook delivery requires HTTPS by default, blocks local/metadata hosts unless explicitly allowed, can include `X-Dupi-Webhook-Secret`, and truncates sanitized error text.

| Variable | Default | Purpose |
|---|---:|---|
| `UPLOAD_QUOTA_ENABLED` | `true` | Enable persistent upload quota accounting |
| `UPLOAD_QUOTA_RETAINED_BYTES_LIMIT` | `1073741824` | Retained bytes per tenant/user |
| `UPLOAD_QUOTA_RETAINED_DOCUMENTS_LIMIT` | `1000` | Retained documents per tenant/user |
| `UPLOAD_QUOTA_WINDOW_BYTES_LIMIT` | `268435456` | Accepted bytes per rolling window |
| `UPLOAD_QUOTA_WINDOW_SECONDS` | `3600` | Rolling upload window |
| `UPLOAD_QUOTA_ATTEMPT_LEASE_SECONDS` | `300` | In-flight upload attempt lease before stale reconciliation |
| `UPLOAD_QUOTA_RECONCILIATION_BATCH_SIZE` | `50` | Max stale upload reservations claimed per reconciler pass |
| `UPLOAD_QUOTA_RECONCILIATION_CRON` | `0 */5 * * * *` | Stale upload reservation reconciler cadence |
| `INGEST_PROCESSING_QUEUE` | `dupi:ingest:jobs:processing` | Worker in-flight Redis list |
| `INGEST_LEASE_SECONDS` | `60` | PostgreSQL ingest claim lease |
| `INGEST_HEARTBEAT_INTERVAL_SECONDS` | `15` | Worker lease heartbeat during long operations |
| `INGEST_PROCESSING_REAP_INTERVAL_SECONDS` | `60` | Worker processing-list reaper cadence |
| `INGEST_PROCESSING_REAP_BATCH_SIZE` | `100` | Oldest-tail processing payloads inspected per reaper pass |
| `REDIS_RETRY_DELAY_SECONDS` | `1` | Worker Redis transient failure backoff |
| `WORKER_ID` | host/process derived | Stable claim owner identifier |
| `INGEST_FAILURE_NOTIFICATION_WEBHOOK_URL` | empty | Optional POST target for FAILED/DEAD_LETTER ingest events |
| `INGEST_FAILURE_NOTIFICATION_TIMEOUT_SECONDS` | `10` | Webhook delivery timeout |
| `INGEST_FAILURE_NOTIFICATION_MAX_ATTEMPTS` | `5` | Bounded webhook retry attempts before `EXHAUSTED` |
| `INGEST_FAILURE_NOTIFICATION_WEBHOOK_SECRET` | empty | Optional `X-Dupi-Webhook-Secret` header value |
| `INGEST_FAILURE_NOTIFICATION_MAX_ERROR_MESSAGE_LENGTH` | `512` | Sanitized webhook error-text cap |
| `INGEST_FAILURE_NOTIFICATION_ALLOW_INSECURE_WEBHOOK` | `false` | Permit non-HTTPS/local webhook targets for trusted local testing only |
| `INGEST_FAILURE_NOTIFICATION_DISPATCH_CRON` | `*/30 * * * * *` | Failure-notification dispatch cadence |

Key routes:

```bash
# User-visible quota; requires DOCUMENT_UPLOAD
curl http://localhost:8080/api/v1/upload-quota

# Idempotent single-file upload
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents \
  -H "Idempotency-Key: upload-20260718-001" \
  -F "file=@sample.pdf"

# Cancel queued/running ingest
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/cancel
```

See [the V1.4.1 release runbook](docs/v1.4.1-release-runbook.md) and the [design](docs/superpowers/specs/2026-07-18-v1.4.1-upload-governance-design.md). The latest local V1.4.1 release scan records image digest `sha256:eec613fab9cdd1d873b95172f98d42ade5989238e2b0f76761b6b4f63b86515a`, image size 640,389,450 bytes, no Python findings, and 22 accepted upstream-unfixed OS findings expiring 2026-08-15.

> V1.4.0 adds tenant-scoped, checksum-verified knowledge-base archives and idempotent restore into a new hidden knowledge base. It is an application recovery layer, not a replacement for PostgreSQL, MinIO, etcd, or Milvus infrastructure backups.

## V1.4 Verifiable Recovery

Operators with `KB_RECOVERY` use the **Recovery** tab to create, inspect, download, retry, and delete archives, and to create, retry, or abandon restores. Archive objects are sealed under `archives/{tenantId}/{archiveId}/` in a private recovery bucket; `manifest.json` is written last. A target stays hidden as `RESTORING` until objects, records, dense/sparse vectors, counts, schemas, and checksums verify.

Routes are below `/api/v1/knowledge-bases/{kbId}/recovery`. Commands return `202 Accepted`; the Web panel polls non-terminal jobs every three seconds. See [the recovery runbook](docs/v1.4-recovery-runbook.md).

| Variable | Default | Purpose |
|---|---:|---|
| `DUPI_RECOVERY_BUCKET` | `dupi-recovery` | Dedicated private MinIO bucket |
| `DUPI_RECOVERY_QUIESCENCE_TIMEOUT_SECONDS` | `300` | Wait for active KB mutations |
| `DUPI_RECOVERY_PAGE_SIZE` | `500` | Bounded vector snapshot page size |
| `DUPI_RECOVERY_MAX_CONCURRENT_JOBS` | `2` | Bounded archive/restore concurrency |

### V1.4.0 Release Gate

The Worker image installs CPU-only PyTorch from the official CPU wheel index, uses PyMilvus 2.5.18 with the current patched packaging toolchain, and runs as UID/GID `65534`. The gate runs `pip check` and imports the production Worker modules before scanning. Run it from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/scan-release.ps1 `
  -Image dupi-rag-worker:v1.4 `
  -OutputPath artifacts/v1.4-release-scan `
  -HighExceptionPath deploy/release-exceptions/v1.4.0.json
```

The scan exports the image's exact `pip freeze --all` result to `worker-requirements.lock.txt`, then audits that lock without resolving a second dependency graph. It accepts either a `pip-audit` executable on `PATH` or an installed `pip_audit` module through `python -m pip_audit`, retries transient audit failures up to three times, and falls back to the pinned `dupi-rag-pip-audit:2.10.1` container when host networking cannot reach OSV. Every path deletes stale output first and records the execution mode in `summary.md`. Use `-TrivySkipDbUpdate` only when the local Trivy database was refreshed separately. The structured exception release must match the normalized image tag, cover every active upstream-unfixed finding exactly, and expires on `2026-08-15`; fixable, expired, unused, or unmatched entries fail the gate. The release scan generates the dependency lock, pip-audit JSON, CycloneDX/Syft SBOM, Trivy version/result JSON, and `summary.md` under `artifacts/v1.4-release-scan`. The summary records the immutable image digest and Trivy vulnerability-database timestamp.

> V1.3 澧炲姞鍙樆鏂殑 RAG 璐ㄩ噺绛栫暐/鍩虹嚎銆佺増鏈寲 Retrieval Profile锛屼互鍙?Milvus 鍘熺敓 Sparse BM25 鐨勫洖濉€佸弻鍐欍€丼hadow銆丆utover 鍜?Rollback銆傜敓浜ч儴缃茶姹?Milvus 2.5.4锛涘崌绾у墠蹇呴』澶囦唤 Milvus/etcd/MinIO/PostgreSQL锛屽苟鍦ㄩ殧绂荤幆澧冨畬鎴愬洖濉笌鍥炴粴婕旂粌銆?

## V1.3 Sparse 杩佺Щ杩愮淮

姣忎釜 Profile 浣跨敤鐙珛闆嗗悎 `{MILVUS_COLLECTION}_sparse_{kbId}_v{version}`銆傝縼绉荤姸鎬佷緷娆′负 `PREPARING -> BACKFILLING -> DUAL_WRITING -> SHADOW_VALIDATING -> CUTOVER -> COMPLETED`锛屽け璐ヨ繘鍏?`FAILED`锛宍BACKFILLING` 鍙箓绛夐噸璇曘€俵egacy BM25 fallback 鐢辫縼绉昏褰曟寔涔呭寲鎺у埗锛屼粎鍏佽鍦ㄥ弻鍐欏拰 Shadow 闃舵鍚敤锛涘畬鎴愬悗鐢辨縺娲?Profile 姘镐箙椹卞姩 Sparse 鍐欏叆銆?

Cutover 瑕佹眰瑕嗙洊鐜?100%銆乪mbedding 缁村害涓€鑷淬€佸€欓€?Profile 鏈夊畬鍏ㄥ尮閰嶇殑 PASS 璇勬祴銆佸€欓€?P95 涓嶈秴杩囧熀绾?1.25 鍊嶃€乫allback rate 涓嶅鍔犮€俁ollback 鍙兘閲嶆柊婵€娲绘洿鏃т笖宸叉湁 PASS 璇佹嵁鐨?Profile銆傚垹闄ゆ枃妗ｄ細鍚屾娓呯悊 dense 闆嗗悎鍜岃鐭ヨ瘑搴撴墍鏈夌増鏈寲 Sparse 闆嗗悎銆?

鐪熷疄璇枡鍩哄噯鍛戒护锛?

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rag-retrieval-benchmark.ps1 `
  -KbId <kbId> -HybridProfileId <profileId> -RerankProfileId <rerankProfileId> `
  -ApiKey $env:DUPI_API_KEY -OutputPath artifacts/v13-real-benchmark.json
```

鑴氭湰浼氭牳瀵瑰疄闄呮ā寮忋€丳rofile 寮€鍏炽€侀€愮敤渚嬮樁娈垫帓鍚嶅拰鐩稿 VECTOR 鐨?rank delta锛涘疄闄呮湭鎵ц reranker 鏃剁洿鎺ュけ璐ャ€?

Worker 浣跨敤 CPU-only PyTorch 鍜?`BAAI/bge-reranker-base`锛岄粯璁ゅ湪鍚姩鐢熷懡鍛ㄦ湡鍔犺浇妯″瀷骞舵墽琛岄鐑帹鐞嗭紱Compose 閫氳繃 `hf_model_cache` 鎸佷箙鍖栨ā鍨嬬紦瀛樸€傞鐑け璐ヤ細鍦?`/health` 涓爣璁?Rerank 涓嶅彲鐢紝浣嗕笉闃绘柇 VECTOR/HYBRID锛涘喎鍚姩寤惰繜涓嶅緱涓庣儹鎬?P95 娣风敤銆?

> 璐﹀彿 / RBAC 涓?ops 绠＄悊鏉冮檺鏇存柊璁板綍瑙?[docs/rbac-ops-admin-2026-07-06.md](docs/rbac-ops-admin-2026-07-06.md)锛涙憚鍏?outbox銆佸垹闄?tombstone銆佸疄渚嬬骇鎺堟潈涓庡璁¤繍缁村寮鸿 [docs/outbox-tombstone-rbac-ops-2026-07-07.md](docs/outbox-tombstone-rbac-ops-2026-07-07.md)銆?
> V1.1锛圓PI `0.1.1-SNAPSHOT` / Web `0.1.1`锛夋柊澧炵湡瀹炴祻瑙堝櫒 E2E 闂ㄧ銆佹憚鍏ヨ瘖鏂€佺煡璇嗗簱璇︽儏 `RAG 璇勪及`銆佷笂浼犳不鐞嗘彁绀轰笌鑱氬悎杩愮淮鍛婅锛涜璁′笌瀹炴柦璁板綍瑙?[docs/superpowers/specs/2026-07-12-v1.1-observability-evaluation-design.md](docs/superpowers/specs/2026-07-12-v1.1-observability-evaluation-design.md) 涓?[docs/superpowers/plans/2026-07-12-v1.1-observability-evaluation-implementation.md](docs/superpowers/plans/2026-07-12-v1.1-observability-evaluation-implementation.md)銆?
> V1.2锛圓PI `0.1.2-SNAPSHOT` / Web `0.1.2`锛夋墿灞曠湡瀹炴祻瑙堝櫒闂ㄧ锛屾柊澧炴枃妗ｇ储寮曡鎯呫€佺粨鏋勫寲 Chat 閿欒銆佹寔涔呭寲 RAG 璇勪及鐢ㄤ緥/鍘嗗彶銆佹贩鍚堟绱笌 Rerank 鎺у埗銆佸璁″憡璀?Webhook锛屼互鍙婄煡璇嗗簱鍏冩暟鎹?鍒嗗潡蹇収瀵煎嚭鎭㈠锛涘疄鏂借鍒掕 [docs/superpowers/plans/2026-07-12-v1.2-quality-loop-implementation.md](docs/superpowers/plans/2026-07-12-v1.2-quality-loop-implementation.md)銆?
> V1.2.1 鏀跺熬灏嗙湡瀹炴祻瑙堝櫒闂ㄧ涓氬姟鏁版嵁闅旂鍒?`e2e` 绉熸埛锛涙垚鍔熻繍琛屼細鍒犻櫎涓存椂鐭ヨ瘑搴撳拰璐﹀彿锛屽け璐ヨ繍琛屼粎淇濈暀 `e2e` 璇佹嵁銆傝璁¤ [docs/superpowers/specs/2026-07-14-v1.2.1-e2e-isolation-cleanup-design.md](docs/superpowers/specs/2026-07-14-v1.2.1-e2e-isolation-cleanup-design.md)銆?
> V1.5 (RAG Quality Upgrade) adds Parent-Child / QA-assisted indexing, a filterable profile v2 Milvus superset, Combined weighted RRF, revision-bound eval quality gates, and Web readiness/gate comparisons.

浼佷笟绾?RAG 鐭ヨ瘑搴撳紩鎿?鈥?绫讳技 Dify/鎵ｅ瓙搴曞眰鐭ヨ瘑搴撴ā鍧椼€?

鏀寔绉佹湁鏂囨。锛圥DF銆丏OCX銆乀XT銆丮arkdown銆丒xcel锛変笂浼犮€佸紓姝ヨВ鏋愪笌鍚戦噺鍖栵紝缁撳悎澶фā鍨嬭繘琛屾绱㈠寮洪棶绛旓紙SSE 娴佸紡锛夈€?

### V1.5 鍗囩骇涓庡惎鐢?

- V1.5 浣跨敤鐙珛鐨?`MILVUS_PROFILE_COLLECTION` 淇濆瓨 classic銆乸arent-child銆乹a-assisted 鍜?combined 鍏辩敤鐨勫彲杩囨护 superset銆傚凡鏈夌煡璇嗗簱鍗囩骇鍚庨渶瑕佹墽琛屼竴娆♀€滈噸寤虹储寮曗€濓紱閲嶅缓鎸夋枃妗ｆ粴鍔ㄦ浛鎹㈠悜閲忓拰 chunk锛屼笉浼氬厛娓呯┖鏁翠釜鍦ㄧ嚎绱㈠紩銆?
- 鐭ヨ瘑搴撲粎鍦ㄦ墍鏈夋枃妗ｅ潎涓?`COMPLETED` 涓?`index_schema_version=2` 鏃舵爣璁颁负 profile v2 ready銆傞娆?ready 浼氭寔涔呭寲 cutover 鐘舵€佸苟娓呯悊 Legacy锛涘悗缁笂浼犳垨閲嶅缓鏈熼棿浠嶄娇鐢?v2 涓凡瀹屾垚鐨勬枃妗ｏ紝涓嶄細鍥為€€鍒板凡娓呯悊鐨?Legacy銆傚垏鎹㈤粯璁?profile 鍙敼鍙樻绱㈠叆鍙ｏ紝涓嶄細鍐嶆閲嶅缓缁熶竴绱㈠紩銆?
- 闈?classic profile 蹇呴』浣跨敤褰撳墠 `index_revision` 鐨?RAG 璇勪及涓?`CLASSIC` 瀵规瘮锛屼笖鑷冲皯鍖呭惈 3 涓?case銆佸紩鐢ㄥ彲璇勪及銆佸懡涓巼鍜屽紩鐢ㄩ€氳繃鐜囧潎涓嶅洖閫€銆傛湭閫氳繃鏃舵洿鏂版帴鍙ｈ繑鍥?HTTP `409`锛岄敊璇爜涓?`retrieval_profile_gate_blocked`銆?

鐗堟湰鍙樻洿瑙?[V1.5.0 Release Notes](docs/v1.5-release-notes.md)锛屽崌绾с€佺伆搴︺€侀獙璇佷笌鍥炴粴姝ラ瑙?[V1.5.0 鍙戝竷杩愯鎵嬪唽](docs/v1.5-release-runbook.md)銆?

## 鎶€鏈爤

- **Web 鎺у埗鍙?*锛歊eact 18 + Vite + TypeScript + Tailwind
- **API**锛欽ava 17 + Spring Boot 3
- **鏋勫缓宸ュ叿**锛歁aven Wrapper 鍥哄畾 Apache Maven 3.9.9锛坄services/api/mvnw.cmd` / `services/api/mvnw`锛?
- **Worker**锛歅ython 3.11
- **鍚戦噺搴?*锛歁ilvus | **鍏冩暟鎹?*锛歅ostgreSQL | **闃熷垪**锛歊edis | **瀵硅薄瀛樺偍**锛歁inIO

## 蹇€熷惎鍔?

### 1. 閰嶇疆鐜鍙橀噺

```bash
cp deploy/.env.example deploy/.env
```

缂栬緫 `deploy/.env`锛?*蹇呴』**閰嶇疆涓ゅ LLM 鍑瘉锛圖eepSeek 瀹樻柟鏃?Embedding 鎺ュ彛锛夛細

| 鍙橀噺 | 鐢ㄩ€?| 绀轰緥 |
|------|------|------|
| `CHAT_API_KEY` | RAG 瀵硅瘽锛圖eepSeek锛?| 鍦?[platform.deepseek.com](https://platform.deepseek.com) 鐢宠 |
| `CHAT_BASE_URL` | 瀵硅瘽 API 鍦板潃 | `https://api.deepseek.com` |
| `CHAT_MODEL` | 瀵硅瘽妯″瀷 | `deepseek-chat` |
| `EMBEDDING_API_KEY` | 鏂囨。鍚戦噺鍖?+ 妫€绱?| 鍦?[鏅鸿氨寮€鏀惧钩鍙癩(https://open.bigmodel.cn) 鐢宠 |
| `EMBEDDING_BASE_URL` | Embedding API 鍦板潃 | `https://open.bigmodel.cn/api/paas/v4` |
| `EMBEDDING_MODEL` | 鍚戦噺妯″瀷 | `embedding-2`锛堟櫤璋憋級 |
| `EMBEDDING_DIMENSION` | 鍚戦噺缁村害 | 椤讳笌妯″瀷涓€鑷达紝鏅鸿氨 `embedding-2` 涓?`1024` |
| `EMBEDDING_BATCH_SIZE` | Worker 鍗曟 Embedding 璇锋眰鏂囨湰鏁?| 榛樿 `32`锛屽彲鎸変緵搴斿晢闄愬埗涓嬭皟 |
| `DUPI_API_KEY` | 鍙€夊叕寮€ API 鍏变韩瀵嗛挜 | 鏈湴鍙俊寮€鍙戝彲鐣欑┖锛涘叡浜?閮ㄧ讲鐜寤鸿璁剧疆 |
| `DUPI_INTERNAL_KEY` | 鍙€夊唴閮?API 鍏变韩瀵嗛挜 | API 涓?Worker 蹇呴』淇濇寔涓€鑷?|
| `UPLOAD_RATE_LIMIT_REQUESTS` | 涓婁紶闄愭祦绐楀彛鍐呰姹傛暟 | 榛樿 `20` |
| `UPLOAD_RATE_LIMIT_WINDOW_SECONDS` | 涓婁紶闄愭祦绐楀彛绉掓暟 | 榛樿 `60` |
| `INGEST_QUEUE_MAX_PENDING_JOBS` | 鎽勫叆 Redis 闃熷垪楂樻按浣?| 榛樿 `200`锛岃揪鍒伴槇鍊兼椂涓婁紶鍏ュ彛蹇€熸嫆缁?|
| `INGEST_RECOVERY_CRON` | 鎽勫叆浠诲姟琛ュ伩鎵弿 cron | 榛樿姣?2 鍒嗛挓 |
| `INGEST_RECOVERY_MAX_ATTEMPTS` | 鎽勫叆琛ュ伩鏈€澶ц嚜鍔ㄩ噸璇曟鏁?| 榛樿 `3`锛岃揪鍒板悗杩涘叆姝讳俊鐘舵€?|
| `INGEST_OUTBOX_DISPATCH_CRON` | transactional outbox 鎶曢€?cron | 榛樿姣?10 绉?|
| `ORPHAN_VECTOR_CLEANUP_CRON` | 娈嬬暀鍚戦噺琛ュ伩娓呯悊瀹氭椂浠诲姟 | 榛樿姣忓ぉ `03:30` |
| `AUDIT_RETENTION_DAYS` | 瀹¤鏃ュ織淇濈暀澶╂暟 | 榛樿 `180`锛屽皬浜庣瓑浜?0 琛ㄧず涓嶆竻鐞?|
| `AUDIT_RETENTION_CRON` | 瀹¤鏃ュ織淇濈暀娓呯悊 cron | 榛樿姣忓ぉ `02:15` |
| `AUDIT_ALERT_WINDOW_MINUTES` | 瀹¤澶辫触鍛婅缁熻绐楀彛 | 榛樿 `30` 鍒嗛挓 |
| `AUDIT_ALERT_FAILED_THRESHOLD` | 瀹¤澶辫触鍛婅闃堝€?| 榛樿 `10` 娆?|
| `AUDIT_ALERT_WEBHOOK_URL` | 鍙€夊璁″憡璀?Webhook 鍦板潃 | 鐣欑┖鏃堕€氱煡鎺ュ彛杩斿洖 `configured=false` |
| `AUDIT_ALERT_WEBHOOK_TIMEOUT_SECONDS` | 瀹¤鍛婅 Webhook 瓒呮椂 | 榛樿 `10` 绉?|

閰嶇疆鍚庨噸鍚簲鐢ㄥ鍣細

```bash
cd deploy
docker compose up -d --force-recreate api worker
```

### 2. 鍚姩鍩虹璁炬柦涓庡簲鐢?

```bash
cd deploy
docker compose up -d --build
```

### 3. 璁块棶 Web 鎺у埗鍙?

娴忚鍣ㄦ墦寮€ **http://localhost:8080**

1. **鏂板缓鐭ヨ瘑搴?* 鈫?閫夋嫨鍚戦噺妫€绱㈡垨娣峰悎妫€绱紝鐐瑰嚮鍗＄墖杩涘叆璇︽儏
2. **鏂囨。绠＄悊** 鈫?涓婁紶鏂囦欢锛岀瓑寰呯姸鎬?`COMPLETED`锛涚偣鍑绘煡鐪嬫寜閽鏌ュ璞°€佹憚鍏ヤ换鍔°€佸垎鍧楁€绘暟銆佹渶澶?20 涓垎鍧楁牱渚嬩笌绱㈠紩灏辩华鐘舵€?
3. **鏅鸿兘闂瓟** 鈫?鍩轰簬宸叉憚鍏ユ枃妗ｆ彁闂紙闇€閰嶇疆 `CHAT_API_KEY` 涓?`EMBEDDING_API_KEY`锛?
4. **RAG 璇勪及** 鈫?绠＄悊鎸佷箙鍖栫敤渚嬶紙绌哄簱鑷姩鍒涘缓鍐呯疆鐢ㄤ緥锛屾瘡搴撴渶澶?100 鏉★級锛岄€夋嫨鏄惁鍚敤 Rerank锛岃繍琛屽苟鏌ョ湅鏈€杩?10 娆＄粨鏋滀笌閫愮敤渚嬭瘖鏂?

### 4. 楠岃瘉

```bash
# 鍋ュ悍妫€鏌ワ紙缁?Nginx 浠ｇ悊锛?
curl http://localhost:8080/actuator/health
```

榛樿 Compose 鍙毚闇?Web 鍏ュ彛 `http://localhost:8080`锛孉PI銆乄orker銆丳ostgreSQL銆丷edis銆丮ilvus銆丮inIO 浠呭湪 Docker 鍐呴儴缃戠粶鍙銆傞渶瑕佺洿杩炶皟璇曟椂锛屽彲涓存椂浣跨敤鏈湴 override 鏂囦欢鏄犲皠绔彛锛岄伩鍏嶆妸璋冭瘯绔彛闀挎湡鏆撮湶鍦ㄩ粯璁ら儴缃蹭腑銆?

闇€瑕佹鏌?Compose 灞曞紑閰嶇疆鏃讹紝璇蜂娇鐢ㄨ劚鏁忚剼鏈紝閬垮厤鎶?`.env` 涓殑绗笁鏂?Key 鎵撳嵃鍒扮粓绔垨鑱婂ぉ璁板綍锛?

```powershell
powershell -ExecutionPolicy Bypass -NoProfile -File scripts/compose-config-redacted.ps1
```

濡傛灉鏇剧粡鎶?`docker compose config` 鐨勫師濮嬭緭鍑鸿创鍒扮粓绔叡浜笂涓嬫枃鎴栨埅鍥句腑锛岃绔嬪嵆杞崲瀵瑰簲鐨?`CHAT_API_KEY`銆乣EMBEDDING_API_KEY` 浠ュ強鍏变韩瀵嗛挜銆?

### 4.1 Docker 鍚姩鎺掗殰

- **闀滃儚鎷夊彇鎱㈡垨澶辫触**锛氫紭鍏堥厤缃?Docker Desktop registry mirrors锛屾垨鎻愬墠 `docker pull` Compose 涓殑鍩虹闀滃儚锛涗笉瑕佹妸涓存椂浠ｇ悊鍦板潃鍐欏叆浠撳簱鍐呴厤缃€?
- **Worker pip 瀹夎鎱㈡垨澶辫触**锛氬彲鍦ㄦ湰鏈?CI 渚ч厤缃?pip 闀滃儚婧愶紱渚濊禆鐗堟湰浠?`services/worker/requirements*.txt` 涓哄噯锛岄伩鍏嶄复鏃舵斁瀹?`pymilvus`銆乣marshmallow` 绛夌害鏉熴€?
- **鍓嶇鏋勫缓 Node 鐗堟湰闂**锛歐eb 鑴氭湰宸查€氳繃 `services/web/scripts/node16-webcrypto.cjs` 鍏煎鏈満 Node 16锛涚敓浜ф瀯寤轰粛寤鸿浣跨敤椤圭洰 Dockerfile 涓浐瀹氱殑鏋勫缓鐜鎴?Node 18+銆?
- **CORS 鎴栫鍙ｈ闂紓甯?*锛氶粯璁ゅ彧璁块棶 `http://localhost:8080`锛岀敱 Web Nginx 鍙嶄唬 `/api`锛涘闇€鐩磋繛 API銆丳ostgreSQL銆丷edis銆丮ilvus 鎴?MinIO锛岃浣跨敤涓存椂 Compose override 鏄惧紡鏆撮湶绔彛銆?
- **Milvus 缁村害涓嶄竴鑷?*锛歚EMBEDDING_DIMENSION` 蹇呴』涓庡綋鍓?`MILVUS_COLLECTION` 鐨?`embedding` 鍚戦噺缁村害涓€鑷淬€傚垏鎹?embedding 妯″瀷/缁村害鍚庯紝鏃х煡璇嗗簱鍙敤 `POST /api/v1/knowledge-bases/{kbId}/reindex` 閲嶅缓锛涘鏋?collection 鏈韩缁村害涓嶅尮閰嶏紝API 浼氬湪鍚姩鏃?fail-fast锛岄渶瑕佸垹闄?閲嶅缓 collection锛屾垨鎶?`MILVUS_COLLECTION` 鎸囧悜鏂扮殑缁村害涓撶敤闆嗗悎銆?
- **Milvus 闆嗗悎鍔犺浇杈冩參**锛欰PI 鍚姩鍙紓姝ュ彂璧?collection load锛屼笉鍐嶅悓姝ョ瓑寰?QueryNode 瀵艰嚧 Web 闀挎椂闂?502锛涢泦鍚堟湭灏辩华鏈熼棿妫€绱細娌跨敤宸叉湁鏈湴鏂囨湰 fallback锛屽苟鍦ㄨ瘖鏂腑鎶ュ憡鍘熷洜銆?

### 4.2 绔埌绔富娴佺▼鑷姩鍖栵紙鎺ㄨ崘锛?

鑴氭湰鎸?Web 鎺у埗鍙版寜閽『搴忚皟鐢ㄦ帴鍙ｏ紙鍋ュ悍 鈫?寤哄簱 鈫?涓婁紶 鈫?鎽勫叆 鈫?妫€绱?鈫?闂瓟 SSE锛夛細

```powershell
powershell -NoProfile -File scripts/e2e-main-flow.ps1
```

闇€鏈夋晥 `EMBEDDING_*` 涓?`CHAT_*` 閰嶇疆锛涙楠よ鏄庝笌鏈€杩戣繍琛岀粨鏋滆 [docs/e2e-testing.md](docs/e2e-testing.md)銆?

鏂板缁存姢涓庡洖褰掗獙璇佽剼鏈細

```powershell
# 鐪熷疄娴忚鍣?E2E 闂ㄧ锛氫娇鐢ㄧ湡瀹炵櫥褰曘€丆ookie 涓?CSRF锛屼笉渚濊禆鏈湴寮€鏀炬ā寮?
$env:E2E_BASE_URL="http://localhost:8080"
$env:E2E_ADMIN_USERNAME="<admin>"
$env:E2E_ADMIN_PASSWORD="<password>"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-browser-gate.ps1

# 绱㈠紩缁存姢娴佺▼锛氭壒閲忎笂浼犮€乺eindex銆佹憚鍏ヤ换鍔￠噸璇曞叆鍙ｃ€佸悜閲忔竻鐞嗕换鍔″叆鍙?
powershell -NoProfile -File scripts/e2e-web-maintenance-flow.ps1

# RAG 妫€绱㈠洖褰掕瘎娴嬶細鎸?examples/rag-eval-cases.json 鏍￠獙鍛戒腑涓庡紩鐢ㄦ枃浠?
powershell -NoProfile -File scripts/rag-regression-eval.ps1
```

`e2e-browser-gate.ps1` 闇€瑕?`E2E_ADMIN_USERNAME` 涓?`E2E_ADMIN_PASSWORD`锛岀己灏戝嚟鎹椂浼氭槑纭け璐ャ€傞棬绂佷粎浠ラ厤缃鐞嗗憳鍒涘缓 `e2e` 绉熸埛涓殑涓存椂绠＄悊鍛橈紝鍚庣画鐭ヨ瘑搴撱€丷AG 鐢ㄤ緥鍜岄獙璇佽处鍙峰潎鍦ㄨ绉熸埛瀹屾垚锛涙垚鍔熷悗鑷姩鍒犻櫎涓存椂鐭ヨ瘑搴撳強 `e2e_*` 璐﹀彿锛屽け璐ユ椂鍦?Playwright 缁撴灉涓繚鐣欒祫婧愭爣璇嗗拰椤甸潰 URL 浣滀负璇佹嵁銆俙rag-regression-eval.ps1` 浼氬啓鍏?`scripts/rag-regression-eval-last-run.json`锛屽叾涓?`caseResults` 鍖呭惈姣忔潯鐢ㄤ緥鐨?query銆乸ass/fail銆佸懡涓暟銆佹湡鏈?鍛戒腑鏂囦欢銆佸懡涓?token銆佹绱㈡ā寮忋€乫allback 鍘熷洜鍜?embedding 淇℃伅銆?

### 5. API 绀轰緥

```bash
# 鍒涘缓鐭ヨ瘑搴?
curl -X POST http://localhost:8080/api/v1/knowledge-bases \
  -H "Content-Type: application/json" \
  -d '{"name":"demo","description":"娴嬭瘯搴?,"chunkSize":512,"chunkOverlap":64,"topK":5,"retrievalMode":"HYBRID"}'

# 涓婁紶鏂囨。
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents \
  -F "file=@sample.pdf"

# 妫€绱㈣皟璇?
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/retrieve \
  -H "Content-Type: application/json" \
  -d '{"query":"浣犵殑闂","topK":5}'

# /retrieve 杩斿洖 citations 鍜?diagnostics锛屽彲鐢ㄤ簬鎺掓煡鍛戒腑鏁般€乫allback 鍘熷洜涓?embedding 閰嶇疆

# 閲嶅缓鏃х煡璇嗗簱绱㈠紩锛堝垏鎹?embedding 妯″瀷/缁村害鍚庝娇鐢級
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/reindex

# 閲嶈瘯澶辫触鎴栨淇℃憚鍏ヤ换鍔?
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/retry

# 鏌ョ湅鎽勫叆浠诲姟璇婃柇锛涘搷搴斿寘鍚?documentFileName銆乨ocumentStatus銆乨iagnosis
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/ingest-jobs

# 鏌ョ湅鍗曟枃妗ｄ笂浼?鎽勫叆/绱㈠紩璇︽儏锛涘寘鍚璞＄姸鎬併€佹渶杩戜换鍔°€佸垎鍧楁暟涓庡垎鍧楁牱渚?
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/documents/{docId}/index-detail

# 绠＄悊鎸佷箙鍖?RAG 璇勪及鐢ㄤ緥銆佽繍琛岃瘎浼板苟鏌ョ湅鏈€杩戝巻鍙?
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/cases
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/cases \
  -H "Content-Type: application/json" \
  -d '{"caseKey":"format-check","query":"鏀寔鍝簺鏍煎紡锛?,"minHits":1,"topK":5,"expectedFileName":"guide.md","mustContainAny":["PDF"]}'
curl -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/runs \
  -H "Content-Type: application/json" \
  -d '{"useRerank":true}'
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/rag-eval/runs

# 鏌ョ湅骞堕噸璇曟畫鐣欏悜閲忚ˉ鍋挎竻鐞嗕换鍔?
curl http://localhost:8080/api/v1/ops/vector-cleanup-tasks
curl -X POST http://localhost:8080/api/v1/ops/vector-cleanup-tasks/{taskId}/retry

# 鏌ョ湅/瀵煎嚭瀹¤鏃ュ織銆佹煡鐪嬪璁″憡璀︺€佽处鍙?瑙掕壊鍏冩暟鎹?
curl "http://localhost:8080/api/v1/ops/audit-logs?limit=50"
curl "http://localhost:8080/api/v1/ops/audit-logs/export" -o audit-logs.csv
curl http://localhost:8080/api/v1/ops/audit-alerts
curl -X POST http://localhost:8080/api/v1/ops/audit-alerts/notify
curl http://localhost:8080/api/v1/ops/metadata
curl http://localhost:8080/api/v1/ops/accounts
curl http://localhost:8080/api/v1/ops/roles

# 浠呮祴璇曟竻鐞嗭細闇€ OPS_ADMIN锛屼笖浠呭厑璁稿垹闄?e2e 绉熸埛涓殑 e2e_* 璐﹀彿銆?
# 璐﹀彿绠＄悊椤甸潰涓嶆彁渚涢€氱敤鍒犻櫎鍏ュ彛銆?
curl -X DELETE http://localhost:8080/api/v1/ops/accounts/e2e_account_42

# /ops/metadata 杩斿洖 guardrails锛氫笂浼犻檺娴併€佹憚鍏ラ槦鍒椼€佸璁￠槇鍊煎拰 multipart 鏈€澶ф枃浠跺ぇ灏?
# /ops/audit-alerts 鑱氬悎瀹¤澶辫触宄板€笺€佹憚鍏ュけ璐?姝讳俊浠诲姟鍜屽悜閲忔竻鐞嗗け璐ヤ换鍔?
# /ops/audit-alerts/notify 浠呭湪 AUDIT_ALERT_WEBHOOK_URL 闈炵┖鏃舵姇閫掞紝鍝嶅簲杩斿洖 configured/delivered/statusCode
# 璋冪敤涓讳綋椤诲悓鏃舵嫢鏈?OPS_ADMIN銆丱PS_AUDIT_READ銆丱PS_ALERT_NOTIFY锛涜秴鏃剁敱 AUDIT_ALERT_WEBHOOK_TIMEOUT_SECONDS 鎺у埗

# schemaVersion=1锛涘崟娆℃渶澶氬鍑?1,000 涓枃妗ｅ揩鐓у拰 10,000 涓垎鍧楀揩鐓?
# 瀵煎叆鏃跺垱寤烘柊鐭ヨ瘑搴擄紝浠呴€氳繃涓氬姟鏈嶅姟鎭㈠鐭ヨ瘑搴撻厤缃拰璇勪及鐢ㄤ緥
curl http://localhost:8080/api/v1/knowledge-bases/{kbId}/export -o kb-export.json
curl -X POST http://localhost:8080/api/v1/knowledge-bases/import \
  -H "Content-Type: application/json" \
  --data-binary @kb-export.json

# 鏂板缓/鏇存柊璐﹀彿銆侀噸缃瘑鐮併€佺鐢?鍚敤璐﹀彿銆佽疆鎹?tokenVersion
curl -X POST http://localhost:8080/api/v1/ops/accounts \
  -H "Content-Type: application/json" \
  -d '{"username":"analyst","password":"change-me","tenantId":"default","roleCode":"ANALYST","knowledgeBaseIds":[]}'
curl -X PATCH http://localhost:8080/api/v1/ops/accounts/analyst \
  -H "Content-Type: application/json" \
  -d '{"roleCode":"VIEWER","knowledgeBaseIds":["<kbId>"]}'
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/reset-password \
  -H "Content-Type: application/json" \
  -d '{"password":"new-change-me"}'
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/disable
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/enable
curl -X POST http://localhost:8080/api/v1/ops/accounts/analyst/rotate-token

# 鏂板缓/鏇存柊/绂佺敤瑙掕壊锛涜处鍙烽€氳繃 roleCode 鑾峰緱瑙掕壊缁戝畾鐨勬潈闄愮偣
curl -X POST http://localhost:8080/api/v1/ops/roles \
  -H "Content-Type: application/json" \
  -d '{"code":"SUPPORT","name":"鏀寔浜哄憳","permissions":["KB_READ","CHAT_WRITE"]}'
curl -X PATCH http://localhost:8080/api/v1/ops/roles/SUPPORT \
  -H "Content-Type: application/json" \
  -d '{"name":"鏀寔浜哄憳","permissions":["KB_READ","CHAT_WRITE","DOCUMENT_UPLOAD"]}'
curl -X POST http://localhost:8080/api/v1/ops/roles/SUPPORT/disable

# RAG 娴佸紡闂瓟
curl -N -X POST http://localhost:8080/api/v1/knowledge-bases/{kbId}/chat \
  -H "Content-Type: application/json" \
  -d '{"query":"浣犵殑闂","stream":true}'
```

闂瓟 SSE 鐨?`retrieval` 浜嬩欢杩斿洖 `{ citations, diagnostics }`锛汬TTP 涓?SSE 閿欒閮借繑鍥炵粨鏋勫寲 JSON锛坄error`銆乣message`銆乣stage`銆乣suggestion`銆乣requestId`锛夛紝鍓嶇浼氭寜妫€绱€丩LM銆侀壌鏉冪瓑闃舵缁欏嚭鍙墽琛屾彁绀恒€傜煡璇嗗簱瀵煎叆浠呮帴鍙?`schemaVersion=1`锛屽綋鍓嶄笉閲嶆柊涓婁紶 MinIO 鍘熷浜岃繘鍒躲€佷笉鎭㈠鏂囨。涓昏褰曪紝涔熶笉鐩存帴閲嶅缓鍚戦噺锛涘鍑轰腑鐨勬枃妗?鍒嗗潡灞炰簬瀹¤涓庤縼绉诲揩鐓э紝瀹屾暣鐏惧浠嶉渶瀵硅薄瀛樺偍澶囦唤閰嶅悎銆?

### 6. 鏈湴鍓嶇寮€鍙戯紙鍙€夛級

```bash
cd services/web
npm install
npm run dev
```

Vite 寮€鍙戞湇鍔″櫒杩愯鍦?http://localhost:5173锛孉PI 榛樿浠ｇ悊鍒?http://localhost:8081銆?

榛樿閮ㄧ讲涓嶅啀鏄犲皠瀹夸富鏈?`8081`銆傛湰鍦板墠绔紑鍙戝闇€浣跨敤 Vite 浠ｇ悊锛岃閫氳繃 Docker Compose override 鎴栧崟鐙惎鍔?API 鏆撮湶璋冭瘯绔彛銆?

### 7. 鏈湴 API 鏋勫缓锛堝彲閫夛級

API 鎺ㄨ崘浣跨敤椤圭洰鑷甫 Maven Wrapper锛岀‘淇濇湰鍦般€丆I 涓庡鍣ㄦ瀯寤哄熀绾夸竴鑷达細

```powershell
cd services/api
.\mvnw.cmd verify
```

## 鐩綍缁撴瀯

瑙?[docs/architecture.md](docs/architecture.md)銆?

## 鐗堟湰瑙勫垝

| 鐗堟湰 | 鑳藉姏 |
|------|------|
| V1 | 鐭ヨ瘑搴?CRUD銆佸紓姝ユ憚鍏ャ€佺函鍚戦噺妫€绱€丼SE RAG銆乄eb 鎺у埗鍙?|
| V1.1 | 鐪熷疄娴忚鍣?E2E 闂ㄧ銆佹憚鍏ヨ瘖鏂€丷AG 璇勪及闂幆銆佷笂浼犳不鐞嗘彁绀恒€佽仛鍚堣繍缁村憡璀?|
| V1.2 | 绱㈠紩璇︽儏銆佺粨鏋勫寲 Chat 閿欒銆佹寔涔呭寲 RAG 璇勪及銆佹贩鍚堟绱?Rerank 鎺у埗銆乄ebhook銆佸鍑烘仮澶?|
| V1.5 | Parent-Child / QA-assisted indexing, profile v2 filterable superset, Combined weighted fusion, revision-bound quality gates, Web readiness/gate comparison |
| V2 | BM25 sparse 鐢熶骇璋冧紭銆佽涔夊垎鍧椼€佺敓鎴愪腑鏂€佸畬鏁村璞?鍚戦噺鐏惧鎭㈠ |
| V3 | 澶氭ā鎬?OCR銆丳ipeline DSL |
| V4 | K8s銆佸绉熸埛銆佸悎瑙勫璁?|

璇︾粏瑙勫垝瑙?[docs/todo.md](docs/todo.md) 涓?[docs/decisions.md](docs/decisions.md)銆?
# V1.3 鍙戝竷纭寲

V1.3 浣跨敤 30 鏉°€佸叚鍒嗙被妫€绱㈡竻鍗曞強褰撳墠/legacy 鍐茬獊璇枡浣滀负鍙戝竷鍩哄噯锛學orker 鏀寔 Rerank 鍚姩棰勭儹鍜屾寔涔呭寲 Hugging Face 缂撳瓨锛岀煡璇嗗簱 RAG 璇勪及椤垫彁渚?Sparse Migration 鐘舵€佽建閬撳拰鍙椾繚鎶ょ殑 Cutover 鎿嶄綔銆侻ilvus 2.4.1 鍒?2.5.4 鐨勫浠?鎭㈠婕旂粌鍙婁緷璧栥€佽鍙瘉銆丆VE銆侀暅鍍忎綋绉壂鎻忓潎鎻愪緵鍙噸澶嶈剼鏈€?

瀹屾暣鍙戝竷姝ラ銆佺幆澧冨彉閲忋€佸け璐ョ瓥鐣ュ拰璇佹嵁浣嶇疆瑙?[V1.3 鍙戝竷杩愯鎵嬪唽](docs/v1.3-release-runbook.md)銆傚疄闄呯敓浜у悓瑙勬牸婕旂粌銆?0 Case 鐜鍩哄噯鍜岄暅鍍忔壂鎻忎粛鏄寮忓彂甯冨墠鐨勫繀鍋氶」銆?

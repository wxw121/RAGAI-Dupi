# End-to-end main process automation testing

<!-- language-switch -->
[中文](../zh-CN/e2e-testing.md) | **English**


# 项目根目录（Windows 需 Bypass 执行策略）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-main-flow.ps1

# 可选参数
powershell -NoProfile -File scripts/e2e-main-flow.ps1 `
  -BaseUrl "http://localhost:8080" `
  -SampleFile "examples\sample-knowledge.md" `
  -PollSeconds 120 `
  -PollInterval 3

```

Maintenance process and RAG regression:

```powershell

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-web-maintenance-flow.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/rag-regression-eval.ps1

```

`rag-regression-eval.ps1` can reuse the existing knowledge base through `-KbId`. When not uploaded, a temporary evaluation knowledge base will be created and `examples/sample-knowledge.md` will be uploaded.
The report is written into `scripts/rag-regression-eval-last-run.json`. `caseResults` will record the query, pass/fail, hit count, expected/hit file, hit token, search mode, fallback reason, embedding model and dimension of each use case.

Exit code: `0` All passed; `1` fails at any step (the report is still written to `e2e-last-run.json`).

## Real Browser E2E Access Control (V1.2.1

This access control is located at `services/web/e2e/browser-gate.spec.ts` and runs through the root script:

```powershell

$env:E2E_BASE_URL="http://localhost:8080"
$env:E2E_ADMIN_USERNAME="<admin>"
$env:E2E_ADMIN_PASSWORD="<password>"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-browser-gate.ps1

```

The access control will start a clean Chromium context and execute the following real UI path:

Log in by entering the username/password of the configuration administrator. Do not use the local open mode. This session only creates the temporary administrator `e2e_gate_<runId>` among the `e2e` tenants.
2. After clearing the Cookie and local CSRF status, log in again as a temporary administrator. Select `HYBRID` to create a `e2e-browser-<runId>` knowledge base, and enter the Documents, Q&A, and `RAG 评估` tabs.
3. Create a new persistent RAG evaluation use case and confirm the list echo.
4. Open the audit page; Create `e2e_account_<runId>` on the account page, confirm that there is no `CSRF token required`, and then disable the account. Finally, open the character page.
5. At each stage, check for page anomalies, console errors, key API 4xx/5xx, request failures, and error toasts.

When `E2E_ADMIN_USERNAME` or `E2E_ADMIN_PASSWORD` is missing, it will exit directly and prompt that the credentials are missing. This is a failure of the preconditions and does not mean that the product process has been approved. After all product assertions and browser error access control are passed, the test will use the same browser Cookie and CSRF token to delete the temporary knowledge base, `e2e_account_<runId>`, and `e2e_gate_<runId>` in sequence, and then confirm with the configuration administrator that all three are invisible. If any step of the product process fails and the deletion is not executed, Playwright will append `e2e-evidence` (tenant resource identifier and current page URL) for troubleshooting.

Only for automated cleaning can `DELETE /api/v1/ops/accounts/{username}` be invoked: The caller must have `OPS_ADMIN`, the user name must start with lowercase `e2e_`, and the account tenant must be `e2e`. The account management page does not have a universal delete button. Ordinary accounts and `default` tenant accounts cannot be deleted through this interface.

The most recent browser access (2026-07-14)

- URL: `http://localhost:8080`
- Browser: Playwright Chromium/Desktop Chrome configuration
Result: `1 passed (4.5s)`, the core testing process takes a long time `3.7s`
- Inspection result: After the configuration administrator created the temporary E2E administrator, the three tabs of mixed retrieval library creation, knowledge base, RAG use case saving, auditing, account creation/disabling, and role page in the isolated session were all successful. No console, page, request, Toast or CSRF errors were captured. The temporary knowledge base and two temporary accounts have been automatically cleared.

The most recent run result (2026-06-19)

__DU_PI_PIPE__ steps|__DU_PI_PIPE__|state
__DU_PI_PIPE__ ------|------|------ __DU_PI_PIPE__
__DU_PI_PIPE__ 1-4|PASS|Health, List, Library Building, details __DU_PI_PIPE__
__DU_PI_PIPE__ 5 upload|PASS|`DocumentService` after the timestamp is fixed, go through __DU_PI_PIPE__
__DU_PI_PIPE__ 6 Intake|PASS|Smart Spectrum `embedding-2` A new KB + Milvus collection dimension of 1024|is required
7 retrieval|__DU_PI_PIPE__ PASS|hits|1 or higher
__DU_PI_PIPE__ 8 Chat SSE|PASS|DeepSeek streaming + `LlmClient` SSE parsing fix __DU_PI_PIPE__

** Run command ** : `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e-main-flow.ps1`

## Frequently Asked Questions (Page/Intake

### Web library Building or Uploading 403

Phenomenon: `Invalid CORS request`
- Reason: The API `CorsConfig` does not allow `Origin: http://localhost:8080`
- Handling: Ensure that the API has been deployed with the version containing `allowedOriginPatterns` (`localhost:*`); Hard refresh page

Intake Failure 400 (Zhipu embeddings)

Phenomenon: `Client error '400 Bad Request' for url '.../embeddings'`
- Common causes: ** Old knowledge base ** `embeddingModel` is still `text-embedding-3-small` (The Worker calls the API based on KB metadata, not just reading `.env`)
- Handling: Delete the old database, create a new knowledge base, and then retransmit. Confirm `embeddingModel=embedding-2`, `embeddingDimension=1024` `GET /api/v1/knowledge-bases/{id}`

The dimensions of Milvus do not match

Phenomenon: `the length(1024) of float data should divide the dim(1536)`
- Handling: Delete the Milvus collection `dupi_chunks` (or `docker volume rm deploy_milvus_data`) and then re-incorporate it

Chat only shows "Error:" or the historical session is empty

Phenomenon: The Q&A section only shows an empty error prefix, or there is no specific message after clicking on the history session.
- Common causes: The Milvus QueryNode is temporarily untraceable (for example, `Timestamp lag too large`) resulting in the Chat SSE 500, and the front end receives an empty error; A failed request may leave a session shell with no message.
- Processing: Confirm that the API has included local chunk text underlining and empty session filtering fixes; When returning, run `.\mvnw.cmd "-Dtest=RetrievalServiceTest,ChatSessionServiceTest,ChatServiceTest" test` and `npm run test -- src/api/chat.test.ts`, and recheck with the real `/chat` and `/chat-sessions` interfaces.

All index maintenance tasks failed

Phenomenon: After uploading multiple documents in batches, the Embedding request was successful, but the index maintenance task displayed `FAILED`, and the error included Milvus `Timestamp lag too large`.
- Common cause: Before writing to new chunks, the Worker will delete the old vector by `doc_id`. Milvus delete throws a temporary exception when the timestamp of QueryNode lags.
- Handling: Confirm that the Worker has included the fault tolerance of `delete_by_doc` for `Timestamp lag too large` / `failed to search/query delegator`. Run `python -m pytest services/worker/tests` during regression, and then use the page "Retry" or `POST /api/v1/knowledge-bases/{kbId}/ingest-jobs/{jobId}/retry` for the failed ingest job.

** Configuration and Restart **

-`deploy/.env` configure `EMBEDDING_*` (Zhipu) and `CHAT_*` (DeepSeek)
The -`EMBEDDING_BASE_URL` is the root path of the API. ** Do not ** include the `/embeddings` suffix
After modifying `.env` : `cd deploy && docker compose up -d --force-recreate worker api`

## Related defect repair

Upload 500 (`documents.created_at` NOT NULL)

- ** Reason ** : When the primary key is preset in `Document.builder().id(UUID)`, JPA `save()` takes the merge path, while `@PrePersist` does not execute. The second `save()` updates `created_at` to null
- "Fix" : [`DocumentService.upload()`] (../../services/api/src/main/java/com/dupi/rag/service/DocumentService.java) explicitly set in the builder `createdAt` / `updatedAt`, And pre-calculate `objectKey`
- Deployment: `docker compose up -d --build api`
# V1.3 Sparse Migration Browser Access Control

`services/web/e2e/browser-gate.spec.ts` validates Sparse Migration pages within the real login, real isolated KB, and real Retrieval Profile processes. The migration API uses deterministic routing fixtures to return PREPARING, DUAL_WRITING, SHADOW_VALIDATING, CUTOVER, and COMPLETED in sequence. Page components, confirmation dialog boxes, button access control, and browser error collection still run the real code.

Execution still requires `E2E_ADMIN_USERNAME`, `E2E_ADMIN_PASSWORD` and the running application; Running only `npx playwright test --list` can only prove that the test is discoverable and cannot be used as evidence that the browser Gate passes.

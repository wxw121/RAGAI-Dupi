import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { finalizeE2eRun, type E2eRunResources } from '../src/test/e2eLifecycle'

const username = process.env.E2E_ADMIN_USERNAME
const password = process.env.E2E_ADMIN_PASSWORD
const csrfStorageKey = 'dupi.auth.csrf'

test.beforeAll(() => {
  if (!username || !password) {
    throw new Error('Missing E2E_ADMIN_USERNAME or E2E_ADMIN_PASSWORD. The browser gate must use a real login.')
  }
})

test('real login and core authenticated UI flows isolate and clean E2E data', async ({ page }, testInfo) => {
  const gate = installBrowserErrorGate(page)
  const runId = Date.now().toString()
  const resources: E2eRunResources = {
    runId,
    gateUsername: `e2e_gate_${runId}`,
    accountUsername: `e2e_account_${runId}`,
    kbName: `e2e-browser-${runId}`,
  }
  const gatePassword = `E2e-${runId}-GateAa1!`
  const accountPassword = `E2e-${runId}-AccountAa1!`
  let completed = false

  try {
    await loginAs(page, username!, password!)
    await navigateTo(page, '账号管理')
    await expect(page.getByRole('heading', { name: /账号管理|账户管理/ })).toBeVisible()
    await createAccount(page, resources.gateUsername, gatePassword, 'e2e', 'ADMIN')
    await expect(accountRow(page, resources.gateUsername)).toContainText('e2e')
    await gate.expectClean('create E2E gate administrator')

    await clearSession(page)
    await loginAs(page, resources.gateUsername, gatePassword)

    await page.getByRole('button', { name: /新建知识库/ }).first().click()
    await page.locator('input').nth(0).fill(resources.kbName)
    const retrievalMode = page.locator('select[name="retrievalMode"]')
    await expect(retrievalMode).toBeVisible()
    await retrievalMode.selectOption('HYBRID')
    await page.getByRole('button', { name: /^创建$/ }).click()
    await expect(page.getByText(resources.kbName)).toBeVisible()
    resources.kbId = await findKnowledgeBaseId(page, resources.kbName)
    expect(resources.kbId, 'created E2E knowledge base id').toBeTruthy()
    await installSparseMigrationFixture(page, resources.kbId!)
    await gate.expectClean('create E2E knowledge base')

    await page.getByText(resources.kbName).click()
    await expect(page.getByRole('button', { name: /文档/ })).toBeVisible()
    await page.getByRole('button', { name: /文档/ }).click()
    await expect(page.getByText(/索引维护/)).toBeVisible()
    await gate.expectClean('knowledge base documents tab')

    await page.getByRole('button', { name: /问答/ }).click()
    await expect(page.getByText(/智能问答|暂无可用文档|输入问题|先上传文档/).first()).toBeVisible()
    await gate.expectClean('knowledge base chat tab')

    await page.getByRole('button', { name: /RAG 评估/ }).click()
    await expect(page.getByRole('button', { name: /运行评估/ })).toBeVisible()
    await page.getByLabel('用例标识').fill('browser-smoke')
    await page.getByLabel('检索问题').fill('browser smoke query')
    await page.getByRole('button', { name: /保存用例/ }).click()
    await expect(page.getByText('browser-smoke', { exact: true })).toBeVisible()
    await expect(page.getByText('Retrieval Profile', { exact: true })).toBeVisible()
    await page.getByLabel('Profile 名称').fill('browser-candidate')
    await page.getByRole('button', { name: /^创建$/ }).click()
    await expect(page.getByText(/v1 · browser-candidate/)).toBeVisible()
    await expect(page.getByText('Sparse Migration', { exact: true })).toBeVisible()
    await page.getByLabel('Migration profile').selectOption({ index: 1 })
    await page.getByRole('button', { name: 'Start', exact: true }).click()
    await expect(page.getByText('PREPARING', { exact: true }).first()).toBeVisible()
    await page.getByRole('button', { name: 'Backfill', exact: true }).click()
    await expect(page.getByText('DUAL_WRITING', { exact: true }).first()).toBeVisible()
    await page.getByRole('button', { name: 'Shadow validation', exact: true }).click()
    await expect(page.getByText('SHADOW_VALIDATING', { exact: true }).first()).toBeVisible()
    await page.getByRole('button', { name: 'Cutover sparse migration' }).click()
    await expect(page.getByText('1.10x')).toBeVisible()
    await page.getByRole('button', { name: 'Confirm Cutover', exact: true }).click()
    await expect(page.getByText('CUTOVER', { exact: true }).first()).toBeVisible()
    await page.getByRole('button', { name: 'Complete', exact: true }).click()
    await expect(page.getByText('COMPLETED', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('最低通过率')).toBeVisible()
    await gate.expectClean('knowledge base rag eval tab')

    await navigateTo(page, '审计日志')
    await expect(page.getByRole('heading', { name: /审计日志/ })).toBeVisible()
    await gate.expectClean('ops audit logs page')

    await navigateTo(page, '账号管理')
    await expect(page.getByRole('heading', { name: /账号管理|账户管理/ })).toBeVisible()
    await createAccount(page, resources.accountUsername, accountPassword, 'e2e', 'ANALYST')
    const temporaryAccountRow = accountRow(page, resources.accountUsername)
    await expect(temporaryAccountRow).toBeVisible()
    await expect(page.locator('body')).not.toContainText(/CSRF token required/i)
    await temporaryAccountRow.getByRole('button', { name: /^禁用$/ }).click()
    await expect(temporaryAccountRow).toContainText('已禁用')
    await gate.expectClean('ops account create with CSRF')

    await navigateTo(page, '角色管理')
    await expect(page.getByRole('heading', { name: /角色管理/ })).toBeVisible()
    await gate.expectClean('ops roles page')
    completed = true
  } finally {
    resources.pageUrl = page.url()
    await finalizeE2eRun(
      completed,
      resources,
      () => cleanupSuccessfulRun(page, resources),
      (name, body) => testInfo.attach(name, { body, contentType: 'application/json' }),
    )
    if (completed) {
      await clearSession(page)
      await loginAs(page, username!, password!)
      await expect(page.getByText(resources.kbName, { exact: true })).toHaveCount(0)
      await expect(page.getByText(resources.gateUsername, { exact: true })).toHaveCount(0)
      await expect(page.getByText(resources.accountUsername, { exact: true })).toHaveCount(0)
    }
  }
})

async function installSparseMigrationFixture(page: Page, kbId: string) {
  let migration: Record<string, unknown> | null = null
  await page.route(`**/api/v1/knowledge-bases/${kbId}/sparse-migrations**`, async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(migration ? [migration] : []) })
      return
    }
    if (!migration) migration = sparseMigrationFixture(kbId, 'PREPARING')
    else if (path.endsWith('/backfill')) migration = sparseMigrationFixture(kbId, 'DUAL_WRITING')
    else if (path.endsWith('/shadow')) migration = sparseMigrationFixture(kbId, 'SHADOW_VALIDATING')
    else if (path.endsWith('/cutover')) migration = sparseMigrationFixture(kbId, 'CUTOVER')
    else if (path.endsWith('/complete')) migration = sparseMigrationFixture(kbId, 'COMPLETED')
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(migration) })
  })
}

function sparseMigrationFixture(kbId: string, state: string) {
  return {
    id: 'e2e-sparse-migration', kbId, profileId: 'e2e-profile', state,
    sourceChunkCount: 100, indexedChunkCount: state === 'PREPARING' ? 0 : 100,
    expectedDimension: 8, actualDimension: 8, baselineP95Ms: 100, candidateP95Ms: 110,
    baselineFallbackRate: 0.01, candidateFallbackRate: 0, legacyBm25Enabled: false,
    errorMessage: null, createdAt: '2026-07-15T00:00:00Z', updatedAt: '2026-07-15T00:00:00Z',
  }
}

async function loginAs(page: Page, loginUsername: string, loginPassword: string) {
  await page.goto('/')
  await page.locator('input').nth(0).fill(loginUsername)
  await page.locator('input[type="password"]').fill(loginPassword)
  await page.getByRole('button', { name: /登录/ }).click()
  await expect(page.getByText('知识库').first()).toBeVisible()
}

async function clearSession(page: Page) {
  await page.evaluate((key) => localStorage.removeItem(key), csrfStorageKey)
  await page.context().clearCookies()
}

async function navigateTo(page: Page, label: string) {
  await page.getByRole('link', { name: label, exact: true }).click()
}

async function createAccount(
  page: Page,
  accountUsername: string,
  accountPassword: string,
  tenantId: string,
  roleCode: string,
) {
  await page.getByRole('button', { name: /新建账号/ }).click()
  await page.getByLabel('账号名').fill(accountUsername)
  await page.getByLabel('租户').fill(tenantId)
  await page.getByLabel('角色').selectOption(roleCode)
  await page.getByLabel('初始密码').fill(accountPassword)
  await page.getByRole('button', { name: /^保存$/ }).click()
}

function accountRow(page: Page, accountUsername: string) {
  return page.locator('tr').filter({ hasText: accountUsername })
}

async function findKnowledgeBaseId(page: Page, kbName: string): Promise<string | undefined> {
  return page.evaluate(async (name) => {
    const response = await fetch('/api/v1/knowledge-bases', { credentials: 'include' })
    if (!response.ok) throw new Error(`knowledge base lookup failed: ${response.status}`)
    const knowledgeBases = await response.json() as Array<{ id: string; name: string }>
    return knowledgeBases.find((knowledgeBase) => knowledgeBase.name === name)?.id
  }, kbName)
}

async function cleanupSuccessfulRun(page: Page, resources: E2eRunResources) {
  if (!resources.kbId) throw new Error('cannot clean E2E run without a knowledge base id')
  for (const path of [
    `/api/v1/knowledge-bases/${resources.kbId}`,
    `/api/v1/ops/accounts/${resources.accountUsername}`,
    `/api/v1/ops/accounts/${resources.gateUsername}`,
  ]) {
    await deleteThroughBrowserSession(page, path)
  }
}

async function deleteThroughBrowserSession(page: Page, path: string) {
  const result = await page.evaluate(async ({ requestPath, csrfKey }) => {
    const csrfToken = localStorage.getItem(csrfKey)
    const response = await fetch(requestPath, {
      method: 'DELETE',
      credentials: 'include',
      headers: csrfToken ? { 'X-Dupi-CSRF-Token': csrfToken } : {},
    })
    return { ok: response.ok, status: response.status, body: await response.text() }
  }, { requestPath: path, csrfKey: csrfStorageKey })
  if (!result.ok) {
    throw new Error(`E2E cleanup failed for ${path}: ${result.status} ${result.body}`)
  }
}

function installBrowserErrorGate(page: Page) {
  const consoleErrors: string[] = []
  const pageErrors: string[] = []
  const failedRequests: string[] = []

  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => pageErrors.push(error.message))
  page.on('requestfailed', (request) => {
    const errorText = request.failure()?.errorText ?? ''
    if (request.method() === 'GET' && errorText.includes('net::ERR_ABORTED')) return
    failedRequests.push(`${request.method()} ${request.url()} ${errorText}`.trim())
  })
  page.on('response', (response) => {
    const url = response.url()
    if ((url.includes('/api/') || url.includes('/actuator/')) && response.status() >= 400) {
      failedRequests.push(`${response.status()} ${url}`)
    }
  })

  return {
    async expectClean(stage: string) {
      const toastText = (await page.locator('.fixed.bottom-4').textContent().catch(() => '')) ?? ''
      const visibleError = /失败|错误|CSRF token required|HTTP 5\d{2}|Unauthorized|Forbidden/i.test(toastText)
        ? [`toast: ${toastText}`]
        : []
      const errors = [...pageErrors, ...consoleErrors, ...failedRequests, ...visibleError]
      pageErrors.length = 0
      consoleErrors.length = 0
      failedRequests.length = 0
      expect(errors, `${stage} browser/application errors`).toEqual([])
    },
  }
}

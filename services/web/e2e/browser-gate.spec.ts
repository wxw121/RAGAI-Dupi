import { expect, test, type Page } from '@playwright/test'

const username = process.env.E2E_ADMIN_USERNAME
const password = process.env.E2E_ADMIN_PASSWORD

test.beforeAll(() => {
  if (!username || !password) {
    throw new Error('Missing E2E_ADMIN_USERNAME or E2E_ADMIN_PASSWORD. The browser gate must use a real login.')
  }
})

test('real login and core authenticated UI flows work without browser errors', async ({ page }) => {
  const gate = installBrowserErrorGate(page)
  const kbName = `e2e-browser-${Date.now()}`

  await page.goto('/')
  await page.locator('input').nth(0).fill(username!)
  await page.locator('input[type="password"]').fill(password!)
  await page.getByRole('button', { name: /登录/ }).click()
  await expect(page.getByText('知识库').first()).toBeVisible()
  await gate.expectClean('login')

  await page.getByRole('button', { name: /新建知识库/ }).first().click()
  await page.locator('input').nth(0).fill(kbName)
  await page.getByRole('button', { name: /^创建$/ }).click()
  await expect(page.getByText(kbName)).toBeVisible()
  await gate.expectClean('create knowledge base')

  await page.getByText(kbName).click()
  await expect(page.getByRole('button', { name: /文档/ })).toBeVisible()
  await page.getByRole('button', { name: /文档/ }).click()
  await expect(page.getByText(/索引维护/)).toBeVisible()
  await gate.expectClean('knowledge base documents tab')

  await page.getByRole('button', { name: /问答/ }).click()
  await expect(page.getByText(/智能问答|暂无可用文档|输入问题|先上传文档/).first()).toBeVisible()
  await gate.expectClean('knowledge base chat tab')

  await page.getByRole('button', { name: /RAG 评估/ }).click()
  await expect(page.getByRole('button', { name: /运行评估/ })).toBeVisible()
  await gate.expectClean('knowledge base rag eval tab')

  await page.goto('/ops/audit-logs')
  await expect(page.getByRole('heading', { name: /审计日志/ })).toBeVisible()
  await gate.expectClean('ops audit logs page')

  await page.goto('/ops/accounts')
  await expect(page.getByRole('heading', { name: /账号管理|账户管理/ })).toBeVisible()
  await gate.expectClean('ops accounts page')

  await page.goto('/ops/roles')
  await expect(page.getByRole('heading', { name: /角色管理/ })).toBeVisible()
  await gate.expectClean('ops roles page')
})

function installBrowserErrorGate(page: Page) {
  const consoleErrors: string[] = []
  const pageErrors: string[] = []
  const failedRequests: string[] = []

  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text())
    }
  })
  page.on('pageerror', (error) => {
    pageErrors.push(error.message)
  })
  page.on('requestfailed', (request) => {
    failedRequests.push(`${request.method()} ${request.url()} ${request.failure()?.errorText ?? ''}`.trim())
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
      const visibleError = /失败|错误|HTTP 5\d{2}|Unauthorized|Forbidden/i.test(toastText)
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

export interface E2eRunResources {
  runId: string
  gateUsername: string
  accountUsername: string
  kbName: string
  kbId?: string
  pageUrl?: string
}

export async function finalizeE2eRun(
  completed: boolean,
  resources: E2eRunResources,
  cleanup: () => Promise<void>,
  attach: (name: string, body: string) => void | Promise<void>,
): Promise<void> {
  if (!completed) {
    await attach('e2e-evidence', JSON.stringify(resources, null, 2))
    return
  }
  try {
    await cleanup()
  } catch (error) {
    await attach('e2e-evidence', JSON.stringify({
      ...resources,
      cleanupError: error instanceof Error ? error.message : String(error),
    }, null, 2))
    throw error
  }
}

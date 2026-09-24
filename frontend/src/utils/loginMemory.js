export const LOGIN_MEMORY_KEY = 'ai-knowledge.login.v1'
export const LOGIN_GRACE_MS = 5 * 60 * 1000
export function clearLoginMemory() {
  try { globalThis.localStorage?.removeItem(LOGIN_MEMORY_KEY) } catch { /* Storage disabled. */ }
}
export function rememberLogin(accessToken, tokenExpiresAt, now = Date.now()) {
  if (!accessToken || tokenExpiresAt <= now) return clearLoginMemory()
  try {
    globalThis.localStorage?.setItem(LOGIN_MEMORY_KEY, JSON.stringify({
      accessToken, tokenExpiresAt, restoreUntil: Math.min(tokenExpiresAt, now + LOGIN_GRACE_MS),
    }))
  } catch { /* Continue with an in-memory session. */ }
}
export function readLoginMemory(now = Date.now()) {
  try {
    const value = JSON.parse(globalThis.localStorage?.getItem(LOGIN_MEMORY_KEY) || 'null')
    if (typeof value?.accessToken === 'string' && value.accessToken.length > 0
        && Number.isFinite(value.tokenExpiresAt) && Number.isFinite(value.restoreUntil)
        && value.tokenExpiresAt > now && value.restoreUntil > now
        && value.restoreUntil <= now + LOGIN_GRACE_MS) return value
  } catch { /* Invalid storage is not a login. */ }
  clearLoginMemory()
  return null
}

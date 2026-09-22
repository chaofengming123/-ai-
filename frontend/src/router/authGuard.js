const destinations = ['/dashboard', '/knowledge-bases', '/documents', '/chat', '/embeddings']
export function safeDestination(value) {
  return typeof value === 'string' && destinations.includes(value) ? value : '/knowledge-bases'
}
export function authGuard(to, isLoggedIn) {
  if (to.meta.requiresAuth && !isLoggedIn) {
    return { path: '/login', query: { redirect: safeDestination(to.path) }, replace: true }
  }
  return true
}

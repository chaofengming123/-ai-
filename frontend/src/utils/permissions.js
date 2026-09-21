export function canManageKnowledgeBases(user) {
  return user?.role === 'ADMIN'
}
export function roleLabel(user) {
  return user?.role === 'ADMIN' ? '管理员' : '普通用户'
}

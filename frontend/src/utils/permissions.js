export function hasPermission(user, action) {
  return Array.isArray(user?.permissions) && user.permissions.includes(`knowledge-base:${action}`)
}
export function canManageKnowledgeBases(user) {
  return ['create', 'update', 'delete'].some(action => hasPermission(user, action))
}
export function roleLabel(user) {
  const names = { USER: '普通用户', EDITOR: '编辑者', ADMIN: '管理员' }
  return user?.roles?.map(role => names[role] ?? role).join('、') || '未分配角色'
}

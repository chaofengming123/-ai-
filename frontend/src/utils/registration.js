export function validateRegistration(username, password, confirmation) {
  if (!/^[a-z0-9_]{3,32}$/.test(username.trim().toLowerCase())) {
    return '用户名需要 3–32 位，只能包含英文字母、数字和下划线。'
  }
  if (!password.trim() || [...password].length < 12 || new TextEncoder().encode(password).length > 72) {
    return '密码至少 12 个字符，不能全为空白，UTF-8 编码最多 72 字节。'
  }
  if (password !== confirmation) return '两次输入的密码不一致。'
  return ''
}

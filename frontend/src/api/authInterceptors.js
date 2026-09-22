// 在 main.js 注入 store，避免 http → store → http 的循环依赖。
export function installAuthInterceptors(http, auth) {
  function protectedRequest(config) {
    // 只向本项目相对路径附带凭证，不能向任意绝对地址发送。
    return config.baseURL === '/api' && (
      config.url === '/auth/me' ||
      /^\/(?:knowledge-bases|documents|chat)(?:\/|$|\?)/.test(config.url ?? '')
    )
  }
  const requestId = http.interceptors.request.use(config => {
    if (protectedRequest(config)) {
      config.authVersion = auth.sessionVersion
      if (auth.accessToken) config.headers.set('Authorization', 'Bearer ' + auth.accessToken)
      else config.headers.delete('Authorization')
    }
    return config
  })
  const responseId = http.interceptors.response.use(response => response, error => {
    const config = error.config
    if (config && protectedRequest(config) && error.response?.status === 401 &&
        config.authVersion === auth.sessionVersion && auth.isLoggedIn) {
      auth.logout('登录已失效，请重新登录。')
    }
    // 403 不退出、不重试写操作；只刷新一次身份信息，让界面跟上角色变更。
    if (config && protectedRequest(config) && config.url !== '/auth/me' &&
        error.response?.status === 403 && config.authVersion === auth.sessionVersion && auth.isLoggedIn) {
      void auth.verifySession?.()
    }
    return Promise.reject(error)
  })
  return () => {
    http.interceptors.request.eject(requestId)
    http.interceptors.response.eject(responseId)
  }
}

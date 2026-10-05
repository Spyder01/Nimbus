function xsrfToken() {
  const raw = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)?.[1]
  return raw ? decodeURIComponent(raw) : undefined
}

// fetch wrapper: same-origin cookies, JSON body, and the CSRF header on writes.
export async function api(path: string, init: RequestInit & { json?: unknown } = {}) {
  const { json, headers, ...rest } = init
  const token = xsrfToken()
  return fetch(path, {
    ...rest,
    headers: {
      Accept: 'application/json',
      ...(json !== undefined && { 'Content-Type': 'application/json' }),
      ...(token && { 'X-XSRF-TOKEN': token }),
      ...headers,
    },
    body: json !== undefined ? JSON.stringify(json) : rest.body,
  })
}

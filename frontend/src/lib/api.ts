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

export class ApiError extends Error {
  status: number
  code: string
  body: Record<string, unknown> | null

  constructor(status: number, code: string, message: string, body: Record<string, unknown> | null) {
    super(message)
    this.status = status
    this.code = code
    this.body = body
  }
}

// JSON request that throws ApiError for any non-2xx response ({error, message} bodies from the backend).
export async function request<T>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const res = await api(path, init)
  const text = await res.text()
  let body: Record<string, unknown> | null = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    /* non-JSON body */
  }
  if (!res.ok) {
    throw new ApiError(
      res.status,
      (body?.error as string) ?? 'error',
      (body?.message as string) ?? res.statusText ?? 'Request failed',
      body,
    )
  }
  return body as T
}

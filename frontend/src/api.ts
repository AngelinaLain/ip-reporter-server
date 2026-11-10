import type { Session } from "./types";
let csrfToken = "";
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}

// 使用同源 HttpOnly 会话；写操作携带服务器签发的 CSRF Token，不在浏览器存储密码。
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const method = options.method || "GET";
  const headers = new Headers(options.headers);
  if (options.body) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method))
    headers.set("X-CSRF-TOKEN", csrfToken);
  const response = await fetch(`/api${path}`, {
    ...options,
    headers,
    credentials: "same-origin",
  });
  if (!response.ok) {
    let message =
      response.status === 401
        ? "会话已结束，请重新登录"
        : `请求失败 (${response.status})`;
    try {
      const data = await response.json();
      message = data.message || message;
    } catch {
      /* 非 JSON 错误保留状态提示 */
    }
    throw new ApiError(message, response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json();
}
export async function session(): Promise<Session> {
  const value = await api<Session>("/auth/session");
  csrfToken = value.csrfToken;
  return value;
}
export const jsonBody = (value: unknown) => JSON.stringify(value);

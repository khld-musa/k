export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080/api/v1";

/** Error thrown for non-2xx responses. `fieldErrors` carries backend @Valid failures keyed by field name. */
export class ApiError extends Error {
  status: number;
  fieldErrors: Record<string, string>;

  constructor(message: string, status: number, fieldErrors: Record<string, string> = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

/** Returns backend field errors from a caught error, or an empty object. */
export function getFieldErrors(err: unknown): Record<string, string> {
  return err instanceof ApiError ? err.fieldErrors : {};
}

async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => "");
  let errorData: { message?: string; error?: string; fieldErrors?: Record<string, string> } = {};
  try {
    errorData = text ? JSON.parse(text) : {};
  } catch {
    // Non-JSON body (e.g. nginx error page)
  }
  const fieldErrors = errorData.fieldErrors || {};
  // [FIX P1-5] Spring Boot error responses use 'error' or 'message' depending on the handler.
  // For validation failures prefer the first field message over the generic "One or more fields are invalid".
  const message = Object.values(fieldErrors)[0] || errorData.message || errorData.error || response.statusText;
  return new ApiError(message, response.status, fieldErrors);
}

let isRefreshing = false;
let refreshQueue: Array<{
  resolve: (token: string) => void;
  reject: (err: any) => void;
}> = [];

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = localStorage.getItem("refresh_token");
  if (!refreshToken) return null;

  try {
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });

    if (!response.ok) return null;

    const data = await response.json();
    localStorage.setItem("auth_token", data.token);
    if (data.refreshToken) {
      localStorage.setItem("refresh_token", data.refreshToken);
    }
    return data.token;
  } catch {
    return null;
  }
}

export async function apiClient(endpoint: string, options: RequestInit = {}): Promise<any> {
  const token = typeof window !== "undefined" ? localStorage.getItem("auth_token") : null;

  const headers = new Headers(options.headers);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (!headers.has("Content-Type") && !(options.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE_URL}${endpoint}`, {
    cache: "no-store",
    ...options,
    headers,
  });

  // Handle 401 — attempt token refresh (except for auth endpoints)
  if (response.status === 401 && !endpoint.includes("/auth/login") && !endpoint.includes("/auth/refresh")) {
    if (!isRefreshing) {
      isRefreshing = true;
      let newToken: string | null = null;
      try {
        newToken = await refreshAccessToken();
      } finally {
        isRefreshing = false;
      }

      if (newToken) {
        // Drain the queue with the new token
        refreshQueue.forEach((item) => item.resolve(newToken!));
        refreshQueue = [];

        // Retry original request with refreshed token
        const retryHeaders = new Headers(options.headers);
        retryHeaders.set("Authorization", `Bearer ${newToken}`);
        if (!retryHeaders.has("Content-Type") && !(options.body instanceof FormData)) {
          retryHeaders.set("Content-Type", "application/json");
        }
        const retryResponse = await fetch(`${API_BASE_URL}${endpoint}`, {
          cache: "no-store",
          ...options,
          headers: retryHeaders,
        });
        if (!retryResponse.ok) {
          throw await toApiError(retryResponse);
        }
        const text = await retryResponse.text().catch(() => "");
        return text ? JSON.parse(text) : null;
      } else {
        // Refresh failed — reject all queued requests, clear auth, and redirect to login
        const expiredError = new Error("Session expired. Please log in again.");
        refreshQueue.forEach((item) => item.reject(expiredError));
        refreshQueue = [];
        localStorage.removeItem("auth_token");
        localStorage.removeItem("refresh_token");
        localStorage.removeItem("auth_user");
        if (typeof window !== "undefined") window.location.href = "/login";
        throw expiredError;
      }
    } else {
      // Queue the request until the ongoing refresh resolves
      return new Promise((resolve, reject) => {
        refreshQueue.push({
          resolve: async (newToken: string) => {
            try {
              const retryHeaders = new Headers(options.headers);
              retryHeaders.set("Authorization", `Bearer ${newToken}`);
              const result = await apiClient(endpoint, { ...options, headers: retryHeaders });
              resolve(result);
            } catch (err) {
              reject(err);
            }
          },
          reject,
        });
      });
    }
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  const text = await response.text().catch(() => "");
  return text ? JSON.parse(text) : null;
}

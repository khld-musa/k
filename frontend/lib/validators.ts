// Client-side validation rules. Each mirrors the corresponding backend check
// (InputSanitizer / request DTO annotations) so the form rejects what the API would reject.

const IPV4 = /^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$/
const HOSTNAME = /^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/
const MSISDN = /^\+?\d{7,15}$/
const SSH_USERNAME = /^[a-zA-Z0-9._-]+$/
const SAFE_PATH = /^[a-zA-Z0-9+_./-]+$/
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const STRONG_PASSWORD = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/

export const isValidIp = (v: string) => IPV4.test(v.trim())
export const isValidHost = (v: string) => IPV4.test(v.trim()) || HOSTNAME.test(v.trim())
export const isValidMsisdn = (v: string) => MSISDN.test(v.trim())
export const isValidSshUsername = (v: string) => v.trim().length <= 100 && SSH_USERNAME.test(v.trim())
export const isSafePath = (v: string) => SAFE_PATH.test(v.trim())
export const isValidEmail = (v: string) => EMAIL.test(v.trim())
export const isStrongPassword = (v: string) => v.length <= 100 && STRONG_PASSWORD.test(v)

export const STRONG_PASSWORD_HINT =
  "At least 8 characters with an uppercase letter, a lowercase letter, a digit and one of @$!%*?& (no other symbols)."

export function isValidPort(v: string | number) {
  const n = Number(v)
  return Number.isInteger(n) && n >= 1 && n <= 65535
}

/** Integer within [min, max]; empty string is invalid. */
export function isIntInRange(v: string | number, min: number, max: number) {
  if (v === "" || v === null || v === undefined) return false
  const n = Number(v)
  return Number.isInteger(n) && n >= min && n <= max
}

/** Splits a multi-value text box (newline, comma or semicolon separated). */
export function splitList(text: string) {
  return text.split(/[\n,;]+/).map(s => s.trim()).filter(Boolean)
}

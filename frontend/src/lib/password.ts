import { z } from 'zod'

export const PASSWORD_MIN = 12
export const PASSWORD_MAX = 128

/** Same length policy as the backend (`PasswordPolicy`): no composition rules. */
export const passwordSchema = z
  .string()
  .min(PASSWORD_MIN, `Use at least ${PASSWORD_MIN} characters.`)
  .max(PASSWORD_MAX, `Use at most ${PASSWORD_MAX} characters.`)
  .refine((v) => v.trim().length > 0, 'The password cannot be blank.')

export interface PasswordStrength {
  level: 0 | 1 | 2 | 3
  label: string
}

/** A length-based hint only (the server is the judge). A long phrase beats a short, "complex" one. */
export function passwordStrength(password: string): PasswordStrength {
  const n = password.length
  if (n === 0) return { level: 0, label: '' }
  if (n < PASSWORD_MIN) return { level: 0, label: `Too short (${n}/${PASSWORD_MIN})` }
  if (n < 16) return { level: 1, label: 'Acceptable' }
  if (n < 20) return { level: 2, label: 'Good' }
  return { level: 3, label: 'Strong' }
}

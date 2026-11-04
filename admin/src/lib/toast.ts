import { createContext, useContext } from 'react'

export type ToastTone = 'ok' | 'error' | 'info'
export type ToastFn = (text: string, tone?: ToastTone) => void

export const ToastContext = createContext<ToastFn>(() => undefined)

/** Shows a short notice (bottom-right; full width on phones). */
export function useToast(): ToastFn {
  return useContext(ToastContext)
}

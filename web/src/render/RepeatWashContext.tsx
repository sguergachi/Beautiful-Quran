import { createContext, useContext, type ReactNode } from 'react'
import { createRepeatWashGate, type RepeatWashGate } from './repeatWashGate'
import { useMemo } from 'react'

const RepeatWashGateContext = createContext<RepeatWashGate | null>(null)

/** Per-ayah provider: all words share one sequential orange-wash queue. */
export function RepeatWashGateProvider({ children }: { children: ReactNode }) {
  const gate = useMemo(() => createRepeatWashGate(), [])
  return (
    <RepeatWashGateContext.Provider value={gate}>
      {children}
    </RepeatWashGateContext.Provider>
  )
}

export function useRepeatWashGate(): RepeatWashGate {
  const gate = useContext(RepeatWashGateContext)
  return useMemo(() => gate ?? createRepeatWashGate(), [gate])
}

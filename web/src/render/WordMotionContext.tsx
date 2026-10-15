import { createContext, useContext, useMemo, type ReactNode } from 'react'

export interface WashTarget {
  paint(progress: number, feather: number): void
}
/** Per rendered verse/page: connected prefixes never escape into another reader. */
export class WordMotionRegistry {
  targets = new Map<string, WashTarget>()
  prefixes = new Map<string, number>()
  progress = new Map<string, number>()
  private activation: number | undefined
  enter(activation: number): void {
    if (this.activation !== undefined && this.activation !== activation) {
      this.prefixes.clear()
      this.progress.clear()
    }
    this.activation = activation
  }
  prefix(key: string, progress: number, feather: number): void {
    this.prefixes.set(key, progress)
    this.targets.get(key)?.paint(progress, feather)
  }
}
const Context = createContext<WordMotionRegistry | null>(null)
export function WordMotionProvider({ children }: { children: ReactNode }) {
  const parent = useContext(Context)
  const registry = useMemo(() => parent ?? new WordMotionRegistry(), [parent])
  return <Context.Provider value={registry}>{children}</Context.Provider>
}
export function useWordMotionRegistry(): WordMotionRegistry {
  const registry = useContext(Context)
  return useMemo(() => registry ?? new WordMotionRegistry(), [registry])
}

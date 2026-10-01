export interface PageTurnQueue {
  settled: number
  flight: { from: number; to: number } | null
  queued: number | null
}

/** Keep the moving leaf intact; only the latest requested landing waits. */
export function requestPageTurn(state: PageTurnQueue, destination: number): PageTurnQueue {
  if (state.flight) {
    return { ...state, queued: destination === state.flight.to ? null : destination }
  }
  return destination === state.settled ? state : {
    ...state,
    flight: { from: state.settled, to: destination },
    queued: null,
  }
}

export function finishPageTurn(state: PageTurnQueue, flight = state.flight): PageTurnQueue {
  if (!state.flight || state.flight !== flight) return state
  return requestPageTurn(
    { settled: state.flight.to, flight: null, queued: null },
    state.queued ?? state.flight.to,
  )
}

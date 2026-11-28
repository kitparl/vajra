export interface Session {
  username: string;
  /** 'PAPER' or 'DELTA_TESTNET'; kept as string so an unknown mode is shown, not hidden. */
  tradingMode: string;
}

export type LoginResult = 'ok' | 'bad-credentials' | { locked: number };

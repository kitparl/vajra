import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { LiveMessage, RiskStatus } from './models';

/** A LiveService stand-in: push messages with `emit`, trigger reconnect hooks with `reconnect`. */
export function fakeLive() {
  const messages$ = new Subject<LiveMessage>();
  const hooks: Array<() => void> = [];
  return {
    messages$,
    status: signal<'connecting' | 'live' | 'reconnecting'>('live'),
    tradingMode: signal<string | null>(null),
    risk: signal<RiskStatus | null>(null),
    coreAvailable: signal(true),
    riskAvailable: signal(true),
    connect: vi.fn(),
    disconnect: vi.fn(),
    onReconnect: (fn: () => void) => {
      hooks.push(fn);
      return () => {
        hooks.splice(hooks.indexOf(fn), 1);
      };
    },
    hookCount: () => hooks.length,
    emit: (m: LiveMessage) => messages$.next(m),
    reconnect: () => [...hooks].forEach((h) => h()),
  };
}

export type FakeLive = ReturnType<typeof fakeLive>;

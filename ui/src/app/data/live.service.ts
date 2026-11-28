import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { LiveMessage, RiskStatus } from './models';

const BACKOFF_MS = [1000, 2000, 5000, 10_000] as const;
const SESSION_ENDED = 4401;
/** No message of any type for this long on a live socket means it is half-open (the gateway sends at least every 1 s). */
const SILENCE_MS = 10_000;
const TYPES: ReadonlySet<string> = new Set(['hello', 'quote', 'order', 'fill', 'position', 'risk', 'upstream']);

export type LiveStatus = 'connecting' | 'live' | 'reconnecting';

/** The terminal's one WebSocket: typed messages, reconnect with backoff, and a re-fetch hook after every connect. */
@Injectable({ providedIn: 'root' })
export class LiveService implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly subject = new Subject<LiveMessage>();
  private readonly hooks: Array<() => void> = [];
  private readonly statusState = signal<LiveStatus>('connecting');
  private readonly modeState = signal<string | null>(null);
  private readonly riskState = signal<RiskStatus | null>(null);
  private readonly coreState = signal(true);
  private readonly riskUpstreamState = signal(true);

  private socket: WebSocket | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private watchdog: ReturnType<typeof setTimeout> | null = null;
  private attempt = 0;
  private running = false;

  readonly messages$: Observable<LiveMessage> = this.subject.asObservable();
  readonly status = this.statusState.asReadonly();
  /** Mode from the last hello; null before the first one. */
  readonly tradingMode = this.modeState.asReadonly();
  readonly risk = this.riskState.asReadonly();
  /** False while the gateway reports core unreachable; back to true on `available:true` or the next quote. */
  readonly coreAvailable = this.coreState.asReadonly();
  /** False while the gateway reports risk unreachable. */
  readonly riskAvailable = this.riskUpstreamState.asReadonly();

  /**
   * Called after every successful open, the first included: a snapshot taken once the socket is live closes
   * the gap where an event arrives between the REST call and the subscription. Returns the unregister function.
   */
  onReconnect(hook: () => void): () => void {
    this.hooks.push(hook);
    return () => {
      const i = this.hooks.indexOf(hook);
      if (i >= 0) {
        this.hooks.splice(i, 1);
      }
    };
  }

  connect(): void {
    if (this.running) {
      return;
    }
    this.running = true;
    this.open();
  }

  disconnect(): void {
    this.running = false;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.clearWatchdog();
    this.reset();
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.onclose = null;
      socket.close();
    }
  }

  ngOnDestroy(): void {
    this.disconnect();
  }

  /** Drops everything that belonged to the last connection, so a later login never shows it. */
  private reset(): void {
    this.attempt = 0;
    this.statusState.set('connecting');
    this.modeState.set(null);
    this.riskState.set(null);
    this.coreState.set(true);
    this.riskUpstreamState.set(true);
  }

  private open(): void {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${location.host}/ws`);
    this.socket = socket;
    socket.onopen = () => {
      this.attempt = 0;
      this.statusState.set('live');
      this.armWatchdog(socket);
      [...this.hooks].forEach((hook) => hook());
    };
    socket.onmessage = (event: MessageEvent<string>) => this.receive(event.data);
    socket.onclose = (event: CloseEvent) => void this.closed(event.code);
  }

  private receive(raw: string): void {
    if (this.socket) {
      this.armWatchdog(this.socket);
    }
    let message: LiveMessage;
    try {
      const parsed = JSON.parse(raw) as { type?: unknown; data?: unknown };
      if (typeof parsed.type !== 'string' || !TYPES.has(parsed.type) || parsed.data === undefined) {
        return;
      }
      message = parsed as LiveMessage;
    } catch {
      return;
    }
    if (message.type === 'hello') {
      this.modeState.set(message.data.tradingMode);
    } else if (message.type === 'risk') {
      this.riskState.set(message.data);
    } else if (message.type === 'quote') {
      this.coreState.set(true);
    } else if (message.type === 'upstream') {
      (message.data.upstream === 'core' ? this.coreState : this.riskUpstreamState).set(message.data.available);
    }
    this.subject.next(message);
  }

  private armWatchdog(socket: WebSocket): void {
    this.clearWatchdog();
    this.watchdog = setTimeout(() => {
      this.watchdog = null;
      socket.onclose = null;
      socket.close();
      void this.closed(1006);
    }, SILENCE_MS);
  }

  private clearWatchdog(): void {
    if (this.watchdog !== null) {
      clearTimeout(this.watchdog);
      this.watchdog = null;
    }
  }

  private async closed(code: number): Promise<void> {
    this.clearWatchdog();
    this.socket = null;
    if (!this.running) {
      return;
    }
    if (code === SESSION_ENDED) {
      this.running = false;
      this.reset();
      await this.auth.loadSession();
      await this.router.navigateByUrl('/login');
      return;
    }
    this.statusState.set('reconnecting');
    const delay = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)];
    this.attempt++;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.open();
    }, delay);
  }
}

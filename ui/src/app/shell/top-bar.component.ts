import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ThemeService } from '../core/theme.service';
import { ApiService, panelError } from '../data/api.service';
import { PanelError } from '../data/models';
import { LiveService } from '../data/live.service';
import { createThrottle } from '../data/throttle';

const POLL_MS = 15_000;
const PNL_MIN_INTERVAL_MS = 2000;

@Component({
  selector: 'app-top-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="bar">
      <strong class="brand">Vajra</strong>
      <span class="mode" [class.testnet]="mode().kind === 'testnet'" [class.danger]="mode().kind === 'unknown'">{{ mode().label }}</span>
      <span class="conn" [class.offline]="!auth.reachable()" role="status">
        <span class="dot" aria-hidden="true"></span>{{ auth.reachable() ? 'online' : 'offline' }}
      </span>
      <span class="live">{{ live.status() }}</span>
      @if (live.risk()?.halted) {
        <span class="halted" role="status">HALTED</span>
      }
      <span class="spacer"></span>
      <span class="pnl" title="Global P&amp;L">P&amp;L {{ pnlNotice() ? pnlNotice() + ' unavailable' : (netPnl() ?? '—') }}</span>
      <button type="button" class="kill" aria-disabled="true" aria-describedby="kill-hint">Kill switch</button>
      <small id="kill-hint" class="hint">(slice 4)</small>
      <button type="button" class="theme" (click)="theme.toggle()"
              [attr.aria-label]="'Switch to ' + (theme.theme() === 'dark' ? 'light' : 'dark') + ' theme'">
        {{ theme.theme() === 'dark' ? 'Light' : 'Dark' }}
      </button>
      <button type="button" class="logout" (click)="logout()">Log out</button>
      @if (logoutError()) {
        <span class="err" role="alert">Logout failed (offline)</span>
      }
    </header>
  `,
  styles: `
    .bar { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2) var(--space-4);
      background: var(--surface); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .spacer { flex: 1; }
    .mode { padding: 2px var(--space-2); border-radius: var(--radius); background: var(--neutral-badge);
      color: var(--text); font-weight: 700; font-size: 12px; letter-spacing: 0.04em; }
    .mode.danger { background: var(--danger); color: var(--danger-contrast); }
    .mode.testnet { background: var(--amber); color: var(--amber-contrast); }
    .conn { display: inline-flex; align-items: center; gap: var(--space-1); color: var(--text-muted); }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--ok); }
    .conn.offline { color: var(--danger); }
    .conn.offline .dot { background: var(--danger); }
    .live { color: var(--text-muted); font-size: 12px; }
    .halted { padding: 2px var(--space-2); border: 2px solid var(--danger); border-radius: var(--radius);
      color: var(--danger); font-weight: 700; letter-spacing: 0.04em; }
    .pnl { font-family: var(--font-mono); color: var(--text-muted); }
    button { padding: var(--space-1) var(--space-3); background: var(--surface-2); border: 1px solid var(--border);
      border-radius: var(--radius); cursor: pointer; }
    button.kill { opacity: 0.5; cursor: not-allowed; }
    .hint { color: var(--text-muted); }
    .err { color: var(--danger); }
  `,
})
export class TopBarComponent {
  protected readonly auth = inject(AuthService);
  protected readonly live = inject(LiveService);
  private readonly api = inject(ApiService);
  protected readonly theme = inject(ThemeService);
  private readonly router = inject(Router);

  protected readonly logoutError = signal(false);
  protected readonly mode = computed(() => {
    // The session value is polled from core every 15 s; hello only fills the gap before the first one.
    const raw = this.live.coreAvailable() ? (this.auth.session()?.tradingMode ?? this.live.tradingMode() ?? undefined) : 'UNKNOWN';
    if (raw === undefined) {
      return { kind: 'none', label: '—' };
    }
    if (raw === 'PAPER') {
      return { kind: 'paper', label: 'PAPER' };
    }
    if (raw === 'DELTA_TESTNET') {
      return { kind: 'testnet', label: 'TESTNET' };
    }
    return { kind: 'unknown', label: raw };
  });
  protected readonly netPnl = signal<string | null>(null);
  private polling = false;
  protected readonly pnlError = signal<PanelError | null>(null);
  protected readonly pnlNotice = computed<PanelError | null>(() => this.pnlError() ?? (this.live.coreAvailable() ? null : 'core'));
  private readonly requestPnl = createThrottle(() => void this.fetchPnl(), PNL_MIN_INTERVAL_MS, inject(DestroyRef));

  constructor() {
    const timer = setInterval(() => void this.poll(), POLL_MS);
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => clearInterval(timer));
    this.requestPnl();
    destroyRef.onDestroy(this.live.onReconnect(() => this.requestPnl()));
    this.live.messages$.pipe(takeUntilDestroyed()).subscribe((m) => {
      if (m.type === 'position' || m.type === 'quote') {
        this.requestPnl();
      }
    });
  }

  private async fetchPnl(): Promise<void> {
    try {
      this.netPnl.set((await this.api.pnl()).netPnl);
      this.pnlError.set(null);
    } catch (err) {
      // The last value stays; a 401 is already redirected by ApiService.
      const e = panelError(err);
      this.pnlError.set(e === 'error' ? null : e);
    }
  }

  private async poll(): Promise<void> {
    if (this.polling) {
      return;
    }
    this.polling = true;
    try {
      await this.auth.loadSession();
    } finally {
      this.polling = false;
    }
    if (!this.auth.session()) {
      await this.router.navigateByUrl('/login');
    }
  }

  protected async logout(): Promise<void> {
    this.logoutError.set(false);
    try {
      await this.auth.logout();
    } catch {
      this.logoutError.set(true);
      return;
    }
    await this.router.navigateByUrl('/login');
  }
}

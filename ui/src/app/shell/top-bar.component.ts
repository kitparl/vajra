import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ThemeService } from '../core/theme.service';

const POLL_MS = 15_000;

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
      <span class="spacer"></span>
      <span class="pnl" title="Global P&amp;L">P&amp;L —</span>
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
  protected readonly theme = inject(ThemeService);
  private readonly router = inject(Router);

  protected readonly logoutError = signal(false);
  protected readonly mode = computed(() => {
    const raw = this.auth.session()?.tradingMode;
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
  private polling = false;

  constructor() {
    const timer = setInterval(() => void this.poll(), POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
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

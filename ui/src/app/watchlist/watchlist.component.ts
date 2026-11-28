import { ChangeDetectionStrategy, Component, DestroyRef, computed, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService, panelError } from '../data/api.service';
import { LiveService } from '../data/live.service';
import { PanelError, Quote } from '../data/models';
import { PanelNoticeComponent } from '../data/panel-notice.component';

const DAY_MS = 24 * 60 * 60 * 1000;
const REFERENCE_REFRESH_MS = 10 * 60 * 1000;
const REFERENCE_WINDOW_BEFORE_MS = 5 * 60 * 1000;
const REFERENCE_WINDOW_AFTER_MS = 60 * 1000;

type Cell = 'mark' | 'bid' | 'ask';

interface Row {
  symbol: string;
  mark: string | null;
  bid: string | null;
  ask: string | null;
  stale: boolean;
  /** Cells whose value changed on the last live quote. */
  changed: Readonly<Record<Cell, boolean>>;
}

const NOT_CHANGED = { mark: false, bid: false, ask: false } as const;

@Component({
  selector: 'app-watchlist',
  imports: [PanelNoticeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (notice(); as e) {
      <app-panel-notice [error]="e" />
    }
    <div class="scroll">
      <table>
        <thead>
          <tr>
            <th scope="col">Symbol</th>
            <th scope="col" class="num">Mark</th>
            <th scope="col" class="num">Bid</th>
            <th scope="col" class="num">Ask</th>
            <th scope="col" class="num">24h</th>
          </tr>
        </thead>
        <tbody>
          @for (r of rows(); track r.symbol) {
            <tr>
              <th scope="row">
                {{ r.symbol }}
                @if (r.stale || !live.coreAvailable()) {
                  <span class="stale">stale</span>
                }
              </th>
              <td class="num mark">
                @for (v of [r.mark]; track v) {
                  <span [class.flash]="r.changed.mark">{{ v ?? '—' }}</span>
                }
              </td>
              <td class="num bid">
                @for (v of [r.bid]; track v) {
                  <span [class.flash]="r.changed.bid">{{ v ?? '—' }}</span>
                }
              </td>
              <td class="num ask">
                @for (v of [r.ask]; track v) {
                  <span [class.flash]="r.changed.ask">{{ v ?? '—' }}</span>
                }
              </td>
              <td class="num change" [class.up]="change(r) > 0" [class.down]="change(r) < 0">{{ changeText(r) }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .scroll { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { padding: var(--space-1) var(--space-2); text-align: left; white-space: nowrap; }
    thead th { color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--border); }
    tbody th { font-weight: 600; }
    .num { text-align: right; font-family: var(--font-mono); }
    .stale { margin-left: var(--space-1); padding: 0 var(--space-1); border: 1px solid var(--amber);
      border-radius: var(--radius); color: var(--amber); font-size: 11px; font-weight: 700; }
    .up { color: var(--ok); }
    .down { color: var(--danger); }
    .flash { animation: flash 600ms ease-out; }
    @keyframes flash { from { background: var(--accent); color: var(--accent-contrast); } to { background: transparent; } }
    @media (prefers-reduced-motion: reduce) { .flash { animation: none; } }
  `,
})
export class WatchlistComponent implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly live = inject(LiveService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly rows = signal<readonly Row[]>([]);
  /** Close of the candle about 24 h ago, per symbol. */
  private readonly references = signal<ReadonlyMap<string, number>>(new Map());
  protected readonly error = signal<PanelError | null>(null);
  /** A failed REST call, or core reported down on the live socket. */
  protected readonly notice = computed<PanelError | null>(() => this.error() ?? (this.live.coreAvailable() ? null : 'core'));

  constructor() {
    this.live.messages$.pipe(takeUntilDestroyed()).subscribe((m) => {
      if (m.type === 'quote') {
        this.applyQuotes(m.data);
      }
    });
    inject(DestroyRef).onDestroy(this.live.onReconnect(() => void this.load()));
  }

  ngOnInit(): void {
    void this.load();
    const timer = setInterval(() => void this.loadReferences(), REFERENCE_REFRESH_MS);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  /** Display-only: the one number the UI computes. NaN when there is no mark or reference. */
  protected change(row: Row): number {
    const ref = this.references().get(row.symbol);
    const mark = row.mark === null ? NaN : Number(row.mark);
    if (ref === undefined || ref === 0 || Number.isNaN(mark)) {
      return NaN;
    }
    return ((mark - ref) / ref) * 100;
  }

  protected changeText(row: Row): string {
    const pct = this.change(row);
    if (Number.isNaN(pct)) {
      return '—';
    }
    const text = `${Math.abs(pct).toFixed(2)}%`;
    return pct > 0 ? `▲ ${text}` : pct < 0 ? `▼ ${text}` : text;
  }

  private async load(): Promise<void> {
    try {
      const [instruments, quotes] = await Promise.all([this.api.instruments(), this.api.quotes()]);
      const bySymbol = new Map(quotes.map((q) => [q.symbol, q]));
      this.rows.set(instruments.map((i) => rowOf(i.symbol, bySymbol.get(i.symbol))));
      this.error.set(null);
      void this.loadReferences();
    } catch (err) {
      this.error.set(panelError(err));
    }
  }

  private async loadReferences(): Promise<void> {
    const now = Date.now();
    const from = new Date(now - DAY_MS - REFERENCE_WINDOW_BEFORE_MS);
    const to = new Date(now - DAY_MS + REFERENCE_WINDOW_AFTER_MS);
    const next = new Map(this.references());
    await Promise.all(
      this.rows().map(async ({ symbol }) => {
        try {
          const candles = await this.api.candles(symbol, from, to);
          const close = candles.at(-1)?.close;
          if (close !== undefined) {
            next.set(symbol, Number(close));
          }
        } catch {
          // The change column stays on its last value (or a dash); the next refresh retries.
        }
      }),
    );
    this.references.set(next);
  }

  private applyQuotes(quotes: readonly Quote[]): void {
    const incoming = new Map(quotes.map((q) => [q.symbol, q]));
    this.rows.update((rows) =>
      rows.map((row) => {
        const q = incoming.get(row.symbol);
        return q ? rowOf(row.symbol, q, row) : row;
      }),
    );
  }
}

function rowOf(symbol: string, q: Quote | undefined, previous?: Row): Row {
  const next = {
    symbol,
    mark: q?.markPrice ?? null,
    bid: q?.bestBid ?? null,
    ask: q?.bestAsk ?? null,
    stale: q?.stale ?? false,
  };
  const changed = previous
    ? { mark: next.mark !== previous.mark, bid: next.bid !== previous.bid, ask: next.ask !== previous.ask }
    : NOT_CHANGED;
  return { ...next, changed };
}

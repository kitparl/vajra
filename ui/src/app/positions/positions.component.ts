import { ChangeDetectionStrategy, Component, DestroyRef, computed, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService, panelError } from '../data/api.service';
import { blankToNull, isOlder } from '../data/merge';
import { createThrottle } from '../data/throttle';
import { LiveService } from '../data/live.service';
import { PanelError, Position, PositionUpdate } from '../data/models';
import { PanelNoticeComponent } from '../data/panel-notice.component';

interface Row extends Position {
  /** Set only for rows touched by a live message; REST rows carry none. */
  updatedAt: string | null;
  /** Sequence number of the live message that last set this row; 0 for rows from a snapshot. */
  liveSeq: number;
}

const REFRESH_MIN_MS = 2000;

const keyOf = (p: { venue: string; symbol: string }): string => `${p.venue}|${p.symbol}`;

@Component({
  selector: 'app-positions',
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
            <th scope="col">Venue</th>
            <th scope="col">Symbol</th>
            <th scope="col" class="num">Size</th>
            <th scope="col" class="num">Avg entry</th>
            <th scope="col" class="num">Mark</th>
            <th scope="col" class="num">Realized</th>
            <th scope="col" class="num">Unrealized</th>
            <th scope="col" class="num">Fees</th>
          </tr>
        </thead>
        <tbody>
          @for (p of rows(); track p.venue + '|' + p.symbol) {
            <tr>
              <td>{{ p.venue }}</td>
              <th scope="row">{{ p.symbol }}</th>
              <td class="num">{{ p.size }}</td>
              <td class="num">{{ p.avgEntryPrice ?? '—' }}</td>
              <td class="num">{{ p.markPrice ?? '—' }}</td>
              <td class="num">{{ p.realizedPnl ?? '—' }}</td>
              <td class="num">{{ p.unrealizedPnl ?? '—' }}</td>
              <td class="num">{{ p.feesPaid ?? '—' }}</td>
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
  `,
})
export class PositionsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly live = inject(LiveService);

  protected readonly rows = signal<readonly Row[]>([]);
  protected readonly error = signal<PanelError | null>(null);
  /** A failed REST call, or core reported down on the live socket. */
  protected readonly notice = computed<PanelError | null>(() => this.error() ?? (this.live.coreAvailable() ? null : 'core'));

  private liveSeq = 0;
  private loadSeq = 0;
  /** Core computes mark and unrealized on every call, so quotes and position events trigger a throttled refetch. */
  private readonly refresh = createThrottle(() => void this.load(), REFRESH_MIN_MS, inject(DestroyRef));

  constructor() {
    this.live.messages$.pipe(takeUntilDestroyed()).subscribe((m) => {
      if (m.type === 'position') {
        this.merge(m.data);
        this.refresh();
      } else if (m.type === 'quote') {
        this.refresh();
      }
    });
    inject(DestroyRef).onDestroy(this.live.onReconnect(() => void this.load()));
  }

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    const started = this.liveSeq;
    const loadId = ++this.loadSeq;
    try {
      const snapshot = (await this.api.positions()).map((p): Row => ({ ...p, updatedAt: null, liveSeq: 0 }));
      if (loadId !== this.loadSeq) {
        return;
      }
      this.rows.update((held) => mergeSnapshot(snapshot, held, started));
      this.error.set(null);
    } catch (err) {
      this.error.set(panelError(err));
    }
  }

  /** Keeps the newer update per venue+symbol (events can arrive out of order); mark and unrealized stay until the next refetch. */
  private merge(u: PositionUpdate): void {
    this.rows.update((rows) => {
      const held = rows.find((r) => keyOf(r) === keyOf(u));
      if (held && isOlder(u.updatedAt, held.updatedAt)) {
        return rows;
      }
      const row: Row = {
        venue: u.venue,
        symbol: u.symbol,
        size: u.size,
        avgEntryPrice: blankToNull(u.avgEntryPrice),
        realizedPnl: blankToNull(u.realizedPnl),
        feesPaid: blankToNull(u.feesPaid),
        markPrice: held?.markPrice ?? null,
        unrealizedPnl: held?.unrealizedPnl ?? null,
        updatedAt: u.updatedAt,
        liveSeq: ++this.liveSeq,
      };
      return held ? rows.map((r) => (r === held ? row : r)) : [...rows, row];
    });
  }
}

/** REST positions carry no timestamp, so a row set by a live message after the load started wins over its snapshot row. */
function mergeSnapshot(snapshot: readonly Row[], held: readonly Row[], loadStartedSeq: number): Row[] {
  const newer = new Map(held.filter((h) => h.liveSeq > loadStartedSeq).map((h) => [keyOf(h), h]));
  const merged = snapshot.map((s) => newer.get(keyOf(s)) ?? s);
  const inSnapshot = new Set(snapshot.map(keyOf));
  return [...merged, ...[...newer.values()].filter((h) => !inSnapshot.has(keyOf(h)))];
}

import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService, panelError } from '../data/api.service';
import { blankToNull, isOlder } from '../data/merge';
import { LiveService } from '../data/live.service';
import { Order, OrderUpdate, PanelError } from '../data/models';
import { PanelNoticeComponent } from '../data/panel-notice.component';
import { OrderDetailComponent } from './order-detail.component';

interface Row {
  id: string;
  symbol: string;
  side: string;
  type: string;
  size: number;
  limitPrice: string | null;
  state: string;
  filledSize: number;
  avgFillPrice: string | null;
  createdAt: string;
  updatedAt: string | null;
  /** Sequence number of the live message that last set this row; 0 for rows from a snapshot. */
  liveSeq: number;
}

const MAX_ROWS = 100;

const fromOrder = (o: Order): Row => ({
  id: o.id,
  symbol: o.symbol,
  side: o.side,
  type: o.type,
  size: o.size,
  limitPrice: o.limitPrice,
  state: o.state,
  filledSize: o.filledSize,
  avgFillPrice: o.avgFillPrice,
  createdAt: o.createdAt,
  updatedAt: o.updatedAt,
  liveSeq: 0,
});

/** A live update does not carry createdAt; an order first seen live is the newest, so its update time stands in. */
const fromUpdate = (u: OrderUpdate, liveSeq: number, createdAt?: string): Row => ({
  id: u.orderId,
  symbol: u.symbol,
  side: u.side,
  type: u.orderType,
  size: u.size,
  limitPrice: blankToNull(u.limitPrice),
  state: u.state,
  filledSize: u.filledSize,
  avgFillPrice: blankToNull(u.avgFillPrice),
  createdAt: createdAt ?? u.updatedAt ?? new Date().toISOString(),
  updatedAt: u.updatedAt,
  liveSeq,
});

const newestFirst = (a: Row, b: Row): number => Date.parse(b.createdAt) - Date.parse(a.createdAt);

@Component({
  selector: 'app-orders',
  imports: [PanelNoticeComponent, OrderDetailComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error(); as e) {
      <app-panel-notice [error]="e" />
    }
    <div class="scroll">
      <table>
        <thead>
          <tr>
            <th scope="col">Symbol</th>
            <th scope="col">Side</th>
            <th scope="col">Type</th>
            <th scope="col" class="num">Size</th>
            <th scope="col" class="num">Price</th>
            <th scope="col">State</th>
            <th scope="col" class="num">Filled</th>
          </tr>
        </thead>
        <tbody>
          @for (o of rows(); track o.id) {
            <tr [attr.data-id]="o.id" [class.selected]="o.id === selected()">
              <th scope="row"><button type="button" class="link" (click)="selected.set(o.id)">{{ o.symbol }}</button></th>
              <td>{{ o.side }}</td>
              <td>{{ o.type }}</td>
              <td class="num">{{ o.size }}</td>
              <td class="num">{{ o.limitPrice ?? o.avgFillPrice ?? '—' }}</td>
              <td class="state">{{ o.state }}</td>
              <td class="num">{{ o.filledSize }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    @for (id of openIds(); track id) {
      <app-order-detail [orderId]="id" (closed)="selected.set(null)" />
    }
  `,
  styles: `
    .scroll { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { padding: var(--space-1) var(--space-2); text-align: left; white-space: nowrap; }
    thead th { color: var(--text-muted); font-weight: 600; border-bottom: 1px solid var(--border); }
    tbody th { font-weight: 600; }
    tr.selected { background: var(--surface-2); }
    .num { text-align: right; font-family: var(--font-mono); }
    button.link { background: none; border: 0; padding: 0; color: var(--accent); font: inherit; font-weight: 600;
      cursor: pointer; text-decoration: underline; }
    app-order-detail { display: block; margin-top: var(--space-3); padding-top: var(--space-2); border-top: 1px solid var(--border); }
  `,
})
export class OrdersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly live = inject(LiveService);

  protected readonly rows = signal<readonly Row[]>([]);
  protected readonly selected = signal<string | null>(null);
  protected readonly error = signal<PanelError | null>(null);
  /** One entry while a detail is open; a new id rebuilds the detail view, which loads its own data. */
  protected readonly openIds = computed(() => {
    const id = this.selected();
    return id === null ? [] : [id];
  });
  private liveSeq = 0;
  private loadSeq = 0;

  constructor() {
    this.live.messages$.pipe(takeUntilDestroyed()).subscribe((m) => {
      if (m.type === 'order') {
        this.merge(m.data);
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
      const snapshot = (await this.api.orders()).map(fromOrder);
      if (loadId !== this.loadSeq) {
        return;
      }
      this.rows.update((held) => mergeSnapshot(snapshot, held, started).sort(newestFirst));
      this.error.set(null);
    } catch (err) {
      this.error.set(panelError(err));
    }
  }

  /** Keeps the newer update per order id (events can arrive out of order). */
  private merge(u: OrderUpdate): void {
    this.rows.update((rows) => {
      const held = rows.find((r) => r.id === u.orderId);
      if (held && isOlder(u.updatedAt, held.updatedAt)) {
        return rows;
      }
      const row = fromUpdate(u, ++this.liveSeq, held?.createdAt);
      const next = held ? rows.map((r) => (r === held ? row : r)) : [...rows, row];
      return next.sort(newestFirst).slice(0, MAX_ROWS);
    });
  }
}

/** A held row wins over its snapshot row when it is newer; live-only rows that arrived during the load are kept. */
function mergeSnapshot(snapshot: readonly Row[], held: readonly Row[], loadStartedSeq: number): Row[] {
  const heldById = new Map(held.map((r) => [r.id, r]));
  const merged = snapshot.map((s) => {
    const h = heldById.get(s.id);
    return h && h.liveSeq > 0 && !isOlder(h.updatedAt, s.updatedAt) ? h : s;
  });
  const inSnapshot = new Set(snapshot.map((r) => r.id));
  return [...merged, ...held.filter((h) => h.liveSeq > loadStartedSeq && !inSnapshot.has(h.id))];
}

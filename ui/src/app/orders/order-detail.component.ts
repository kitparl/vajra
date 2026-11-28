import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService, panelError } from '../data/api.service';
import { LiveService } from '../data/live.service';
import { OrderDetails, PanelError } from '../data/models';
import { PanelNoticeComponent } from '../data/panel-notice.component';

@Component({
  selector: 'app-order-detail',
  imports: [PanelNoticeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="head">
      <h3>Order {{ orderId() }}</h3>
      <button type="button" class="close" (click)="closed.emit()">Close</button>
    </div>
    @if (error(); as e) {
      <app-panel-notice [error]="e" />
    }
    @if (details(); as d) {
      <p class="summary">
        {{ d.order.side }} {{ d.order.type }} {{ d.order.symbol }} · size {{ d.order.size }} · filled {{ d.order.filledSize }}
        · {{ d.order.state }}
        @if (d.order.reason) {
          · {{ d.order.reason }}
        }
      </p>
      <h4>Transitions</h4>
      <table>
        <thead>
          <tr><th scope="col">At</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Reason</th></tr>
        </thead>
        <tbody>
          @for (t of d.transitions; track $index) {
            <tr><th scope="row">{{ t.at }}</th><td>{{ t.from ?? '—' }}</td><td>{{ t.to }}</td><td>{{ t.reason ?? '' }}</td></tr>
          }
        </tbody>
      </table>
      <h4>Fills</h4>
      <table>
        <thead>
          <tr><th scope="col">At</th><th scope="col" class="num">Price</th><th scope="col" class="num">Size</th><th scope="col" class="num">Fee</th><th scope="col">Liquidity</th></tr>
        </thead>
        <tbody>
          @for (f of d.fills; track f.id) {
            <tr>
              <th scope="row">{{ f.at }}</th>
              <td class="num">{{ f.price }}</td>
              <td class="num">{{ f.size }}</td>
              <td class="num">{{ f.fee }}</td>
              <td>{{ f.liquidity ?? '' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="5">No fills</td></tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    .head { display: flex; justify-content: space-between; align-items: center; gap: var(--space-2); }
    h3, h4 { margin: var(--space-2) 0; font-size: 13px; }
    .summary { margin: 0; color: var(--text-muted); }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th, td { padding: var(--space-1) var(--space-2); text-align: left; }
    thead th { color: var(--text-muted); border-bottom: 1px solid var(--border); }
    tbody th { font-weight: 400; }
    .num { text-align: right; font-family: var(--font-mono); }
    button { padding: var(--space-1) var(--space-3); background: var(--surface-2); border: 1px solid var(--border);
      border-radius: var(--radius); cursor: pointer; }
  `,
})
export class OrderDetailComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly live = inject(LiveService);

  readonly orderId = input.required<string>();
  readonly closed = output<void>();

  protected readonly details = signal<OrderDetails | null>(null);
  protected readonly error = signal<PanelError | null>(null);

  constructor() {
    this.live.messages$.pipe(takeUntilDestroyed()).subscribe((m) => {
      if ((m.type === 'fill' && m.data.orderId === this.orderId()) || (m.type === 'order' && m.data.orderId === this.orderId())) {
        void this.load();
      }
    });
    inject(DestroyRef).onDestroy(this.live.onReconnect(() => void this.load()));
  }

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.details.set(await this.api.orderDetails(this.orderId()));
      this.error.set(null);
    } catch (err) {
      this.error.set(panelError(err));
    }
  }
}

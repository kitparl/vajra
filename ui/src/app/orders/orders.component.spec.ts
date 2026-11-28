import { TestBed } from '@angular/core/testing';
import { ApiService } from '../data/api.service';
import { fakeLive, FakeLive } from '../data/fakes.testing';
import { LiveService } from '../data/live.service';
import { Order, OrderDetails, OrderUpdate } from '../data/models';
import { OrdersComponent } from './orders.component';

const order = (id: string, createdAt: string, over: Partial<Order> = {}): Order =>
  ({
    id,
    symbol: 'BTCUSD',
    side: 'BUY',
    type: 'MARKET',
    size: 2,
    limitPrice: null,
    state: 'NEW',
    filledSize: 0,
    avgFillPrice: null,
    createdAt,
    updatedAt: createdAt,
    ...over,
  }) as Order;
const update = (id: string, over: Partial<OrderUpdate> = {}): OrderUpdate =>
  ({
    orderId: id,
    symbol: 'BTCUSD',
    side: 'BUY',
    orderType: 'MARKET',
    size: 2,
    limitPrice: '',
    state: 'FILLED',
    filledSize: 2,
    avgFillPrice: '100.10',
    updatedAt: '2026-11-28T10:00:09Z',
    ...over,
  }) as OrderUpdate;
const details = (id: string): OrderDetails => ({
  order: order(id, '2026-11-28T10:00:00Z', { state: 'FILLED' }),
  riskDecisionId: null,
  signalId: null,
  transitions: [{ from: null, to: 'NEW', reason: null, at: '2026-11-28T10:00:00Z' }, { from: 'NEW', to: 'FILLED', reason: 'done', at: '2026-11-28T10:00:02Z' }],
  fills: [{ id: 'f1', orderId: id, venue: 'DELTA', venueFillId: 'v1', price: '100.10', size: 2, fee: '0.00012345', liquidity: 'taker', at: '2026-11-28T10:00:02Z' }],
});

function setup() {
  const api = {
    orders: vi.fn(async () => [order('a', '2026-11-28T10:00:00Z'), order('b', '2026-11-28T10:00:05Z')]),
    orderDetails: vi.fn(async (id: string) => details(id)),
  };
  const live: FakeLive = fakeLive();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: api },
      { provide: LiveService, useValue: live },
    ],
  });
  const fixture = TestBed.createComponent(OrdersComponent);
  fixture.detectChanges();
  return { fixture, api, live, el: fixture.nativeElement as HTMLElement };
}

const ids = (el: HTMLElement) => Array.from(el.querySelectorAll('tbody tr')).map((r) => r.getAttribute('data-id'));
const stateOf = (el: HTMLElement, id: string) => el.querySelector(`tr[data-id=${id}] td.state`)!.textContent!.trim();

describe('OrdersComponent', () => {
  it('ordersMergeLiveUpdatesById', async () => {
    const { fixture, el, live } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(ids(el)).toEqual(['b', 'a']);
    live.emit({ type: 'order', data: update('a') });
    fixture.detectChanges();
    expect(stateOf(el, 'a')).toBe('FILLED');
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    live.emit({ type: 'order', data: update('c', { state: 'NEW', updatedAt: '2026-11-28T10:00:20Z' }) });
    fixture.detectChanges();
    expect(ids(el)).toEqual(['c', 'b', 'a']);
  });

  it('olderOrderUpdateIsIgnored', async () => {
    const { fixture, el, live } = setup();
    await fixture.whenStable();
    live.emit({ type: 'order', data: update('a', { state: 'FILLED', updatedAt: '2026-11-28T10:00:09Z' }) });
    live.emit({ type: 'order', data: update('a', { state: 'PARTIALLY_FILLED', updatedAt: '2026-11-28T10:00:03Z' }) });
    fixture.detectChanges();
    expect(stateOf(el, 'a')).toBe('FILLED');
  });

  it('clickingRowOpensDetailWithFillsAndTransitions', async () => {
    const { fixture, el, api } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('tr[data-id=a] button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.orderDetails).toHaveBeenCalledWith('a');
    const detail = el.querySelector('app-order-detail')!;
    expect(detail.textContent).toContain('0.00012345');
    expect(detail.textContent).toContain('done');
    expect(detail.querySelectorAll('th[scope=col]').length).toBeGreaterThan(0);
  });

  it('liveFillRefreshesOpenDetail', async () => {
    const { fixture, el, api, live } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('tr[data-id=a] button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    live.emit({ type: 'fill', data: { fillId: 'f2', orderId: 'a', filledAt: null } as never });
    live.emit({ type: 'fill', data: { fillId: 'f3', orderId: 'zzz', filledAt: null } as never });
    expect(api.orderDetails).toHaveBeenCalledTimes(2);
  });

  it('detailCanBeClosed', async () => {
    const { fixture, el } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('tr[data-id=a] button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('button.close')!.click();
    fixture.detectChanges();
    expect(el.querySelector('app-order-detail')).toBeNull();
  });

  it('selectingSecondOrderLoadsItsDetail', async () => {
    const { fixture, el, api } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('tr[data-id=a] button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('tr[data-id=b] button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.orderDetails).toHaveBeenLastCalledWith('b');
    expect(el.querySelector('app-order-detail h3')!.textContent).toContain('b');
  });

  it('snapshotDoesNotOverwriteNewerLiveOrder', async () => {
    const { fixture, el, api, live } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    let resolve!: (o: Order[]) => void;
    api.orders.mockReturnValueOnce(new Promise<Order[]>((r) => (resolve = r)));
    live.reconnect();
    live.emit({ type: 'order', data: update('a', { state: 'FILLED', updatedAt: '2026-11-28T10:00:09Z' }) });
    live.emit({ type: 'order', data: update('n', { state: 'NEW', updatedAt: '2026-11-28T10:00:10Z' }) });
    resolve([order('a', '2026-11-28T10:00:00Z', { updatedAt: '2026-11-28T10:00:00Z' }), order('b', '2026-11-28T10:00:05Z')]);
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r, 0));
    fixture.detectChanges();
    expect(stateOf(el, 'a')).toBe('FILLED');
    expect(ids(el)).toEqual(['n', 'b', 'a']);
  });

  it('reconnectHookRemovedOnDestroy', async () => {
    const { fixture, live } = setup();
    await fixture.whenStable();
    expect(live.hookCount()).toBe(1);
    fixture.destroy();
    expect(live.hookCount()).toBe(0);
  });

  it('detailHookRemovedOnDestroy', async () => {
    const { fixture, el, live } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    el.querySelector<HTMLButtonElement>('tr[data-id=a] button')!.click();
    fixture.detectChanges();
    expect(live.hookCount()).toBe(2);
    el.querySelector<HTMLButtonElement>('button.close')!.click();
    fixture.detectChanges();
    expect(live.hookCount()).toBe(1);
  });

  it('ordersRefetchedOnFirstOpen', async () => {
    const { fixture, el, api, live } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(stateOf(el, 'a')).toBe('NEW');
    api.orders.mockResolvedValueOnce([
      order('a', '2026-11-28T10:00:00Z', { state: 'FILLED', updatedAt: '2026-11-28T10:00:07Z' }),
      order('b', '2026-11-28T10:00:05Z'),
    ]);
    live.reconnect(); // the hook the live service now fires on the first open too
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r, 0));
    fixture.detectChanges();
    expect(stateOf(el, 'a')).toBe('FILLED');
  });
});

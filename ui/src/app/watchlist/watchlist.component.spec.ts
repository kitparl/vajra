import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from '../data/api.service';
import { fakeLive, FakeLive } from '../data/fakes.testing';
import { Candle, Instrument, Quote } from '../data/models';
import { LiveService } from '../data/live.service';
import { WatchlistComponent } from './watchlist.component';

const inst = (symbol: string) => ({ symbol, venue: 'DELTA', tradingEnabled: true }) as Instrument;
const quote = (symbol: string, mark: string, extra: Partial<Quote> = {}): Quote => ({
  symbol,
  bestBid: '99.5',
  bestAsk: '100.5',
  markPrice: mark,
  venueTime: null,
  receivedAt: '2026-11-28T10:00:00Z',
  stale: false,
  ...extra,
});
const candle = (close: string) => [{ symbol: 'BTCUSD', openTime: 't', close }] as Candle[];

function setup(over: Partial<Record<'instruments' | 'quotes' | 'candles', () => Promise<unknown>>> = {}) {
  const api = {
    instruments: vi.fn(over.instruments ?? (async () => [inst('BTCUSD')])),
    quotes: vi.fn(over.quotes ?? (async () => [quote('BTCUSD', '110')])),
    candles: vi.fn(over.candles ?? (async () => candle('100'))),
  };
  const live: FakeLive = fakeLive();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: api },
      { provide: LiveService, useValue: live },
    ],
  });
  const fixture = TestBed.createComponent(WatchlistComponent);
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return { fixture, api, live, el };
}

async function settle(fixture: { whenStable(): Promise<unknown>; detectChanges(): void }) {
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

describe('WatchlistComponent', () => {
  it('quoteMessageUpdatesRowAndFlashes', async () => {
    const { fixture, live, el } = setup();
    await settle(fixture);
    expect(el.querySelector('td.mark')!.textContent).toContain('110');
    expect(el.querySelector('td.mark .flash')).toBeNull();
    live.emit({ type: 'quote', data: [quote('BTCUSD', '111.25')] });
    fixture.detectChanges();
    expect(el.querySelector('td.mark')!.textContent).toContain('111.25');
    expect(el.querySelector('td.mark .flash')).not.toBeNull();
    expect(el.querySelector('td.bid .flash')).toBeNull();
  });

  it('staleQuoteShowsMarker', async () => {
    const { fixture, el, live } = setup();
    await settle(fixture);
    expect(el.textContent).not.toContain('stale');
    live.emit({ type: 'quote', data: [quote('BTCUSD', '110', { stale: true })] });
    fixture.detectChanges();
    expect(el.querySelector('.stale')!.textContent!.trim()).toBe('stale');
  });

  it('changeShowsArrowNotOnlyColor', async () => {
    const { fixture, el, live } = setup();
    await settle(fixture);
    const change = () => el.querySelector('td.change')!.textContent!.trim();
    expect(change()).toBe('▲ 10.00%');
    live.emit({ type: 'quote', data: [quote('BTCUSD', '90')] });
    fixture.detectChanges();
    expect(change()).toBe('▼ 10.00%');
  });

  it('changeIsDashWithoutReferenceCandle', async () => {
    const { fixture, el } = setup({ candles: async () => [] });
    await settle(fixture);
    expect(el.querySelector('td.change')!.textContent!.trim()).toBe('—');
  });

  it('refetchesSnapshotOnReconnect', async () => {
    const { fixture, api, live } = setup();
    await settle(fixture);
    live.reconnect();
    expect(api.instruments).toHaveBeenCalledTimes(2);
    expect(api.quotes).toHaveBeenCalledTimes(2);
  });

  it('upstreamUnavailableShownInline', async () => {
    const err = new HttpErrorResponse({ status: 503, error: { error: 'UPSTREAM_UNAVAILABLE', upstream: 'core' } });
    const { fixture, el } = setup({ instruments: async () => Promise.reject(err) });
    await settle(fixture);
    expect(el.querySelector('[role=alert]')!.textContent).toContain('core unavailable');
  });

  it('genericErrorShownInline', async () => {
    const { fixture, el } = setup({ quotes: async () => Promise.reject(new HttpErrorResponse({ status: 500 })) });
    await settle(fixture);
    expect(el.querySelector('[role=alert]')!.textContent).toContain('Could not load');
  });

  it('tableHasScopedHeaders', async () => {
    const { fixture, el } = setup();
    await settle(fixture);
    const ths = Array.from(el.querySelectorAll('thead th'));
    expect(ths.length).toBe(5);
    expect(ths.every((th) => th.getAttribute('scope') === 'col')).toBe(true);
    expect(el.querySelector('tbody th')!.getAttribute('scope')).toBe('row');
  });

  it('reconnectHookRemovedOnDestroy', async () => {
    const { fixture, live } = setup();
    await settle(fixture);
    expect(live.hookCount()).toBe(1);
    fixture.destroy();
    expect(live.hookCount()).toBe(0);
  });

  it('upstreamDownMarksRowsStaleAndShowsNotice', async () => {
    const { fixture, live, el } = setup();
    await settle(fixture);
    expect(el.querySelector('.stale')).toBeNull();
    expect(el.querySelector('[role=alert]')).toBeNull();

    live.coreAvailable.set(false);
    fixture.detectChanges();
    expect(el.querySelector('.stale')).not.toBeNull();
    expect(el.querySelector('[role=alert]')!.textContent).toContain('core unavailable');

    live.coreAvailable.set(true);
    fixture.detectChanges();
    expect(el.querySelector('.stale')).toBeNull();
    expect(el.querySelector('[role=alert]')).toBeNull();
  });
});

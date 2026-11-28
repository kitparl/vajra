import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from '../data/api.service';
import { fakeLive, FakeLive } from '../data/fakes.testing';
import { LiveService } from '../data/live.service';
import { Position, PositionUpdate } from '../data/models';
import { PositionsComponent } from './positions.component';

const pos = (over: Partial<Position> = {}): Position => ({
  venue: 'DELTA',
  symbol: 'BTCUSD',
  size: 3,
  avgEntryPrice: '100.10',
  realizedPnl: '0.00012345',
  feesPaid: '0.5',
  markPrice: '101.00',
  unrealizedPnl: '2.7',
  ...over,
});
const update = (over: Partial<PositionUpdate> = {}): PositionUpdate => ({
  venue: 'DELTA',
  symbol: 'BTCUSD',
  size: 5,
  avgEntryPrice: '100.20',
  realizedPnl: '0.00012345',
  feesPaid: '0.6',
  updatedAt: '2026-11-28T10:00:05Z',
  ...over,
});

function setup(positions: () => Promise<Position[]> = async () => [pos()]) {
  const api = { positions: vi.fn(positions) };
  const live: FakeLive = fakeLive();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiService, useValue: api },
      { provide: LiveService, useValue: live },
    ],
  });
  const fixture = TestBed.createComponent(PositionsComponent);
  fixture.detectChanges();
  return { fixture, api, live, el: fixture.nativeElement as HTMLElement };
}

const cells = (el: HTMLElement) => Array.from(el.querySelectorAll('tbody tr:first-child td, tbody tr:first-child th')).map((c) => c.textContent!.trim());

describe('PositionsComponent', () => {
  it('positionsShowStringsVerbatim', async () => {
    const { fixture, el } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(cells(el)).toEqual(['DELTA', 'BTCUSD', '3', '100.10', '101.00', '0.00012345', '2.7', '0.5']);
  });

  it('livePositionMergesByVenueAndSymbolKeepingMark', async () => {
    const { fixture, el, live } = setup(async () => [pos(), pos({ symbol: 'ETHUSD' })]);
    await fixture.whenStable();
    live.emit({ type: 'position', data: update() });
    fixture.detectChanges();
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    expect(cells(el).slice(2, 5)).toEqual(['5', '100.20', '101.00']);
  });

  it('newSymbolFromLiveIsAdded', async () => {
    const { fixture, el, live } = setup();
    await fixture.whenStable();
    live.emit({ type: 'position', data: update({ symbol: 'SOLUSD' }) });
    fixture.detectChanges();
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
  });

  it('olderPositionUpdateIsIgnored', async () => {
    const { fixture, el, live } = setup();
    await fixture.whenStable();
    live.emit({ type: 'position', data: update({ size: 5 }) });
    live.emit({ type: 'position', data: update({ size: 4, updatedAt: '2026-11-28T10:00:01Z' }) });
    fixture.detectChanges();
    expect(cells(el)[2]).toBe('5');
  });

  it('upstreamUnavailableShownInline', async () => {
    const err = new HttpErrorResponse({ status: 503, error: { error: 'UPSTREAM_UNAVAILABLE', upstream: 'core' } });
    const { fixture, el } = setup(async () => Promise.reject(err));
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')!.textContent).toContain('core unavailable');
  });

  it('refetchesOnReconnect', async () => {
    const { fixture, api, live } = setup();
    await fixture.whenStable();
    live.reconnect();
    expect(api.positions).toHaveBeenCalledTimes(2);
  });

  it('snapshotDoesNotOverwriteNewerLivePosition', async () => {
    const { fixture, el, api, live } = setup();
    await fixture.whenStable();
    fixture.detectChanges();
    live.emit({ type: 'quote', data: [] });
    await fixture.whenStable();
    let resolve!: (p: Position[]) => void;
    api.positions.mockReturnValueOnce(new Promise<Position[]>((r) => (resolve = r)));
    live.reconnect();
    live.emit({ type: 'position', data: update({ size: 5 }) });
    resolve([pos({ size: 3 })]);
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r, 0));
    fixture.detectChanges();
    expect(cells(el)[2]).toBe('5');
  });

  it('positionsRefetchOnQuoteThrottled', async () => {
    vi.useFakeTimers();
    try {
      const { api, live } = setup();
      await vi.advanceTimersByTimeAsync(3000);
      expect(api.positions).toHaveBeenCalledTimes(1);
      live.emit({ type: 'quote', data: [] });
      expect(api.positions).toHaveBeenCalledTimes(2);
      live.emit({ type: 'quote', data: [] });
      live.emit({ type: 'position', data: update() });
      await vi.advanceTimersByTimeAsync(1999);
      expect(api.positions).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(1);
      expect(api.positions).toHaveBeenCalledTimes(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it('reconnectHookRemovedOnDestroy', async () => {
    const { fixture, live } = setup();
    await fixture.whenStable();
    expect(live.hookCount()).toBe(1);
    fixture.destroy();
    expect(live.hookCount()).toBe(0);
  });

  it('supersededSnapshotIsDropped', async () => {
    const { fixture, el, api, live } = setup();
    await fixture.whenStable();
    let resolveOld!: (p: Position[]) => void;
    api.positions.mockReturnValueOnce(new Promise<Position[]>((r) => (resolveOld = r)));
    live.reconnect();
    api.positions.mockResolvedValueOnce([pos({ size: 7 })]);
    live.reconnect();
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r, 0));
    resolveOld([pos({ size: 1 })]);
    await new Promise((r) => setTimeout(r, 0));
    fixture.detectChanges();
    expect(cells(el)[2]).toBe('7');
  });

  it('upstreamDownShowsCoreNotice', async () => {
    const { fixture, live, el } = setup();
    await fixture.whenStable();
    live.coreAvailable.set(false);
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')!.textContent).toContain('core unavailable');
    live.coreAvailable.set(true);
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('flatPositionLiveUpdateShowsDash', async () => {
    const { fixture, live, el } = setup();
    await fixture.whenStable();
    live.emit({ type: 'position', data: update({ size: 0, avgEntryPrice: '', realizedPnl: '', feesPaid: '' }) });
    fixture.detectChanges();
    expect(cells(el).slice(2, 4)).toEqual(['0', '—']);
    expect(cells(el)[5]).toBe('—');
    expect(cells(el)[7]).toBe('—');
  });
});

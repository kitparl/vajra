import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { LiveMessage } from './models';
import { LiveService } from './live.service';

class MockSocket {
  static instances: MockSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onclose: ((e: { code: number }) => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  constructor(readonly url: string) {
    MockSocket.instances.push(this);
  }
  close(): void {
    this.closed = true;
  }
  open(): void {
    this.onopen?.();
  }
  receive(msg: unknown): void {
    this.onmessage?.({ data: JSON.stringify(msg) });
  }
  drop(code = 1006): void {
    this.onclose?.({ code });
  }
}

describe('LiveService', () => {
  const auth = { loadSession: vi.fn(async () => undefined) };
  let nav: ReturnType<typeof vi.fn>;

  function setup(): LiveService {
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: { navigateByUrl: (nav = vi.fn(async () => true)) } },
      ],
    });
    return TestBed.inject(LiveService);
  }

  beforeEach(() => {
    vi.useFakeTimers();
    MockSocket.instances = [];
    vi.stubGlobal('WebSocket', MockSocket);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('connectsToSameHostWs', () => {
    const live = setup();
    live.connect();
    expect(MockSocket.instances[0].url).toBe(`ws://${location.host}/ws`);
    expect(live.status()).toBe('connecting');
    MockSocket.instances[0].open();
    expect(live.status()).toBe('live');
    live.disconnect();
  });

  it('reconnectsWithBackoffAndRefetchesSnapshot', async () => {
    const live = setup();
    const refetch = vi.fn();
    live.onReconnect(refetch);
    live.connect();
    MockSocket.instances[0].open();
    expect(refetch).toHaveBeenCalledTimes(1);

    for (const [i, delay] of [1000, 2000, 5000, 10_000, 10_000].entries()) {
      MockSocket.instances[i].drop();
      expect(live.status()).toBe('reconnecting');
      await vi.advanceTimersByTimeAsync(delay - 1);
      expect(MockSocket.instances.length).toBe(i + 1);
      await vi.advanceTimersByTimeAsync(1);
      expect(MockSocket.instances.length).toBe(i + 2);
    }
    MockSocket.instances[5].open();
    expect(live.status()).toBe('live');
    expect(refetch).toHaveBeenCalledTimes(2);

    MockSocket.instances[5].drop();
    await vi.advanceTimersByTimeAsync(1000);
    expect(MockSocket.instances.length).toBe(7);
    live.disconnect();
  });

  it('hooksFireOnFirstOpen', () => {
    const live = setup();
    const refetch = vi.fn();
    live.onReconnect(refetch);
    live.connect();
    expect(refetch).not.toHaveBeenCalled();
    MockSocket.instances[0].open();
    expect(refetch).toHaveBeenCalledTimes(1);
    live.disconnect();
  });

  it('unregisteredHookIsNotCalled', async () => {
    const live = setup();
    const refetch = vi.fn();
    const off = live.onReconnect(refetch);
    off();
    live.connect();
    MockSocket.instances[0].drop();
    await vi.advanceTimersByTimeAsync(1000);
    MockSocket.instances[1].open();
    expect(refetch).not.toHaveBeenCalled();
    live.disconnect();
  });

  it('closeCode4401ResetsModeRiskAndStatus', async () => {
    const live = setup();
    live.connect();
    const ws = MockSocket.instances[0];
    ws.open();
    ws.receive({ type: 'hello', data: { tradingMode: 'PAPER' } });
    ws.receive({ type: 'risk', data: { halted: true, events: [] } });
    ws.drop(4401);
    await vi.advanceTimersByTimeAsync(0);
    expect(live.tradingMode()).toBeNull();
    expect(live.risk()).toBeNull();
    expect(live.status()).toBe('connecting');
  });

  it('disconnectResetsState', () => {
    const live = setup();
    live.connect();
    const ws = MockSocket.instances[0];
    ws.open();
    ws.receive({ type: 'hello', data: { tradingMode: 'PAPER' } });
    ws.receive({ type: 'risk', data: { halted: true, events: [] } });
    live.disconnect();
    expect(live.risk()).toBeNull();
    expect(live.status()).toBe('connecting');
  });

  it('closeCode4401NavigatesToLogin', async () => {
    const live = setup();
    live.connect();
    MockSocket.instances[0].open();
    MockSocket.instances[0].drop(4401);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(auth.loadSession).toHaveBeenCalled();
    expect(nav).toHaveBeenCalledWith('/login');
    expect(MockSocket.instances.length).toBe(1);
  });

  it('publishesTypedMessagesAndTracksHelloAndRisk', () => {
    const live = setup();
    const seen: LiveMessage[] = [];
    live.messages$.subscribe((m) => seen.push(m));
    live.connect();
    const ws = MockSocket.instances[0];
    ws.open();
    ws.receive({ type: 'hello', data: { tradingMode: 'UNKNOWN' } });
    ws.receive({ type: 'risk', data: { halted: true, events: [] } });
    ws.receive({ type: 'bogus', data: 1 });
    ws.onmessage?.({ data: 'not json' });
    expect(seen.map((m) => m.type)).toEqual(['hello', 'risk']);
    expect(live.tradingMode()).toBe('UNKNOWN');
    expect(live.risk()?.halted).toBe(true);
    live.disconnect();
  });

  it('upstreamMessagesSetTypedSignalsAndQuoteClearsCore', () => {
    const live = setup();
    live.connect();
    const ws = MockSocket.instances[0];
    ws.open();
    expect(live.coreAvailable()).toBe(true);
    ws.receive({ type: 'upstream', data: { upstream: 'core', available: false } });
    ws.receive({ type: 'upstream', data: { upstream: 'risk', available: false } });
    expect(live.coreAvailable()).toBe(false);
    expect(live.riskAvailable()).toBe(false);
    ws.receive({ type: 'quote', data: [] });
    expect(live.coreAvailable()).toBe(true);
    ws.receive({ type: 'upstream', data: { upstream: 'risk', available: true } });
    expect(live.riskAvailable()).toBe(true);
    live.disconnect();
  });

  it('watchdogReconnectsAfterSilence', async () => {
    const live = setup();
    live.connect();
    const ws = MockSocket.instances[0];
    ws.open();
    await vi.advanceTimersByTimeAsync(9000);
    ws.receive({ type: 'risk', data: { halted: false, events: [] } });
    await vi.advanceTimersByTimeAsync(9999);
    expect(live.status()).toBe('live');
    await vi.advanceTimersByTimeAsync(1);
    expect(ws.closed).toBe(true);
    expect(live.status()).toBe('reconnecting');
    await vi.advanceTimersByTimeAsync(1000);
    expect(MockSocket.instances.length).toBe(2);
    live.disconnect();
  });

  it('watchdogClearedOnClose', async () => {
    const live = setup();
    live.connect();
    const ws = MockSocket.instances[0];
    ws.open();
    ws.drop();
    expect(vi.getTimerCount()).toBe(1); // only the reconnect timer
    await vi.advanceTimersByTimeAsync(1000);
    live.disconnect();
    expect(vi.getTimerCount()).toBe(0);
    MockSocket.instances[1].open();
    live.disconnect();
    expect(vi.getTimerCount()).toBe(0);
  });
});

import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { Session } from '../core/session';
import { ApiService } from '../data/api.service';
import { fakeLive } from '../data/fakes.testing';
import { LiveService } from '../data/live.service';
import { PnlSummary } from '../data/models';
import { TopBarComponent } from './top-bar.component';

function setup(session: Session | null, reachable = true, loadSession: () => Promise<void> = async () => undefined) {
  const state = signal<Session | null>(session);
  const auth = {
    session: state,
    reachable: signal(reachable),
    loadSession: vi.fn(loadSession),
    logout: vi.fn<() => Promise<void>>(async () => state.set(null)),
  };
  const live = fakeLive();
  const api = { pnl: vi.fn(async (): Promise<PnlSummary> => ({ realizedPnl: '1', feesPaid: '0.1', unrealizedPnl: '2', netPnl: '2.90000001' })) };
  TestBed.configureTestingModule({
    imports: [TopBarComponent],
    providers: [
      provideRouter([]),
      { provide: AuthService, useValue: auth },
      { provide: LiveService, useValue: live },
      { provide: ApiService, useValue: api },
    ],
  });
  const fixture = TestBed.createComponent(TopBarComponent);
  fixture.detectChanges();
  return { fixture, auth, state, live, api, el: fixture.nativeElement as HTMLElement };
}

describe('TopBarComponent', () => {
  afterEach(() => vi.useRealTimers());

  it('modeBadgeShowsTextForTestnet', () => {
    const { el } = setup({ username: 'a', tradingMode: 'DELTA_TESTNET' });
    expect(el.querySelector('.mode')!.textContent!.trim()).toBe('TESTNET');
  });

  it('unknownModeShownRawWithDangerStyle', () => {
    const { el } = setup({ username: 'a', tradingMode: 'LIVE_X' });
    const badge = el.querySelector('.mode')!;
    expect(badge.textContent!.trim()).toBe('LIVE_X');
    expect(badge.classList.contains('danger')).toBe(true);
  });

  it('skipsPollWhileOneIsInFlight', async () => {
    vi.useFakeTimers();
    const { auth } = setup({ username: 'a', tradingMode: 'PAPER' }, true, () => new Promise<void>(() => undefined));
    await vi.advanceTimersByTimeAsync(45_000);
    expect(auth.loadSession).toHaveBeenCalledTimes(1);
  });

  it('logoutFailureStaysOnPageWithMessage', async () => {
    const { el, auth, fixture } = setup({ username: 'a', tradingMode: 'PAPER' });
    auth.logout.mockRejectedValue(new Error('offline'));
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    el.querySelector<HTMLButtonElement>('button.logout')!.click();
    await fixture.whenStable();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(el.querySelector('[role=alert]')!.textContent).toContain('Logout failed (offline)');
    });
    expect(nav).not.toHaveBeenCalled();
  });

  it('modeBadgeShowsPaper', () => {
    const { el } = setup({ username: 'a', tradingMode: 'PAPER' });
    expect(el.querySelector('.mode')!.textContent!.trim()).toBe('PAPER');
  });

  it('killButtonDisabled', () => {
    const { el } = setup({ username: 'a', tradingMode: 'PAPER' });
    const kill = el.querySelector<HTMLButtonElement>('button.kill')!;
    expect(kill.getAttribute('aria-disabled')).toBe('true');
    const hint = el.querySelector('#kill-hint')!;
    expect(kill.getAttribute('aria-describedby')).toBe('kill-hint');
    expect(hint.textContent).toContain('slice 4');
    kill.click();
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('showsOfflineWhenGatewayUnreachable', () => {
    const { el } = setup({ username: 'a', tradingMode: 'PAPER' }, false);
    expect(el.querySelector('.conn')!.textContent).toContain('offline');
  });

  it('pollsSessionEveryFifteenSecondsAndRedirectsOnSessionEnd', async () => {
    vi.useFakeTimers();
    const { auth, state } = setup({ username: 'a', tradingMode: 'PAPER' });
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    await vi.advanceTimersByTimeAsync(15_000);
    expect(auth.loadSession).toHaveBeenCalledTimes(1);
    expect(nav).not.toHaveBeenCalled();
    state.set(null);
    await vi.advanceTimersByTimeAsync(15_000);
    expect(nav).toHaveBeenCalledWith('/login');
  });

  it('logoutNavigatesToLogin', async () => {
    const { el, auth } = setup({ username: 'a', tradingMode: 'PAPER' });
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    el.querySelector<HTMLButtonElement>('button.logout')!.click();
    await vi.waitFor(() => expect(nav).toHaveBeenCalledWith('/login'));
    expect(auth.logout).toHaveBeenCalled();
  });

  it('haltedBadgeFromRiskMessage', () => {
    const { el, live, fixture } = setup({ username: 'a', tradingMode: 'PAPER' });
    expect(el.querySelector('.halted')).toBeNull();
    live.risk.set({ halted: true, events: [] });
    fixture.detectChanges();
    expect(el.querySelector('.halted')!.textContent!.trim()).toBe('HALTED');
    live.risk.set({ halted: false, events: [] });
    fixture.detectChanges();
    expect(el.querySelector('.halted')).toBeNull();
  });

  it('showsNetPnlVerbatim', async () => {
    const { el, fixture } = setup({ username: 'a', tradingMode: 'PAPER' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('.pnl')!.textContent).toContain('2.90000001');
  });

  it('pnlRefetchThrottled', async () => {
    vi.useFakeTimers();
    const { api, live } = setup({ username: 'a', tradingMode: 'PAPER' });
    expect(api.pnl).toHaveBeenCalledTimes(1);
    const pos = { type: 'position', data: {} } as never;
    await vi.advanceTimersByTimeAsync(3000);
    live.emit(pos);
    expect(api.pnl).toHaveBeenCalledTimes(2);
    live.emit(pos);
    live.emit(pos);
    await vi.advanceTimersByTimeAsync(1999);
    expect(api.pnl).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(api.pnl).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.pnl).toHaveBeenCalledTimes(3);
  });

  it('pnlRefreshesOnQuotesThrottled', async () => {
    vi.useFakeTimers();
    const { api, live } = setup({ username: 'a', tradingMode: 'PAPER' });
    await vi.advanceTimersByTimeAsync(3000);
    const quote = { type: 'quote', data: [] } as never;
    live.emit(quote);
    expect(api.pnl).toHaveBeenCalledTimes(2);
    live.emit(quote);
    live.emit(quote);
    await vi.advanceTimersByTimeAsync(1999);
    expect(api.pnl).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(api.pnl).toHaveBeenCalledTimes(3);
  });

  it('pnlUpstreamUnavailableShownInSlot', async () => {
    vi.useFakeTimers();
    const { el, api, fixture, live } = setup({ username: 'a', tradingMode: 'PAPER' });
    api.pnl.mockRejectedValue(new HttpErrorResponse({ status: 503, error: { upstream: 'core' } }));
    await vi.advanceTimersByTimeAsync(2000);
    live.emit({ type: 'position', data: {} } as never);
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    expect(el.querySelector('.pnl')!.textContent).toContain('core unavailable');
  });

  it('reconnectHookRemovedOnDestroy', () => {
    const { fixture, live } = setup({ username: 'a', tradingMode: 'PAPER' });
    expect(live.hookCount()).toBe(1);
    fixture.destroy();
    expect(live.hookCount()).toBe(0);
  });

  it('sessionUnknownOverridesHelloMode', () => {
    const { el, live, state, fixture } = setup({ username: 'a', tradingMode: 'PAPER' });
    live.tradingMode.set('PAPER');
    state.set({ username: 'a', tradingMode: 'UNKNOWN' });
    fixture.detectChanges();
    const badge = el.querySelector('.mode')!;
    expect(badge.textContent!.trim()).toBe('UNKNOWN');
    expect(badge.classList.contains('danger')).toBe(true);
  });

  it('sessionModeChangeShownWhileSocketOpen', () => {
    const { el, live, state, fixture } = setup({ username: 'a', tradingMode: 'PAPER' });
    live.tradingMode.set('PAPER');
    state.set({ username: 'a', tradingMode: 'DELTA_TESTNET' });
    fixture.detectChanges();
    expect(el.querySelector('.mode')!.textContent!.trim()).toBe('TESTNET');
  });

  it('helloModeUsedUntilSessionExists', () => {
    const { el, live, fixture } = setup(null);
    live.tradingMode.set('PAPER');
    fixture.detectChanges();
    expect(el.querySelector('.mode')!.textContent!.trim()).toBe('PAPER');
  });

  it('coreUnavailableShowsUnknownBadgeAndPnlNotice', () => {
    const { el, live, fixture } = setup({ username: 'a', tradingMode: 'PAPER' });
    live.coreAvailable.set(false);
    fixture.detectChanges();
    const badge = el.querySelector('.mode')!;
    expect(badge.textContent!.trim()).toBe('UNKNOWN');
    expect(badge.classList.contains('danger')).toBe(true);
    expect(el.querySelector('.pnl')!.textContent).toContain('core unavailable');
    live.coreAvailable.set(true);
    fixture.detectChanges();
    expect(el.querySelector('.mode')!.textContent!.trim()).toBe('PAPER');
  });
});

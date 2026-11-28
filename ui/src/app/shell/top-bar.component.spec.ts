import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { Session } from '../core/session';
import { TopBarComponent } from './top-bar.component';

function setup(session: Session | null, reachable = true, loadSession: () => Promise<void> = async () => undefined) {
  const state = signal<Session | null>(session);
  const auth = {
    session: state,
    reachable: signal(reachable),
    loadSession: vi.fn(loadSession),
    logout: vi.fn<() => Promise<void>>(async () => state.set(null)),
  };
  TestBed.configureTestingModule({
    imports: [TopBarComponent],
    providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
  });
  const fixture = TestBed.createComponent(TopBarComponent);
  fixture.detectChanges();
  return { fixture, auth, state, el: fixture.nativeElement as HTMLElement };
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
});

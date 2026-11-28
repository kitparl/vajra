import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { AuthService } from './auth.service';
import { authGuard, guestGuard } from './auth.guard';
import { Session } from './session';

function run(session: Session | null, afterLoad: Session | null = session, guard: typeof authGuard = authGuard) {
  const state = signal<Session | null>(session);
  const loadSession = vi.fn(async () => state.set(afterLoad));
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: AuthService, useValue: { session: state, loadSession } }],
  });
  const result = TestBed.runInInjectionContext(() =>
    guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
  );
  return { result: result as Promise<boolean | UrlTree>, loadSession };
}

describe('authGuard', () => {
  it('redirectsToLoginWhenNoSession', async () => {
    const { result } = run(null);
    const out = await result;
    expect(out instanceof UrlTree).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(out as UrlTree)).toBe('/login');
  });

  it('allowsWhenSessionLoads', async () => {
    const { result } = run(null, { username: 'a', tradingMode: 'PAPER' });
    expect(await result).toBe(true);
  });

  it('allowsWithoutReloadWhenSessionPresent', async () => {
    const { result, loadSession } = run({ username: 'a', tradingMode: 'PAPER' });
    expect(await result).toBe(true);
    expect(loadSession).not.toHaveBeenCalled();
  });
});

describe('guestGuard', () => {
  it('redirectsAuthenticatedUserToRoot', async () => {
    const { result } = run({ username: 'a', tradingMode: 'PAPER' }, undefined, guestGuard);
    const out = await result;
    expect(out instanceof UrlTree).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(out as UrlTree)).toBe('/');
  });

  it('allowsLoginWhenNoSession', async () => {
    const { result } = run(null, null, guestGuard);
    expect(await result).toBe(true);
  });
});

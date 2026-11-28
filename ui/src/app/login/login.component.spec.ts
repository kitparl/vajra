import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { signal } from '@angular/core';
import { LoginResult, Session } from '../core/session';
import { LoginComponent } from './login.component';

function setup(login: (u: string, p: string) => Promise<LoginResult>) {
  const session = signal<Session | null>(null);
  const auth = {
    session,
    login: vi.fn(login),
    loadSession: vi.fn(async () => session.set({ username: 'pranshu', tradingMode: 'PAPER' })),
  };
  TestBed.configureTestingModule({
    imports: [LoginComponent],
    providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
  });
  const fixture = TestBed.createComponent(LoginComponent);
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const user = el.querySelector<HTMLInputElement>('input[name=username]')!;
  const pass = el.querySelector<HTMLInputElement>('input[name=password]')!;
  const submit = el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const fill = () => {
    user.value = 'pranshu';
    user.dispatchEvent(new Event('input'));
    pass.value = 'secret';
    pass.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  const send = () => el.querySelector('form')!.dispatchEvent(new Event('submit'));
  return { fixture, el, auth, session, user, pass, submit, fill, send };
}

describe('LoginComponent', () => {
  it('submitDisabledWhileRequestInFlight', async () => {
    let release!: (r: LoginResult) => void;
    const t = setup(() => new Promise<LoginResult>((r) => (release = r)));
    t.fill();
    t.send();
    t.fixture.detectChanges();
    expect(t.submit.disabled).toBe(true);
    t.send();
    expect(t.auth.login).toHaveBeenCalledTimes(1);
    release('bad-credentials');
    await t.fixture.whenStable();
    t.fixture.detectChanges();
    expect(t.submit.disabled).toBe(false);
  });

  it('showsLockedMessageWithMinutes', async () => {
    const t = setup(async () => ({ locked: 125 }));
    t.fill();
    t.send();
    await t.fixture.whenStable();
    t.fixture.detectChanges();
    expect(t.el.querySelector('[role=alert]')!.textContent).toContain('3 minutes');
  });

  it('badCredentialsClearsPasswordAndShowsError', async () => {
    const t = setup(async () => 'bad-credentials');
    t.fill();
    t.send();
    await t.fixture.whenStable();
    t.fixture.detectChanges();
    expect(t.pass.value).toBe('');
    expect(t.el.querySelector('[role=alert]')!.textContent).toContain('Invalid username or password');
  });

  it('unexpectedErrorShowsGenericMessage', async () => {
    const t = setup(async () => {
      throw new Error('boom');
    });
    t.fill();
    t.send();
    await t.fixture.whenStable();
    t.fixture.detectChanges();
    expect(t.el.querySelector('[role=alert]')!.textContent).toContain('Login failed, try again');
    expect(t.pass.value).toBe('');
  });

  it('failedLoginFocusesPasswordInput', async () => {
    const t = setup(async () => 'bad-credentials');
    t.fill();
    t.send();
    await t.fixture.whenStable();
    t.fixture.detectChanges();
    expect(document.activeElement).toBe(t.pass);
  });

  it('submitReadsValuesSetWithoutInputEvents', async () => {
    const t = setup(async () => 'bad-credentials');
    t.user.value = 'autofill-user';
    t.pass.value = 'autofill-pass';
    t.send();
    await t.fixture.whenStable();
    expect(t.auth.login).toHaveBeenCalledWith('autofill-user', 'autofill-pass');
  });

  it('sessionLoadFailureAfterLoginShowsGenericError', async () => {
    const t = setup(async () => 'ok');
    t.auth.loadSession.mockImplementation(async () => undefined);
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    t.fill();
    t.send();
    await t.fixture.whenStable();
    t.fixture.detectChanges();
    expect(nav).not.toHaveBeenCalled();
    expect(t.el.querySelector('[role=alert]')!.textContent).toContain('Login failed, try again');
  });

  it('successLoadsSessionAndNavigates', async () => {
    const t = setup(async () => 'ok');
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    t.fill();
    t.send();
    await t.fixture.whenStable();
    expect(t.auth.loadSession).toHaveBeenCalled();
    expect(nav).toHaveBeenCalledWith('/');
  });
});

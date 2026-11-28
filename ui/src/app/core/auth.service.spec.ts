import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  it('loginFetchesCsrfBeforePosting', async () => {
    const result = auth.login('u', 'p');
    const csrf = http.expectOne('/api/v1/auth/csrf');
    expect(csrf.request.method).toBe('GET');
    http.expectNone('/api/v1/auth/login');
    csrf.flush(null, { status: 204, statusText: 'No Content' });
    const login = await vi.waitFor(() => http.expectOne('/api/v1/auth/login'));
    expect(login.request.method).toBe('POST');
    expect(login.request.body).toEqual({ username: 'u', password: 'p' });
    login.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBe('ok');
  });

  it('badCredentialsMapsTo401', async () => {
    const result = auth.login('u', 'bad');
    http.expectOne('/api/v1/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    (await vi.waitFor(() => http.expectOne('/api/v1/auth/login')))
      .flush({ error: 'BAD_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });
    expect(await result).toBe('bad-credentials');
  });

  it('lockedResponseReturnsSeconds', async () => {
    const result = auth.login('u', 'p');
    http.expectOne('/api/v1/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    (await vi.waitFor(() => http.expectOne('/api/v1/auth/login')))
      .flush({ error: 'LOCKED', retryAfterSeconds: 120 }, { status: 429, statusText: 'Too Many Requests' });
    expect(await result).toEqual({ locked: 120 });
  });

  it('otherFailuresReject', async () => {
    const result = auth.login('u', 'p');
    http.expectOne('/api/v1/auth/csrf').flush(null, { status: 204, statusText: 'No Content' });
    (await vi.waitFor(() => http.expectOne('/api/v1/auth/login'))).flush(null, { status: 500, statusText: 'Server Error' });
    await expect(result).rejects.toBeTruthy();
  });

  it('loadSessionSetsSession', async () => {
    const done = auth.loadSession();
    http.expectOne('/api/v1/session').flush({ username: 'pranshu', tradingMode: 'PAPER' });
    await done;
    expect(auth.session()).toEqual({ username: 'pranshu', tradingMode: 'PAPER' });
    expect(auth.reachable()).toBe(true);
  });

  it('loadSession401ClearsSession', async () => {
    const first = auth.loadSession();
    http.expectOne('/api/v1/session').flush({ username: 'a', tradingMode: 'PAPER' });
    await first;
    const second = auth.loadSession();
    http.expectOne('/api/v1/session').flush(null, { status: 401, statusText: 'Unauthorized' });
    await second;
    expect(auth.session()).toBeNull();
    expect(auth.reachable()).toBe(true);
  });

  it('loadSessionNetworkErrorKeepsSessionAndMarksOffline', async () => {
    const first = auth.loadSession();
    http.expectOne('/api/v1/session').flush({ username: 'a', tradingMode: 'PAPER' });
    await first;
    const second = auth.loadSession();
    http.expectOne('/api/v1/session').error(new ProgressEvent('error'));
    await second;
    expect(auth.session()).not.toBeNull();
    expect(auth.reachable()).toBe(false);
  });

  it('logoutFailureKeepsSessionAndRejects', async () => {
    const first = auth.loadSession();
    http.expectOne('/api/v1/session').flush({ username: 'a', tradingMode: 'PAPER' });
    await first;
    const out = auth.logout();
    http.expectOne('/api/v1/auth/logout').error(new ProgressEvent('error'));
    await expect(out).rejects.toBeTruthy();
    expect(auth.session()).not.toBeNull();
  });

  it('hangingSessionRequestTimesOutAsOfflineAfter5s', async () => {
    vi.useFakeTimers();
    try {
      const done = auth.loadSession();
      http.expectOne('/api/v1/session');
      await vi.advanceTimersByTimeAsync(5000);
      await done;
      expect(auth.reachable()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('sessionNullAfterLogout', async () => {
    const first = auth.loadSession();
    http.expectOne('/api/v1/session').flush({ username: 'a', tradingMode: 'PAPER' });
    await first;
    const out = auth.logout();
    const req = http.expectOne('/api/v1/auth/logout');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await out;
    expect(auth.session()).toBeNull();
  });
});

import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiService, panelError } from './api.service';

describe('ApiService', () => {
  const auth = { loadSession: vi.fn(async () => undefined) };
  let nav: ReturnType<typeof vi.fn>;
  let http: HttpTestingController;
  let api: ApiService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: Router, useValue: { navigateByUrl: (nav = vi.fn(async () => true)) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(ApiService);
  });

  it('getsSnapshotsFromGatewayPaths', async () => {
    const p = api.candles('BTC USD', new Date('2026-11-27T10:00:00Z'), new Date('2026-11-27T10:05:00Z'));
    const req = http.expectOne('/api/v1/candles?symbol=BTC%20USD&from=2026-11-27T10:00:00.000Z&to=2026-11-27T10:05:00.000Z');
    expect(req.request.method).toBe('GET');
    req.flush([]);
    expect(await p).toEqual([]);
    const o = api.orderDetails('x');
    http.expectOne('/api/v1/orders/x').flush({});
    await o;
  });

  it('unauthorizedClearsSessionAndGoesToLogin', async () => {
    const p = api.pnl();
    http.expectOne('/api/v1/pnl').flush(null, { status: 401, statusText: 'Unauthorized' });
    await expect(p).rejects.toBeTruthy();
    expect(auth.loadSession).toHaveBeenCalled();
    expect(nav).toHaveBeenCalledWith('/login');
  });

  it('panelErrorNamesUpstream', () => {
    const e = (status: number, error: unknown) => new HttpErrorResponse({ status, error });
    expect(panelError(e(503, { error: 'UPSTREAM_UNAVAILABLE', upstream: 'core' }))).toBe('core');
    expect(panelError(e(503, { upstream: 'risk' }))).toBe('risk');
    expect(panelError(e(503, null))).toBe('error');
    expect(panelError(e(500, { upstream: 'core' }))).toBe('error');
    expect(panelError(new Error('x'))).toBe('error');
  });
});

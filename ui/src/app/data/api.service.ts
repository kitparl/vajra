import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { Candle, Instrument, OrderDetails, Order, PanelError, PnlSummary, Position, Quote } from './models';

const API = '/api/v1';

/** A 503 with a known upstream names it; anything else is a generic error. */
export function panelError(err: unknown): PanelError {
  if (err instanceof HttpErrorResponse && err.status === 503) {
    const upstream = (err.error as { upstream?: string } | null)?.upstream;
    if (upstream === 'core' || upstream === 'risk') {
      return upstream;
    }
  }
  return 'error';
}

/** Read-only snapshot calls. A 401 ends the session and goes to /login, then the error still propagates. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  instruments(): Promise<Instrument[]> {
    return this.get('/instruments');
  }

  quotes(): Promise<Quote[]> {
    return this.get('/quotes');
  }

  positions(): Promise<Position[]> {
    return this.get('/positions');
  }

  pnl(): Promise<PnlSummary> {
    return this.get('/pnl');
  }

  orders(): Promise<Order[]> {
    return this.get('/orders');
  }

  orderDetails(id: string): Promise<OrderDetails> {
    return this.get(`/orders/${encodeURIComponent(id)}`);
  }

  candles(symbol: string, from: Date, to: Date): Promise<Candle[]> {
    const query = `symbol=${encodeURIComponent(symbol)}&from=${from.toISOString()}&to=${to.toISOString()}`;
    return this.get(`/candles?${query}`);
  }

  private async get<T>(path: string): Promise<T> {
    try {
      return await firstValueFrom(this.http.get<T>(`${API}${path}`));
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 401) {
        await this.auth.loadSession();
        await this.router.navigateByUrl('/login');
      }
      throw err;
    }
  }
}

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import { LoginResult, Session } from './session';

const API = '/api/v1';
const SESSION_TIMEOUT_MS = 5000;

interface LockedBody {
  retryAfterSeconds?: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly sessionState = signal<Session | null>(null);
  private readonly reachableState = signal(true);

  readonly session = this.sessionState.asReadonly();
  /** False when the gateway could not be reached on the last session check. */
  readonly reachable = this.reachableState.asReadonly();

  /**
   * Fetches a fresh CSRF cookie, then posts credentials. Login rotates the token, and Angular's
   * XSRF interceptor reads the cookie on every request, so nothing is cached here.
   * Resolves to a LoginResult for known outcomes and rejects on anything unexpected.
   */
  async login(username: string, password: string): Promise<LoginResult> {
    await firstValueFrom(this.http.get(`${API}/auth/csrf`));
    try {
      await firstValueFrom(this.http.post(`${API}/auth/login`, { username, password }));
      return 'ok';
    } catch (err) {
      if (err instanceof HttpErrorResponse) {
        if (err.status === 401) {
          return 'bad-credentials';
        }
        if (err.status === 429) {
          const body = err.error as LockedBody | null;
          return { locked: body?.retryAfterSeconds ?? Number(err.headers.get('Retry-After') ?? 0) };
        }
      }
      throw err;
    }
  }

  async logout(): Promise<void> {
    await firstValueFrom(this.http.post(`${API}/auth/logout`, null));
    this.sessionState.set(null);
  }

  /** 200 sets the session, 401 clears it, any other failure (including a 5 s timeout) keeps it and marks the gateway offline. */
  async loadSession(): Promise<void> {
    try {
      this.sessionState.set(await firstValueFrom(this.http.get<Session>(`${API}/session`).pipe(timeout(SESSION_TIMEOUT_MS))));
      this.reachableState.set(true);
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 401) {
        this.sessionState.set(null);
        this.reachableState.set(true);
      } else {
        this.reachableState.set(false);
      }
    }
  }
}

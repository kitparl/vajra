import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="login">
      <form class="card" (submit)="submit($event)" autocomplete="on">
        <h1>Vajra</h1>
        <label>
          Username
          <input name="username" type="text" autocomplete="username" required />
        </label>
        <label>
          Password
          <input name="password" type="password" autocomplete="current-password" required />
        </label>
        @if (message(); as text) {
          <p class="error" role="alert">{{ text }}</p>
        }
        <button type="submit" [disabled]="pending()">{{ pending() ? 'Signing in…' : 'Sign in' }}</button>
      </form>
    </main>
  `,
  styles: `
    .login { min-height: 100dvh; display: grid; place-items: center; padding: var(--space-4); }
    .card { width: 100%; max-width: 360px; display: grid; gap: var(--space-3); padding: var(--space-4);
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); }
    h1 { margin: 0; font-size: 20px; }
    label { display: grid; gap: var(--space-1); color: var(--text-muted); }
    input { padding: var(--space-2) var(--space-3); background: var(--bg); color: var(--text);
      border: 1px solid var(--border); border-radius: var(--radius); font: inherit; }
    button { padding: var(--space-2) var(--space-3); background: var(--accent); color: var(--accent-contrast);
      border: 0; border-radius: var(--radius); cursor: pointer; font-weight: 600; }
    button:disabled { opacity: 0.6; cursor: not-allowed; }
    .error { margin: 0; color: var(--danger); }
  `,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly pending = signal(false);
  private readonly messageState = signal<string | null>(null);
  protected readonly message = this.messageState.asReadonly();

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.pending()) {
      return;
    }
    // Read from the controls: autofill and iOS can skip input events.
    const form = event.target as HTMLFormElement;
    const username = (form.elements.namedItem('username') as HTMLInputElement).value;
    const passwordInput = form.elements.namedItem('password') as HTMLInputElement;
    this.pending.set(true);
    this.messageState.set(null);
    let signedIn = false;
    try {
      const result = await this.auth.login(username, passwordInput.value);
      if (result === 'ok') {
        await this.auth.loadSession();
        signedIn = this.auth.session() !== null;
        if (!signedIn) {
          this.messageState.set('Login failed, try again.');
        }
      } else {
        this.messageState.set(
          result === 'bad-credentials'
            ? 'Invalid username or password.'
            : `Too many attempts. Try again in ${minutes(result.locked)}.`,
        );
      }
    } catch {
      this.messageState.set('Login failed, try again.');
    } finally {
      passwordInput.value = '';
      this.pending.set(false);
    }
    if (signedIn) {
      await this.router.navigateByUrl('/');
    } else {
      passwordInput.focus();
    }
  }
}

function minutes(seconds: number): string {
  const m = Math.max(1, Math.ceil(seconds / 60));
  return `${m} minute${m === 1 ? '' : 's'}`;
}

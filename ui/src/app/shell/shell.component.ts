import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TopBarComponent } from './top-bar.component';

const PANELS = ['Watchlist', 'Chart', 'Orders', 'Positions'] as const;
type Panel = (typeof PANELS)[number];

@Component({
  selector: 'app-shell',
  imports: [TopBarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-top-bar />
    <nav class="tabs" aria-label="Panels">
      @for (p of panels; track p) {
        <button type="button" [attr.aria-pressed]="active() === p" (click)="active.set(p)">{{ p }}</button>
      }
    </nav>
    <main class="grid">
      @for (p of panels; track p) {
        <section [class]="'panel ' + p.toLowerCase()" [class.active]="active() === p" [attr.aria-label]="p">
          <h2>{{ p }}</h2>
          <p class="muted">Coming soon</p>
        </section>
      }
    </main>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 100dvh; }
    .tabs { display: flex; border-bottom: 1px solid var(--border); background: var(--surface); }
    .tabs button { flex: 1; padding: var(--space-3); background: none; border: 0;
      border-bottom: 2px solid transparent; cursor: pointer; }
    .tabs button[aria-pressed='true'] { border-bottom-color: var(--accent); font-weight: 700; }
    .grid { flex: 1; padding: var(--space-3); }
    .panel { display: none; background: var(--surface); border: 1px solid var(--border);
      border-radius: var(--radius); padding: var(--space-3); }
    .panel.active { display: block; }
    h2 { margin: 0 0 var(--space-2); font-size: 14px; }
    .muted { margin: 0; color: var(--text-muted); }
    @media (min-width: 1024px) {
      .tabs { display: none; }
      .grid { display: grid; gap: var(--space-3); grid-template-columns: 260px 1fr 360px;
        grid-template-rows: 1fr 1fr; grid-template-areas: 'watch chart orders' 'watch chart positions'; }
      .panel { display: block; }
      .watchlist { grid-area: watch; }
      .chart { grid-area: chart; }
      .orders { grid-area: orders; }
      .positions { grid-area: positions; }
    }
  `,
})
export class ShellComponent {
  protected readonly panels = PANELS;
  protected readonly active = signal<Panel>('Watchlist');
}

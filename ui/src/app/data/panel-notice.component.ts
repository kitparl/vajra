import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PanelError } from './models';

/** Inline notice for a panel whose call failed; names the upstream when the gateway reported one. */
@Component({
  selector: 'app-panel-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="notice" role="alert">{{ text() }}</p>`,
  styles: `.notice { margin: 0 0 var(--space-2); color: var(--danger); font-weight: 600; }`,
})
export class PanelNoticeComponent {
  readonly error = input.required<PanelError>();
  protected readonly text = computed(() => {
    const e = this.error();
    return e === 'error' ? 'Could not load data' : `${e} unavailable`;
  });
}

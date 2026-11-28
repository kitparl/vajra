import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiService } from '../data/api.service';
import { fakeLive } from '../data/fakes.testing';
import { LiveService } from '../data/live.service';
import { ShellComponent } from './shell.component';

describe('ShellComponent', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        { provide: LiveService, useValue: fakeLive() },
        {
          provide: ApiService,
          useValue: {
            instruments: async () => [],
            quotes: async () => [],
            candles: async () => [],
            orders: async () => [],
            positions: async () => [],
            pnl: async () => ({ realizedPnl: null, feesPaid: null, unrealizedPnl: null, netPnl: null }),
          },
        },
        {
          provide: AuthService,
          useValue: {
            session: signal({ username: 'a', tradingMode: 'PAPER' }),
            reachable: signal(true),
            loadSession: async () => undefined,
            logout: async () => undefined,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('rendersFourPanels', () => {
    const { el } = setup();
    expect(Array.from(el.querySelectorAll('section h2')).map((h) => h.textContent)).toEqual([
      'Watchlist',
      'Chart',
      'Orders',
      'Positions',
    ]);
  });

  it('tabSelectionMarksActivePanel', () => {
    const { fixture, el } = setup();
    const tabs = el.querySelectorAll<HTMLButtonElement>('nav button');
    tabs[2].click();
    fixture.detectChanges();
    expect(tabs[2].getAttribute('aria-pressed')).toBe('true');
    expect(el.querySelectorAll('section.active').length).toBe(1);
    expect(el.querySelector('section.active h2')!.textContent).toBe('Orders');
  });

  it('mountsRealPanelsAndConnectsLive', () => {
    const { el } = setup();
    expect(el.querySelector('app-watchlist')).not.toBeNull();
    expect(el.querySelector('app-orders')).not.toBeNull();
    expect(el.querySelector('app-positions')).not.toBeNull();
    expect(TestBed.inject(LiveService).connect).toHaveBeenCalled();
  });
});

import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'vajra.theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  private readonly state = signal<Theme>(this.read());

  readonly theme = this.state.asReadonly();

  constructor() {
    this.apply(this.state());
  }

  toggle(): void {
    const next: Theme = this.state() === 'dark' ? 'light' : 'dark';
    this.state.set(next);
    this.apply(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // storage unavailable: the choice just lasts for this page load
    }
  }

  private read(): Theme {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
    } catch {
      return 'dark';
    }
  }

  private apply(theme: Theme): void {
    this.doc.documentElement.setAttribute('data-theme', theme);
  }
}

import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/theme.service';

@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App {
  // Instantiated here so the saved theme applies on the login page too.
  protected readonly theme = inject(ThemeService);
}

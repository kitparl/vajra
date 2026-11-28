import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth.guard';
import { LoginComponent } from './login/login.component';
import { ShellComponent } from './shell/shell.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  { path: '', component: ShellComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: '' },
];

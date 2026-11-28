import { DestroyRef } from '@angular/core';

/**
 * Runs `fn` at most once per `minMs`: the first trigger runs at once, triggers inside the window collapse
 * into one trailing run. The pending timer is cleared when the owner is destroyed.
 */
export function createThrottle(fn: () => void, minMs: number, destroyRef: DestroyRef): () => void {
  let lastRun = Number.NEGATIVE_INFINITY;
  let timer: ReturnType<typeof setTimeout> | null = null;
  destroyRef.onDestroy(() => {
    if (timer !== null) {
      clearTimeout(timer);
    }
  });
  const run = (): void => {
    lastRun = Date.now();
    fn();
  };
  return () => {
    if (timer !== null) {
      return;
    }
    const wait = lastRun + minMs - Date.now();
    if (wait <= 0) {
      run();
      return;
    }
    timer = setTimeout(() => {
      timer = null;
      run();
    }, wait);
  };
}

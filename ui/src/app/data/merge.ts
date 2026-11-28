/** Protobuf sends an unset string as ""; the screens show that as a dash, so it becomes null. */
export const blankToNull = (s: string): string | null => (s === '' ? null : s);

/** True when `incoming` is strictly older than `held`; a missing timestamp on either side never blocks an update. */
export function isOlder(incoming: string | null | undefined, held: string | null | undefined): boolean {
  if (!incoming || !held) {
    return false;
  }
  return Date.parse(incoming) < Date.parse(held);
}

// Fails if the built index.html has an inline <script> or onload= handler (blocked by the CSP).
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../dist/ui/browser/index.html', import.meta.url), 'utf8');
const inlineScript = /<script(?![^>]*\ssrc=)[^>]*>/i.test(html);
const onload = /\sonload\s*=/i.test(html);
if (inlineScript || onload) {
  console.error('index.html contains an inline script or onload handler, which the CSP blocks');
  process.exit(1);
}
console.log('index.html: no inline script, no onload');

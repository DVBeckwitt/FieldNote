import { readFile, writeFile } from 'node:fs/promises';

const read = name => readFile(new URL(`./prototype/${name}`, import.meta.url), 'utf8');
const [html, css, model, app] = await Promise.all(['index.html', 'styles.css', 'model.js', 'app.js'].map(read));
const script = model.replace(/^export /gm, '') + '\n' + app.replace(/^import .*from '\.\/model\.js';\r?\n/, '');
const standalone = html
  .replace('<link rel="stylesheet" href="styles.css">', () => `<style>${css}</style>`)
  .replace('<script type="module" src="app.js"></script>', () => `<script type="module">${script}</script>`);
await writeFile(new URL('./prototype/preview.html', import.meta.url), standalone, 'utf8');
console.log('Built prototype/preview.html — a standalone interactive prototype.');

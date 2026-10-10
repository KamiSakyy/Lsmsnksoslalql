import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const assetRoot = path.resolve(root, '../handoff/rigged');
const threeRoot = path.join(root, 'node_modules/three');
const publicRoot = path.join(root, 'public');
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.glb': 'model/gltf-binary' };

function within(root, relative) {
  const result = path.resolve(root, relative);
  return result.startsWith(root + path.sep) ? result : null;
}

const server = http.createServer((request, response) => {
  let pathname;
  try { pathname = decodeURIComponent(new URL(request.url, 'http://preview.invalid').pathname); }
  catch { response.writeHead(400); response.end('Invalid URL'); return; }
  let file;
  if (pathname === '/model.glb') file = path.join(assetRoot, 'anime-girl-rigged.glb');
  else if (pathname === '/report.json') file = path.join(assetRoot, 'rig-report.json');
  else if (pathname === '/vendor/three.module.js' || pathname === '/vendor/three.core.js')
    file = within(threeRoot, 'build/' + path.basename(pathname));
  else if (pathname.startsWith('/vendor/addons/'))
    file = within(threeRoot, 'examples/jsm/' + pathname.slice('/vendor/addons/'.length));
  else file = within(publicRoot, pathname === '/' ? 'index.html' : pathname.slice(1));
  // Expose only viewer assets and the two fixed result files, never the repository or credentials.
  if (!file || !types[path.extname(file)] || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Файл пока не готов или не найден.'); return;
  }
  const size = fs.statSync(file).size;
  const headers = { 'Content-Type': types[path.extname(file)], 'Content-Length': size,
    'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' };
  if (request.url.includes('download=1') && pathname === '/model.glb')
    headers['Content-Disposition'] = 'attachment; filename="anime-girl-rigged.glb"';
  response.writeHead(200, headers);
  if (request.method === 'HEAD') { response.end(); return; }
  fs.createReadStream(file).on('error', () => response.destroy()).pipe(response);
});

const port = Number(process.env.PORT || 3000);
server.listen(port, '0.0.0.0', () => console.log(`3D model viewer listening on 0.0.0.0:${port}`));

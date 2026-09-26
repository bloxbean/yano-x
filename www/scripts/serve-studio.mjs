// Minimal read-only static server for Studio browser tests: serves one directory under /studio/ on loopback.
// It exists only for tests; Studio itself is a static site and needs no server of its own.
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const root = path.resolve(process.argv[2] ?? 'public/studio');
const port = Number(process.argv[3] ?? 4342);
const types = {'.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.yaml': 'text/yaml; charset=utf-8', '.svg': 'image/svg+xml'};

http.createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  if (request.method !== 'GET' || !url.pathname.startsWith('/studio/')) {
    response.writeHead(404).end();
    return;
  }
  const relative = decodeURIComponent(url.pathname.slice('/studio/'.length)) || 'index.html';
  const file = path.resolve(root, relative);
  if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, {'content-type': types[path.extname(file)] ?? 'application/octet-stream',
    'cache-control': 'no-store', 'x-content-type-options': 'nosniff'});
  fs.createReadStream(file).pipe(response);
}).listen(port, '127.0.0.1', () => console.log(`Studio test server on http://127.0.0.1:${port}/studio/`));

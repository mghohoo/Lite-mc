import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root = new URL('../app_pojavlauncher/src/main/assets/litemc/', import.meta.url);
const types = {html:'text/html; charset=utf-8',css:'text/css',js:'text/javascript'};
http.createServer(async (request,response) => {
  const name = request.url === '/' ? 'index.html' : request.url.slice(1);
  if (!/^(index\.html|app\.js|skin\.js|style\.css)$/.test(name)) {response.writeHead(404);response.end();return;}
  try {const content = await readFile(new URL(name,root));response.writeHead(200,{'Content-Type':types[name.split('.').pop()],'Cache-Control':'no-store'});response.end(content);}
  catch {response.writeHead(500);response.end('Preview file unavailable');}
}).listen(4178,'127.0.0.1',()=>console.log('Lite-MC UI preview: http://127.0.0.1:4178 (no native operations)'));

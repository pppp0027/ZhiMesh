/**
 * Used to parse the .env.development proxy configuration
 */
import type { ProxyOptions } from 'vite';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { Socket } from 'node:net';

type ProxyItem = [string, string];

type ProxyList = ProxyItem[];

type ProxyTargetList = Record<string, ProxyOptions & { rewrite: (path: string) => string }>;

const httpsRE = /^https:\/\//;

function configureProxyErrorHandler(proxy: any, prefix: string, target: string) {
  // Vite 3.2 assumes an error response object always exists. http-proxy can
  // omit it for aborted requests, which otherwise crashes the dev server.
  proxy.removeAllListeners('error');
  proxy.on('error', (error: Error, request?: IncomingMessage, response?: ServerResponse | Socket) => {
    const requestUrl = request?.url || prefix;
    console.error(`[vite proxy] ${requestUrl} -> ${target}: ${error.message}`);

    if (!response) return;

    if ('writeHead' in response && !response.headersSent && !response.writableEnded) {
      response.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ message: 'Backend service is temporarily unavailable.' }));
      return;
    }

    response.destroy();
  });
}

/**
 * Generate proxy
 * @param list
 */
export function createProxy(list: ProxyList = []) {
  const ret: ProxyTargetList = {};
  for (const [prefix, target] of list) {
    const isHttps = httpsRE.test(target);

    // https://github.com/http-party/node-http-proxy#options
    ret[prefix] = {
      target: target,
      changeOrigin: true,
      ws: true,
      rewrite: (path) => path.replace(new RegExp(`^${prefix}`), ''),
      configure: (proxy) => configureProxyErrorHandler(proxy, prefix, target),
      // https is require secure=false
      ...(isHttps ? { secure: false } : {}),
    };
  }
  return ret;
}

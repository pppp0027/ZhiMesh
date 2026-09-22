#!/usr/bin/env node
/**
 * ssh-exec — SSH 远程命令执行 MCP server（stdio 传输）
 *
 * 修复自 npm 包 ssh-mcp-server@1.0.4（该包两处致命 bug，不可直接使用）：
 *   1. privateKey 传的是 Promise 而非密钥内容 Buffer，认证必然失败（client-authentication）
 *   2. 未监听 ssh2 Client 的 'error' 事件，SSH 出错时整个进程崩溃
 *
 * 连接参数支持每次调用时传入；未传的项回落到启动环境变量：
 *   SSH_HOST / SSH_PORT / SSH_USER / SSH_KEY_PATH
 */
import { readFileSync } from 'node:fs';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { Client } from 'ssh2';

const ENV_DEFAULTS = {
  host: process.env.SSH_HOST,
  port: process.env.SSH_PORT ? Number(process.env.SSH_PORT) : 22,
  username: process.env.SSH_USER,
  privateKeyPath: process.env.SSH_KEY_PATH,
};

const server = new Server(
  { name: 'ssh-exec', version: '1.0.0' },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: [
    {
      name: 'execute_command',
      description:
        '通过 SSH 在远程服务器上执行命令。host/username/privateKeyPath 未传时使用服务启动时的环境变量默认值。',
      inputSchema: {
        type: 'object',
        properties: {
          command: { type: 'string', description: '要执行的 shell 命令' },
          stdin: { type: 'string', description: '可选，写入命令标准输入的内容' },
          host: { type: 'string', description: '默认取 SSH_HOST' },
          port: { type: 'number', description: '默认取 SSH_PORT 或 22' },
          username: { type: 'string', description: '默认取 SSH_USER' },
          privateKeyPath: { type: 'string', description: '私钥路径，默认取 SSH_KEY_PATH' },
        },
        required: ['command'],
      },
    },
  ],
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  if (request.params.name !== 'execute_command') {
    throw new Error(`Unknown tool: ${request.params.name}`);
  }
  const args = { ...ENV_DEFAULTS, ...request.params.arguments };
  const missing = ['host', 'username', 'privateKeyPath'].filter((k) => !args[k]);
  if (missing.length > 0) {
    return {
      content: [{ type: 'text', text: `缺少连接参数: ${missing.join(', ')}（调用时未传，也未配置对应环境变量）` }],
      isError: true,
    };
  }

  let privateKey;
  try {
    privateKey = readFileSync(args.privateKeyPath);
  } catch (e) {
    return {
      content: [{ type: 'text', text: `读取私钥失败 ${args.privateKeyPath}: ${e.message}` }],
      isError: true,
    };
  }

  // 每次调用新建 Client：避免跨调用复用已 end() 的连接
  return new Promise((resolve) => {
    const client = new Client();
    const fail = (msg) => {
      client.end();
      resolve({ content: [{ type: 'text', text: msg }], isError: true });
    };

    client.on('error', (e) => fail(`SSH 连接失败: ${e.message}${e.level ? ` (level=${e.level})` : ''}`));
    client.on('ready', () => {
      client.exec(args.command, (err, stream) => {
        if (err) {
          fail(`命令执行失败: ${err.message}`);
          return;
        }
        let stdout = '';
        let stderr = '';
        stream.on('data', (d) => (stdout += d)).stderr.on('data', (d) => (stderr += d));
        stream.on('close', (code) => {
          client.end();
          const text = `exit=${code}\n${stdout}${stderr ? `\n[stderr]\n${stderr}` : ''}`;
          resolve({ content: [{ type: 'text', text }] });
        });
        if (args.stdin) {
          stream.write(args.stdin);
          stream.end();
        }
      });
    });
    client.connect({
      host: args.host,
      port: args.port,
      username: args.username,
      privateKey,
      readyTimeout: 15000,
    });
  });
});

const transport = new StdioServerTransport();
await server.connect(transport);
console.error('ssh-exec MCP server running');

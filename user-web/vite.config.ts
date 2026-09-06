import path from 'path'
import type { PluginOption } from 'vite'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

function setupPlugins(env: ImportMetaEnv): PluginOption[] {
  return [
    vue(),
    env.VITE_GLOB_APP_PWA === 'true' && VitePWA({
      injectRegister: 'auto',
      manifest: {
        name: 'ZhiMesh',
        short_name: 'ZhiMesh',
        icons: [
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
    }),
  ]
}

export default defineConfig((env) => {
  const viteEnv = loadEnv(env.mode, process.cwd()) as unknown as ImportMetaEnv

  return {
    resolve: {
      alias: {
        '@': path.resolve(process.cwd(), 'src'),
      },
    },
    plugins: setupPlugins(viteEnv),
    server: {
      host: '0.0.0.0',
      port: 1002,
      open: false,
      proxy: {
        '/api': {
          target: viteEnv.VITE_APP_API_BASE_URL,
          changeOrigin: true, // 允许跨域
          rewrite: path => path.replace('/api/', '/'),
        },
      },
    },
    build: {
      reportCompressedSize: false,
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules'))
              return undefined
            if (/[\\/]node_modules[\\/](vue|vue-router|pinia|vue-i18n)[\\/]/.test(id))
              return 'vue-core'
            if (id.includes('node_modules/naive-ui') || id.includes('node_modules\\naive-ui'))
              return 'naive-ui'
            if (id.includes('node_modules/cytoscape') || id.includes('node_modules\\cytoscape'))
              return 'graph'
            if (id.includes('node_modules/@vue-flow') || id.includes('node_modules\\@vue-flow'))
              return 'workflow'
            if (/[\\/]node_modules[\\/](@traptitech[\\/]markdown-it-katex|markdown-it|markdown-it-link-attributes|katex|highlight\.js)[\\/]/.test(id))
              return 'markdown'
            return undefined
          },
        },
      },
      commonjsOptions: {
        ignoreTryCatch: false,
      },
    },
  }
})

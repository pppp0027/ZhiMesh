import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import dotenv from 'dotenv'

const projectRoot = process.cwd()
const packageJson = JSON.parse(readFileSync(resolve(projectRoot, 'package.json'), 'utf8'))
const config = {}

for (const fileName of ['.env', '.env.production']) {
  try {
    Object.assign(config, dotenv.parse(readFileSync(resolve(projectRoot, fileName))))
  } catch {
    // Optional environment files are intentionally ignored.
  }
}

for (const key of Object.keys(config)) {
  if (!key.startsWith('VITE_GLOB_'))
    delete config[key]
}

const configName = `__PRODUCTION__${config.VITE_GLOB_APP_SHORT_NAME || '__APP'}__CONF__`
  .toUpperCase()
  .replace(/\s/g, '')
const outputPath = resolve(projectRoot, 'dist', 'app.config.js')
const content = `window.${configName}=${JSON.stringify(config)};Object.freeze(window.${configName});Object.defineProperty(window,"${configName}",{configurable:false,writable:false});`

mkdirSync(dirname(outputPath), { recursive: true })
writeFileSync(outputPath, content)
console.log(`✓ [${packageJson.name}] - configuration file generated: dist/app.config.js`)

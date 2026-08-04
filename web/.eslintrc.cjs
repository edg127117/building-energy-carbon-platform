/**
 * 前端静态检查基线。
 *
 * Vue 模板交给 vue-eslint-parser，脚本块再交给 TypeScript 解析器；
 * 纯排版规则不作为 V1 合并门禁，避免 lint 自动重排现有页面模板。
 */
module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
  },
  parser: 'vue-eslint-parser',
  parserOptions: {
    parser: '@typescript-eslint/parser',
    ecmaVersion: 'latest',
    sourceType: 'module',
    extraFileExtensions: ['.vue'],
  },
  plugins: ['vue', '@typescript-eslint'],
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended',
    'plugin:@typescript-eslint/recommended',
  ],
  ignorePatterns: [
    'node_modules/',
    'dist/',
    'coverage/',
  ],
  overrides: [
    {
      files: ['vite.config.ts', '**/*.test.ts'],
      env: {
        node: true,
      },
    },
  ],
  rules: {
    'vue/max-attributes-per-line': 'off',
    'vue/singleline-html-element-content-newline': 'off',
    'vue/html-self-closing': 'off',
  },
}

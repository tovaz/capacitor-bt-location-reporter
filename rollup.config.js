import nodeResolve from '@rollup/plugin-node-resolve';

export default {
  input: 'dist/esm/index.js',
  output: [
    {
      file: 'dist/plugin.js',
      format: 'iife',
      name: 'capacitorBtLocationReporter',
      globals: { '@capacitor/core': 'capacitorExports' },
      sourcemap: false,
      inlineDynamicImports: true,
    },
    {
      file: 'dist/plugin.cjs.js',
      format: 'cjs',
      sourcemap: false,
      inlineDynamicImports: true,
    },
  ],
  external: ['@capacitor/core'],
  plugins: [nodeResolve()],
};

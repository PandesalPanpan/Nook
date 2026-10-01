import tseslint from 'typescript-eslint';
export default tseslint.config(
  {ignores: ['node_modules/**','**/build/**','**/.gradle/**','**/dist/**']},
  ...tseslint.configs.recommended,
  {files: ['**/*.ts'], rules: {'@typescript-eslint/no-unused-vars': ['error', {argsIgnorePattern: '^_'}]}},
);

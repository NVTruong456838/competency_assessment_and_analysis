import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  testIgnore: 'assessment-real.spec.ts',
  fullyParallel: true,
  reporter: 'list',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:3087',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'node tests/e2e/mock-backend.mjs',
      url: 'http://127.0.0.1:8187/health',
      reuseExistingServer: false,
      env: { PORT: '8187' },
      timeout: 30_000,
    },
    {
      command: 'npm start -- --hostname 127.0.0.1 --port 3087',
      url: 'http://127.0.0.1:3087/login',
      reuseExistingServer: false,
      env: { SPRING_API_URL: 'http://127.0.0.1:8187' },
      timeout: 120_000,
    },
  ],
});

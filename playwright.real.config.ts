import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: 'assessment-real.spec.ts',
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  outputDir: './test-results/real-http',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:3088',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium-real-http', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm start -- --hostname 127.0.0.1 --port 3088',
    url: 'http://127.0.0.1:3088/login',
    reuseExistingServer: false,
    env: { SPRING_API_URL: 'http://127.0.0.1:18081' },
    timeout: 120_000,
  },
});

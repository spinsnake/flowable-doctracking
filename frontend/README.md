# MFU Document Tracking frontend

Nuxt 4 SPA with Vue 3, TypeScript, Nuxt UI 4 and Tailwind 4. Versions are exact in package.json and package-lock.json. Node 24.18 and npm 11.16 are the tested runtime.

```powershell
npm ci
npm run dev
npm run test
npm run typecheck
npm run build
```

Development uses same-origin `/api/v1` proxied to `http://localhost:8080`. Override `API_PROXY_TARGET` in the server process if required. No credential belongs in frontend environment variables. Docker builds static `.output/public` and serves it through Nginx, forwarding API requests to the Compose `backend` service.

Google login starts at `/api/v1/auth/google/start`; all identity verification and session handling happen in the backend. Browser commands bootstrap `/auth/csrf`, carry `X-CSRF-TOKEN`, retain an idempotency key after an uncertain network response, and carry optimistic versions where applicable. Keys are actor scoped and cleared on logout. There is no demo login, local password, token storage or fake business data.

Implemented P1 surfaces: Google-only login, current profile, scoped navigation, central Unit and Station creation, unit user provisioning/editing, explicit Courier grants, login email status/retry, route template/version creation, origin registration/list/detail/timeline, private image upload/finalization/attachment, Station worklist, own reservation/cancel. P2–P5 workflow commands are intentionally absent. Workflow-pending messages do not imply task completion. Pending operations require an explicit refresh until the synchronization worker is implemented in P2.

Use isolated backend test identity/mail sink only for development/integration verification. Real Google/SMTP staging validation is P8 and requires configured infrastructure. Screens must be visually checked at desktop and mobile widths before reporting the frontend ready.

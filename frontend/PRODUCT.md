# Product context

MFU Document Tracking is a web application for university staff, unit administrators, central administrators, and explicitly assigned couriers. It records a document's immutable route and tracking model, shows scoped history and evidence, and coordinates workflow through the backend.

Confirmed requirements: Thai UI, Google-only login using allowlisted @mfu.ac.th accounts, role and Unit scope, login email notification, versioned route templates, document title or number, private validated photo evidence, single-document reservation and cancellation. P1 does not expose pickup/drop-off/receipt/ready commands, which belong to later phases. Display no fabricated business records or metrics.

## Platform
Web: Nuxt 4 SPA, Vue 3, TypeScript, Nuxt UI 4, Tailwind 4. Same-origin /api/v1 backend; no browser OAuth tokens or application passwords. Mobile web remains the same product and visual system.

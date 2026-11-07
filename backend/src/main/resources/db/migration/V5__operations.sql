-- Operational diagnostics are separate from business exceptions and never manufacture tracking evidence.
create table workflow_reconciliation_findings (
 document_id uuid not null references documents(id), code text not null,
 state text not null check(state in ('OPEN','RESOLVED')),
 first_seen_at timestamptz not null default now(), last_seen_at timestamptz not null default now(),
 resolved_at timestamptz, sightings bigint not null default 1,
 primary key(document_id,code)
);
create index reconciliation_open on workflow_reconciliation_findings(last_seen_at,document_id) where state='OPEN';

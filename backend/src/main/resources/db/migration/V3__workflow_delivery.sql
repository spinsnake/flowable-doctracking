alter table workflow_outbox add column last_error_code text;
alter table workflow_outbox add column version bigint not null default 0;
alter table workflow_outbox add constraint workflow_delivery_status check(status in ('PENDING','RETRY_WAIT','APPLIED','DEAD_LETTER'));
create index workflow_due on workflow_outbox(document_id,sequence) where status<>'APPLIED';
alter table workflow_inbox add column payload_hash varchar(64);
create table workflow_reminders (
 id uuid primary key default gen_random_uuid(), document_id uuid not null references documents(id),
 task_key text not null, route_step_id uuid not null, execution_id text not null,
 recorded_at timestamptz not null default now(),
 foreign key(document_id,route_step_id) references route_steps(document_id,id)
);

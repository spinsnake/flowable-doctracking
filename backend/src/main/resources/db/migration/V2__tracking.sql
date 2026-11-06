create table workflow_policy (
 id boolean primary key default true check(id), version bigint not null default 1,
 default_tracking_mode text not null check(default_tracking_mode in ('MODEL_A','MODEL_B')),
 changed_at timestamptz not null default now()
);
insert into workflow_policy(id,default_tracking_mode) values(true,'MODEL_B');
create table route_templates (
 id uuid primary key, origin_unit_id uuid not null references units(id), current_version integer not null check(current_version>0)
);
create table route_template_versions (
 template_id uuid not null references route_templates(id), version integer not null check(version>0),
 name text not null check(length(trim(name)) between 1 and 255), station_ids jsonb not null check(jsonb_array_length(station_ids)>=2),
 created_by uuid not null references users(id), created_at timestamptz not null default now(), primary key(template_id,version)
);
create table documents (
 id uuid primary key, title varchar(500), document_number varchar(255), origin_unit_id uuid not null references units(id),
 origin_station_id uuid not null references stations(id), template_id uuid not null, template_version integer not null,
 tracking_mode text not null check(tracking_mode in ('MODEL_A','MODEL_B')), policy_version bigint not null,
 business_state text not null, version bigint not null default 0 check(version>=0), current_route_step_id uuid not null,
 reported_location_step_id uuid, pending_receipt_step_id uuid, active_leg_id uuid, active_reservation_id uuid,
 process_sync_status text not null default 'PENDING' check(process_sync_status in ('PENDING','APPLIED','FAILED_RETRYABLE')),
 latest_operation_id uuid, event_sequence bigint not null default 0, created_by uuid not null references users(id),
 created_at timestamptz not null default now(), check(coalesce(length(trim(title)),0)+coalesce(length(trim(document_number)),0)>0),
 foreign key(template_id,template_version) references route_template_versions(template_id,version)
);
create index documents_origin on documents(origin_unit_id,created_at,id);
create table route_steps (
 id uuid primary key, document_id uuid not null references documents(id), sequence integer not null check(sequence>=0),
 station_id uuid not null references stations(id), unique(document_id,sequence), unique(document_id,id)
);
alter table documents add foreign key(id,current_route_step_id) references route_steps(document_id,id) deferrable initially deferred;
alter table documents add foreign key(id,reported_location_step_id) references route_steps(document_id,id) deferrable initially deferred;
alter table documents add foreign key(id,pending_receipt_step_id) references route_steps(document_id,id) deferrable initially deferred;
create index route_steps_station on route_steps(station_id,document_id);
create table reservations (
 id uuid primary key, owner_user_id uuid not null references users(id), station_id uuid not null references stations(id),
 status text not null check(status in ('ACTIVE','CANCELLED','EXPIRED','CONSUMED')), expires_at timestamptz not null,
 version bigint not null default 0, operation_id uuid not null, correlation_id text not null, created_at timestamptz not null default now()
);
create index reservations_expiry on reservations(expires_at) where status='ACTIVE';
create table reservation_items (
 reservation_id uuid not null references reservations(id), document_id uuid not null references documents(id), route_step_id uuid not null,
 previous_state text not null, reserved_version bigint not null, is_active boolean not null default true,
 primary key(reservation_id,document_id), foreign key(document_id,route_step_id) references route_steps(document_id,id)
);
create unique index one_active_reservation on reservation_items(document_id) where is_active;
create table courier_legs (
 id uuid primary key, document_id uuid not null references documents(id), courier_user_id uuid not null references users(id),
 from_step_id uuid not null, to_step_id uuid not null, started_at timestamptz not null default now(), ended_at timestamptz,
 foreign key(document_id,from_step_id) references route_steps(document_id,id), foreign key(document_id,to_step_id) references route_steps(document_id,id),
 unique(document_id,id), check(from_step_id<>to_step_id)
);
create unique index one_active_leg on courier_legs(document_id) where ended_at is null;
create table direct_transfers (
 id uuid primary key, document_id uuid not null references documents(id), from_step_id uuid not null, to_step_id uuid not null,
 dispatched_by uuid not null references users(id), dispatched_at timestamptz not null default now(), received_at timestamptz,
 foreign key(document_id,from_step_id) references route_steps(document_id,id), foreign key(document_id,to_step_id) references route_steps(document_id,id)
);
create unique index one_active_direct_transfer on direct_transfers(document_id) where received_at is null;
alter table documents add foreign key(id,active_leg_id) references courier_legs(document_id,id) deferrable initially deferred;
alter table documents add foreign key(active_reservation_id,id) references reservation_items(reservation_id,document_id) deferrable initially deferred;
create table tracking_operations (
 id uuid primary key, actor_id uuid not null references users(id), document_id uuid not null references documents(id),
 result jsonb not null, created_at timestamptz not null default now()
);
create table document_events (
 id uuid primary key, document_id uuid not null references documents(id), sequence bigint not null,
 event_type text not null, actor_id uuid references users(id), route_step_id uuid, from_step_id uuid, to_step_id uuid,
 operation_id uuid not null, recorded_at timestamptz not null default now(), payload jsonb not null default '{}',
 unique(document_id,sequence), unique(operation_id,document_id,event_type),
 foreign key(document_id,route_step_id) references route_steps(document_id,id),
 foreign key(document_id,from_step_id) references route_steps(document_id,id), foreign key(document_id,to_step_id) references route_steps(document_id,id)
);
create table workflow_outbox (
 id uuid primary key, event_id uuid not null unique references document_events(id), document_id uuid not null references documents(id),
 sequence bigint not null, action text not null, payload jsonb not null, status text not null default 'PENDING', attempts integer not null default 0,
 available_at timestamptz not null default now(), created_at timestamptz not null default now(), unique(document_id,sequence)
);
create table workflow_inbox (
 event_id uuid primary key references document_events(id), document_id uuid not null references documents(id), applied_at timestamptz not null default now()
);
create table workflow_instances (
 document_id uuid primary key references documents(id), process_instance_id text not null unique, process_definition_id text not null,
 process_definition_version integer not null, created_at timestamptz not null default now()
);
create table evidence_uploads (
 id uuid primary key, owner_user_id uuid not null references users(id), document_id uuid references documents(id), station_id uuid references stations(id),
 content_type text not null check(content_type in ('image/jpeg','image/png')), size_bytes bigint not null check(size_bytes>0),
 checksum_sha256 varchar(64) not null, status text not null default 'INITIATED' check(status in ('INITIATED','STORED','FINALIZED')),
 storage_key text not null unique, created_at timestamptz not null default now(), finalized_at timestamptz,
 check(document_id is not null or station_id is not null)
);
create table event_evidence (
 event_id uuid not null references document_events(id), evidence_id uuid not null references evidence_uploads(id), primary key(event_id,evidence_id)
);
create table tracking_exceptions (
 id uuid primary key, document_id uuid not null references documents(id), opened_by uuid not null references users(id),
 code text not null, previous_state text not null, opened_at timestamptz not null default now(), resolved_at timestamptz,
 resolution_event_id uuid references document_events(id)
);
create function reject_ledger_mutation() returns trigger language plpgsql as $$ begin
 raise exception 'Immutable tracking ledger' using errcode='23514'; end $$;
create trigger document_events_immutable before update or delete on document_events for each row execute function reject_ledger_mutation();
create trigger route_steps_immutable before update or delete on route_steps for each row execute function reject_ledger_mutation();
create trigger template_versions_immutable before update or delete on route_template_versions for each row execute function reject_ledger_mutation();
create trigger event_evidence_immutable before update or delete on event_evidence for each row execute function reject_ledger_mutation();
create function protect_document_snapshot() returns trigger language plpgsql as $$ begin
 if row(new.origin_unit_id,new.origin_station_id,new.template_id,new.template_version,new.tracking_mode,new.policy_version)
 is distinct from row(old.origin_unit_id,old.origin_station_id,old.template_id,old.template_version,old.tracking_mode,old.policy_version) then
 raise exception 'Immutable document snapshot' using errcode='23514'; end if; return new; end $$;
create trigger documents_snapshot_immutable before update on documents for each row execute function protect_document_snapshot();
create function protect_finalized_evidence() returns trigger language plpgsql as $$ begin
 if old.status='FINALIZED' then raise exception 'Finalized evidence is immutable' using errcode='23514'; end if;
 if tg_op='DELETE' then return old; end if;
 return new; end $$;
create trigger finalized_evidence_immutable before update or delete on evidence_uploads for each row execute function protect_finalized_evidence();
create function validate_event_evidence() returns trigger language plpgsql as $$ begin
 if not exists(select 1 from evidence_uploads where id=new.evidence_id and status='FINALIZED') then
 raise exception 'Evidence must be finalized' using errcode='23514'; end if; return new; end $$;
create trigger event_evidence_validated before insert on event_evidence for each row execute function validate_event_evidence();
create function guard_active_transport() returns trigger language plpgsql as $$ begin
 perform id from documents where id=new.document_id for update;
 if tg_table_name='reservation_items' then
   if new.is_active and (exists(select 1 from courier_legs where document_id=new.document_id and ended_at is null)
   or exists(select 1 from direct_transfers where document_id=new.document_id and received_at is null)) then
     raise exception 'Document already has active transport' using errcode='23514';
   end if;
 elsif tg_table_name='courier_legs' then
   if new.ended_at is null and (exists(select 1 from reservation_items where document_id=new.document_id and is_active)
   or exists(select 1 from direct_transfers where document_id=new.document_id and received_at is null)) then
     raise exception 'Document already reserved or directly dispatched' using errcode='23514';
   end if;
 elsif tg_table_name='direct_transfers' then
   if new.received_at is null and (exists(select 1 from reservation_items where document_id=new.document_id and is_active)
   or exists(select 1 from courier_legs where document_id=new.document_id and ended_at is null)) then
     raise exception 'Document already reserved or in courier custody' using errcode='23514';
   end if;
 end if; return new; end $$;
create trigger reservation_transport_guard before insert or update on reservation_items for each row execute function guard_active_transport();
create trigger leg_transport_guard before insert or update on courier_legs for each row execute function guard_active_transport();
create trigger direct_transport_guard before insert or update on direct_transfers for each row execute function guard_active_transport();

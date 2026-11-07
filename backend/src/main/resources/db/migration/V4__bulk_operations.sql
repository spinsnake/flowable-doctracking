-- One command may accept several documents, or reject every selected item.
alter table tracking_operations alter column document_id drop not null;
create table operation_documents (
 operation_id uuid not null references tracking_operations(id),
 document_id uuid not null references documents(id),
 primary key(operation_id,document_id)
);
insert into operation_documents select id,document_id from tracking_operations where document_id is not null;

-- Existing singleton commands keep the same operation/document association.
create function link_operation_document() returns trigger language plpgsql as $$
begin
 if new.document_id is not null then
  insert into operation_documents(operation_id,document_id) values(new.id,new.document_id) on conflict do nothing;
 end if;
 return new;
end;
$$;
create trigger operation_document_link after insert on tracking_operations
 for each row execute function link_operation_document();

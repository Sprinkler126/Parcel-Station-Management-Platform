alter table parcel add column pickup_request_id varchar(96);

create unique index uk_parcel_pickup_request on parcel (pickup_request_id);

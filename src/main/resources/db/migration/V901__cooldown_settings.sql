create table cooldown_settings (
    id                    varchar(16) primary key,
    min_days              int         not null,
    max_days              int         not null,
    buffer_days           int         not null,
    default_days          int         not null,
    tight_threshold       double      not null,
    emergency_threshold   double      not null,
    ewma_alpha            double      not null,
    stat_window_days      int         not null,
    updated_at            datetime(3) not null,
    operator              varchar(32)
);

insert into cooldown_settings (
    id, min_days, max_days, buffer_days, default_days,
    tight_threshold, emergency_threshold, ewma_alpha, stat_window_days,
    updated_at, operator
) values (
    'GLOBAL', 3, 90, 3, 7, 0.30, 0.10, 0.30, 14, current_timestamp, 'SYSTEM'
);

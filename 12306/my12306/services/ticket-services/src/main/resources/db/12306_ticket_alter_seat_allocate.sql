-- Apply only after SHOW INDEX confirms idx_seat_allocate is absent.
-- Keep idx_seat_query: other read paths are outside this migration.
ALTER TABLE `12306_ticket`.`t_seat`
    ADD INDEX `idx_seat_allocate`
        (`train_id`, `seat_type`, `seat_status`, `start_station`, `end_station`,
         `del_flag`, `carriage_number`, `seat_number`),
    ALGORITHM=INPLACE, LOCK=NONE;

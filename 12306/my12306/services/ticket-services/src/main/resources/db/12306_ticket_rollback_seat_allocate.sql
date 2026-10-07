-- Revert the allocator code first when reverting the optimization as a whole.
-- Removing only this index preserves correctness but may restore filesort costs.
ALTER TABLE `12306_ticket`.`t_seat`
    DROP INDEX `idx_seat_allocate`, ALGORITHM=INPLACE, LOCK=NONE;

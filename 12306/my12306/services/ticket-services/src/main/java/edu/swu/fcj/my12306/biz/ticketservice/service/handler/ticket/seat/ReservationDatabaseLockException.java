package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat;

import org.springframework.dao.PessimisticLockingFailureException;

/** Marks a statement failure from the method body, never a failure during transaction commit. */
public class ReservationDatabaseLockException extends RuntimeException {
    public ReservationDatabaseLockException(PessimisticLockingFailureException cause) {
        super(cause);
    }

    public PessimisticLockingFailureException failure() {
        return (PessimisticLockingFailureException) getCause();
    }
}

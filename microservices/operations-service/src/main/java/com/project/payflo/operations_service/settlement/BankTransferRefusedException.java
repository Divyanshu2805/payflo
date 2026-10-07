package com.project.payflo.operations_service.settlement;

/** The bank would not accept the transfer: it never started, so nothing was paid. */
public class BankTransferRefusedException extends RuntimeException {

    public BankTransferRefusedException(String message) {
        super(message);
    }
}

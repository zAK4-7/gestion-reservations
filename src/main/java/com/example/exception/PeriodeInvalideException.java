package com.example.exception;

/** Les dates fournies sont absentes ou incohérentes (début >= fin). */
public class PeriodeInvalideException extends GestionReservationException {

    public PeriodeInvalideException(String message) {
        super(message);
    }
}

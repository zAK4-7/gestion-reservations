package com.example.exception;

/** Erreur métier ou technique remontée par la couche service. */
public class GestionReservationException extends RuntimeException {

    public GestionReservationException(String message) {
        super(message);
    }

    public GestionReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.example.exception;

/** La salle est déjà prise sur tout ou partie du créneau demandé. */
public class SalleIndisponibleException extends GestionReservationException {

    public SalleIndisponibleException(String message) {
        super(message);
    }
}

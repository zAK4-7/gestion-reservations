package com.example.util;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

/**
 * Fabrique unique d'EntityManagerFactory : on la crée une seule fois (coûteux)
 * et on la partage ; chaque unité de travail ouvre ensuite son propre EntityManager.
 */
public final class JpaUtil {

    public static final String UNITE_PERSISTANCE = "gestion-reservations-pu";

    private static EntityManagerFactory emf;

    private JpaUtil() {}

    public static synchronized EntityManagerFactory getEmf() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory(UNITE_PERSISTANCE);
        }
        return emf;
    }

    public static synchronized void fermer() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
    }
}

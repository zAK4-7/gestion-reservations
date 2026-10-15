package com.example;

import com.example.exception.PeriodeInvalideException;
import com.example.exception.SalleIndisponibleException;
import com.example.model.Equipement;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationService;
import com.example.util.JpaUtil;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.time.LocalDateTime;

/**
 * Démonstration de bout en bout : cascades, orphelins, suppression,
 * puis utilisation du service métier (disponibilité, requêtes JPQL, erreurs).
 */
public class App {

    private static final LocalDateTime MATIN = LocalDateTime.of(2030, 3, 4, 9, 0);

    public static void main(String[] args) {
        EntityManagerFactory emf = JpaUtil.getEmf();
        try {
            long[] ids = etapeCascade(emf);
            long utilisateurId = ids[0];
            long salleId = ids[1];
            long equipementId = ids[2];

            etapeOrphelin(emf, utilisateurId);
            etapeSuppressionUtilisateur(emf, utilisateurId, salleId, equipementId);
            etapeService(emf);
        } finally {
            JpaUtil.fermer();
        }
    }

    /** Un seul persist(utilisateur) doit enregistrer tout le graphe d'objets. */
    private static long[] etapeCascade(EntityManagerFactory emf) {
        titre("1. Persistance en cascade");
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();

            Utilisateur alice = new Utilisateur("Alice Dupont", "alice@example.com");
            Salle salleA = new Salle("Salle A", 12);
            Equipement projecteur = new Equipement("Projecteur");
            salleA.addEquipement(projecteur);

            alice.addReservation(new Reservation(salleA, MATIN, MATIN.plusHours(2)));
            alice.addReservation(new Reservation(salleA, MATIN.plusHours(5), MATIN.plusHours(6)));

            em.persist(alice);
            em.getTransaction().commit();

            System.out.println(">> Enregistré : " + alice + ", " + salleA + ", " + projecteur);
            System.out.println(">> Réservations d'Alice en base : " + compterReservations(emf, alice.getId()));
            return new long[]{alice.getId(), salleA.getId(), projecteur.getId()};
        } catch (RuntimeException e) {
            annuler(em);
            throw e;
        } finally {
            em.close();
        }
    }

    /** Retirer une réservation de la collection suffit à la supprimer de la base. */
    private static void etapeOrphelin(EntityManagerFactory emf, long utilisateurId) {
        titre("2. Suppression d'une réservation orpheline");
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Utilisateur alice = em.find(Utilisateur.class, utilisateurId);
            Reservation premiere = alice.getReservations().iterator().next();
            System.out.println(">> Retrait de : " + premiere);
            alice.removeReservation(premiere);
            em.getTransaction().commit();
            System.out.println(">> Réservations restantes : " + compterReservations(emf, utilisateurId));
        } catch (RuntimeException e) {
            annuler(em);
            throw e;
        } finally {
            em.close();
        }
    }

    /** L'utilisateur et ses réservations disparaissent ; la salle et l'équipement restent. */
    private static void etapeSuppressionUtilisateur(EntityManagerFactory emf, long utilisateurId,
                                                    long salleId, long equipementId) {
        titre("3. Suppression de l'utilisateur");
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.remove(em.find(Utilisateur.class, utilisateurId));
            em.getTransaction().commit();
        } catch (RuntimeException e) {
            annuler(em);
            throw e;
        } finally {
            em.close();
        }

        EntityManager verification = emf.createEntityManager();
        try {
            System.out.println(">> Utilisateur encore présent ? " + (verification.find(Utilisateur.class, utilisateurId) != null));
            System.out.println(">> Réservations restantes : " + compterReservations(emf, utilisateurId));
            System.out.println(">> Salle conservée ? " + (verification.find(Salle.class, salleId) != null));
            System.out.println(">> Équipement conservé ? " + (verification.find(Equipement.class, equipementId) != null));
        } finally {
            verification.close();
        }
    }

    /** Parcours des fonctionnalités métier : disponibilité, JPQL, gestion des erreurs. */
    private static void etapeService(EntityManagerFactory emf) {
        titre("4. Service métier");
        ReservationService service = new ReservationService(emf);

        Utilisateur bob = service.creerUtilisateur("Bob Martin", "bob@example.com");
        // "Projecteur" existe déjà : il est réutilisé, "Tableau blanc" est créé.
        Salle salleB = service.creerSalle("Salle B", 8, "Projecteur", "Tableau blanc");

        Reservation reservation = service.reserver(bob.getId(), salleB.getId(), MATIN, MATIN.plusHours(2));
        System.out.println(">> Réservation créée : " + reservation);

        try {
            service.reserver(bob.getId(), salleB.getId(), MATIN.plusHours(1), MATIN.plusHours(3));
        } catch (SalleIndisponibleException e) {
            System.out.println(">> Conflit détecté : " + e.getMessage());
        }

        try {
            service.reserver(bob.getId(), salleB.getId(), MATIN.plusHours(4), MATIN.plusHours(3));
        } catch (PeriodeInvalideException e) {
            System.out.println(">> Période refusée : " + e.getMessage());
        }

        System.out.println(">> Salles équipées d'un projecteur : " + service.sallesAvecEquipement("Projecteur"));
        System.out.println(">> Salles libres de 10h à 11h : "
                + service.sallesDisponibles(MATIN.plusHours(1), MATIN.plusHours(2)));
        System.out.println(">> Réservations de Bob : " + service.reservationsDeUtilisateur(bob.getId()));

        service.annulerReservation(reservation.getId());
        System.out.println(">> Après annulation, Salle B libre à 9h ? "
                + service.estDisponible(salleB.getId(), MATIN, MATIN.plusHours(1)));
    }

    private static long compterReservations(EntityManagerFactory emf, long utilisateurId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                            "SELECT COUNT(r) FROM Reservation r WHERE r.utilisateur.id = :id", Long.class)
                    .setParameter("id", utilisateurId)
                    .getSingleResult();
        } finally {
            em.close();
        }
    }

    private static void annuler(EntityManager em) {
        if (em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
    }

    private static void titre(String texte) {
        System.out.println();
        System.out.println("========== " + texte + " ==========");
    }
}

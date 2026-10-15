package com.example.service;

import com.example.exception.GestionReservationException;
import com.example.exception.PeriodeInvalideException;
import com.example.exception.SalleIndisponibleException;
import com.example.model.Equipement;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Logique métier : chaque méthode publique ouvre un EntityManager court,
 * exécute son travail dans une transaction (ou en lecture seule) puis le ferme.
 */
public class ReservationService {

    private final EntityManagerFactory emf;

    public ReservationService(EntityManagerFactory emf) {
        this.emf = emf;
    }

    // ------------------------------------------------------------------
    // Création
    // ------------------------------------------------------------------

    public Utilisateur creerUtilisateur(String nom, String email) {
        return dansTransaction(em -> {
            Utilisateur utilisateur = new Utilisateur(nom, email);
            em.persist(utilisateur);
            return utilisateur;
        });
    }

    /**
     * Crée une salle et ses équipements. Un équipement dont le nom existe déjà
     * est réutilisé au lieu d'être dupliqué (le nom est unique en base).
     */
    public Salle creerSalle(String nom, int capacite, String... nomsEquipements) {
        if (capacite < 1) {
            throw new GestionReservationException("La capacité doit être au moins de 1 personne");
        }
        return dansTransaction(em -> {
            Salle salle = new Salle(nom, capacite);
            Set<String> nomsUniques = new LinkedHashSet<>(List.of(nomsEquipements));
            for (String nomEquipement : nomsUniques) {
                List<Equipement> existants = em
                        .createQuery("SELECT e FROM Equipement e WHERE e.nom = :nom", Equipement.class)
                        .setParameter("nom", nomEquipement)
                        .getResultList();
                Equipement equipement = existants.isEmpty() ? new Equipement(nomEquipement) : existants.get(0);
                salle.addEquipement(equipement);
            }
            em.persist(salle);
            return salle;
        });
    }

    // ------------------------------------------------------------------
    // Réservations
    // ------------------------------------------------------------------

    public Reservation reserver(Long utilisateurId, Long salleId, LocalDateTime debut, LocalDateTime fin) {
        validerPeriode(debut, fin);
        return dansTransaction(em -> {
            Utilisateur utilisateur = em.find(Utilisateur.class, utilisateurId);
            if (utilisateur == null) {
                throw new GestionReservationException("Utilisateur introuvable (id=" + utilisateurId + ")");
            }
            Salle salle = em.find(Salle.class, salleId);
            if (salle == null) {
                throw new GestionReservationException("Salle introuvable (id=" + salleId + ")");
            }
            if (compterChevauchements(em, salleId, debut, fin) > 0) {
                throw new SalleIndisponibleException(
                        "La salle '" + salle.getNom() + "' est déjà occupée entre " + debut + " et " + fin);
            }
            Reservation reservation = new Reservation(salle, debut, fin);
            reservation.setUtilisateur(utilisateur);
            em.persist(reservation);
            return reservation;
        });
    }

    /** Annule une réservation en la retirant de la collection de son utilisateur (orphanRemoval). */
    public void annulerReservation(Long reservationId) {
        dansTransaction(em -> {
            Reservation reservation = em.find(Reservation.class, reservationId);
            if (reservation == null) {
                throw new GestionReservationException("Réservation introuvable (id=" + reservationId + ")");
            }
            reservation.getUtilisateur().removeReservation(reservation);
            return null;
        });
    }

    /** Supprime l'utilisateur ; ses réservations partent avec lui, mais pas les salles. */
    public void supprimerUtilisateur(Long utilisateurId) {
        dansTransaction(em -> {
            Utilisateur utilisateur = em.find(Utilisateur.class, utilisateurId);
            if (utilisateur == null) {
                throw new GestionReservationException("Utilisateur introuvable (id=" + utilisateurId + ")");
            }
            em.remove(utilisateur);
            return null;
        });
    }

    // ------------------------------------------------------------------
    // Requêtes
    // ------------------------------------------------------------------

    public boolean estDisponible(Long salleId, LocalDateTime debut, LocalDateTime fin) {
        validerPeriode(debut, fin);
        return lecture(em -> compterChevauchements(em, salleId, debut, fin) == 0);
    }

    public List<Salle> sallesDisponibles(LocalDateTime debut, LocalDateTime fin) {
        validerPeriode(debut, fin);
        return lecture(em -> em.createQuery(
                        "SELECT s FROM Salle s WHERE NOT EXISTS ("
                                + " SELECT r.id FROM Reservation r"
                                + " WHERE r.salle = s AND r.dateDebut < :fin AND r.dateFin > :debut)"
                                + " ORDER BY s.nom", Salle.class)
                .setParameter("debut", debut)
                .setParameter("fin", fin)
                .getResultList());
    }

    public List<Reservation> reservationsDeUtilisateur(Long utilisateurId) {
        return lecture(em -> em.createQuery(
                        "SELECT r FROM Reservation r JOIN FETCH r.salle"
                                + " WHERE r.utilisateur.id = :id ORDER BY r.dateDebut", Reservation.class)
                .setParameter("id", utilisateurId)
                .getResultList());
    }

    public List<Salle> sallesAvecEquipement(String nomEquipement) {
        return lecture(em -> em.createQuery(
                        "SELECT DISTINCT s FROM Salle s JOIN s.equipements e"
                                + " WHERE e.nom = :nom ORDER BY s.nom", Salle.class)
                .setParameter("nom", nomEquipement)
                .getResultList());
    }

    public Optional<Utilisateur> trouverUtilisateurParEmail(String email) {
        return lecture(em -> em.createQuery(
                        "SELECT u FROM Utilisateur u WHERE u.email = :email", Utilisateur.class)
                .setParameter("email", email)
                .getResultStream()
                .findFirst());
    }

    // ------------------------------------------------------------------
    // Outils internes
    // ------------------------------------------------------------------

    private void validerPeriode(LocalDateTime debut, LocalDateTime fin) {
        if (debut == null || fin == null) {
            throw new PeriodeInvalideException("Les dates de début et de fin sont obligatoires");
        }
        if (!debut.isBefore(fin)) {
            throw new PeriodeInvalideException(
                    "La date de début (" + debut + ") doit précéder la date de fin (" + fin + ")");
        }
    }

    /**
     * Deux créneaux se chevauchent si chacun commence avant la fin de l'autre.
     * Les comparaisons sont strictes : un créneau qui commence exactement
     * quand un autre se termine n'est donc pas un conflit.
     */
    private long compterChevauchements(EntityManager em, Long salleId, LocalDateTime debut, LocalDateTime fin) {
        return em.createQuery(
                        "SELECT COUNT(r) FROM Reservation r"
                                + " WHERE r.salle.id = :salleId AND r.dateDebut < :fin AND r.dateFin > :debut",
                        Long.class)
                .setParameter("salleId", salleId)
                .setParameter("debut", debut)
                .setParameter("fin", fin)
                .getSingleResult();
    }

    private <R> R dansTransaction(Function<EntityManager, R> travail) {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            R resultat = travail.apply(em);
            tx.commit();
            return resultat;
        } catch (GestionReservationException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw new GestionReservationException("Échec de l'opération de persistance : " + e.getMessage(), e);
        } finally {
            em.close();
        }
    }

    private <R> R lecture(Function<EntityManager, R> requete) {
        EntityManager em = emf.createEntityManager();
        try {
            return requete.apply(em);
        } finally {
            em.close();
        }
    }
}

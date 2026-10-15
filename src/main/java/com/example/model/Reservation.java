package com.example.model;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import java.time.LocalDateTime;

/**
 * Créneau pendant lequel un utilisateur occupe une salle.
 */
@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date_debut", nullable = false)
    private LocalDateTime dateDebut;

    @Column(name = "date_fin", nullable = false)
    private LocalDateTime dateFin;

    @ManyToOne(optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private Utilisateur utilisateur;

    // PERSIST/MERGE permettent d'enregistrer une salle neuve en même temps que la réservation.
    // Aucune cascade REMOVE : annuler une réservation ne doit jamais détruire la salle.
    @ManyToOne(optional = false, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "salle_id", nullable = false)
    private Salle salle;

    public Reservation() {}

    public Reservation(Salle salle, LocalDateTime dateDebut, LocalDateTime dateFin) {
        this.salle = salle;
        this.dateDebut = dateDebut;
        this.dateFin = dateFin;
    }

    /**
     * Dernier filet de sécurité : Hibernate appelle cette méthode juste avant
     * chaque INSERT ou UPDATE, quel que soit le chemin d'accès utilisé.
     */
    @PrePersist
    @PreUpdate
    void verifierCoherenceDesDates() {
        if (dateDebut == null || dateFin == null) {
            throw new IllegalStateException("Les dates de début et de fin sont obligatoires");
        }
        if (!dateDebut.isBefore(dateFin)) {
            throw new IllegalStateException(
                    "La date de début (" + dateDebut + ") doit précéder la date de fin (" + dateFin + ")");
        }
    }

    public Long getId() { return id; }

    public LocalDateTime getDateDebut() { return dateDebut; }
    public void setDateDebut(LocalDateTime dateDebut) { this.dateDebut = dateDebut; }

    public LocalDateTime getDateFin() { return dateFin; }
    public void setDateFin(LocalDateTime dateFin) { this.dateFin = dateFin; }

    public Utilisateur getUtilisateur() { return utilisateur; }
    public void setUtilisateur(Utilisateur utilisateur) { this.utilisateur = utilisateur; }

    public Salle getSalle() { return salle; }
    public void setSalle(Salle salle) { this.salle = salle; }

    @Override
    public String toString() {
        return "Reservation{id=" + id
                + ", salle=" + (salle != null ? salle.getNom() : "?")
                + ", du " + dateDebut + " au " + dateFin + "}";
    }
}

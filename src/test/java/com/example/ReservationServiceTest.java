package com.example;

import com.example.exception.GestionReservationException;
import com.example.exception.PeriodeInvalideException;
import com.example.exception.SalleIndisponibleException;
import com.example.model.Reservation;
import com.example.model.Salle;
import com.example.model.Utilisateur;
import com.example.service.ReservationService;
import com.example.util.JpaUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class ReservationServiceTest {

    private static final LocalDateTime DEBUT = LocalDateTime.of(2031, 5, 14, 10, 0);
    private static final LocalDateTime FIN = DEBUT.plusHours(2);

    private static ReservationService service;

    @BeforeClass
    public static void demarrer() {
        service = new ReservationService(JpaUtil.getEmf());
    }

    @AfterClass
    public static void arreter() {
        JpaUtil.fermer();
    }

    /** La base H2 est partagée entre les tests : on rend chaque valeur unique. */
    private static String unique(String prefixe) {
        return prefixe + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Utilisateur nouvelUtilisateur() {
        return service.creerUtilisateur("Testeur", unique("user") + "@example.com");
    }

    @Test
    public void reserverEnregistreLaReservation() {
        Utilisateur utilisateur = nouvelUtilisateur();
        Salle salle = service.creerSalle(unique("salle"), 10);

        Reservation reservation = service.reserver(utilisateur.getId(), salle.getId(), DEBUT, FIN);

        assertNotNull(reservation.getId());
        List<Reservation> reservations = service.reservationsDeUtilisateur(utilisateur.getId());
        assertEquals(1, reservations.size());
        assertEquals(salle.getNom(), reservations.get(0).getSalle().getNom());
    }

    @Test
    public void periodeInvalideEstRefusee() {
        Utilisateur utilisateur = nouvelUtilisateur();
        Salle salle = service.creerSalle(unique("salle"), 10);

        assertThrows(PeriodeInvalideException.class,
                () -> service.reserver(utilisateur.getId(), salle.getId(), FIN, DEBUT));
        assertThrows(PeriodeInvalideException.class,
                () -> service.reserver(utilisateur.getId(), salle.getId(), DEBUT, DEBUT));
        assertThrows(PeriodeInvalideException.class,
                () -> service.reserver(utilisateur.getId(), salle.getId(), null, FIN));
        assertTrue(service.reservationsDeUtilisateur(utilisateur.getId()).isEmpty());
    }

    @Test
    public void chevauchementEstRefuse() {
        Utilisateur utilisateur = nouvelUtilisateur();
        Salle salle = service.creerSalle(unique("salle"), 10);
        service.reserver(utilisateur.getId(), salle.getId(), DEBUT, FIN);

        assertThrows(SalleIndisponibleException.class, () ->
                service.reserver(utilisateur.getId(), salle.getId(), DEBUT.plusHours(1), FIN.plusHours(1)));
        assertEquals(1, service.reservationsDeUtilisateur(utilisateur.getId()).size());
    }

    @Test
    public void creneauxContigusSontAcceptes() {
        Utilisateur utilisateur = nouvelUtilisateur();
        Salle salle = service.creerSalle(unique("salle"), 10);

        service.reserver(utilisateur.getId(), salle.getId(), DEBUT, FIN);
        service.reserver(utilisateur.getId(), salle.getId(), FIN, FIN.plusHours(2));

        assertEquals(2, service.reservationsDeUtilisateur(utilisateur.getId()).size());
    }

    @Test
    public void sallesDisponiblesExclutLesSallesOccupees() {
        Utilisateur utilisateur = nouvelUtilisateur();
        Salle occupee = service.creerSalle(unique("occupee"), 10);
        Salle libre = service.creerSalle(unique("libre"), 10);
        service.reserver(utilisateur.getId(), occupee.getId(), DEBUT, FIN);

        List<Salle> pendant = service.sallesDisponibles(DEBUT.plusHours(1), FIN.plusHours(1));
        assertTrue(pendant.stream().anyMatch(s -> s.getId().equals(libre.getId())));
        assertTrue(pendant.stream().noneMatch(s -> s.getId().equals(occupee.getId())));

        List<Salle> apres = service.sallesDisponibles(FIN.plusHours(5), FIN.plusHours(6));
        assertTrue(apres.stream().anyMatch(s -> s.getId().equals(occupee.getId())));
        assertFalse(service.estDisponible(occupee.getId(), DEBUT, FIN));
    }

    @Test
    public void sallesAvecEquipementReutiliseLesEquipementsExistants() {
        String visio = unique("visio");
        String tableau = unique("tableau");
        Salle premiere = service.creerSalle(unique("s1"), 10, visio, tableau);
        // "tableau" existe déjà : il doit être réutilisé, sinon la contrainte d'unicité échouerait.
        Salle seconde = service.creerSalle(unique("s2"), 5, tableau);

        List<Salle> avecVisio = service.sallesAvecEquipement(visio);
        assertEquals(1, avecVisio.size());
        assertEquals(premiere.getNom(), avecVisio.get(0).getNom());

        List<Salle> avecTableau = service.sallesAvecEquipement(tableau);
        assertEquals(2, avecTableau.size());
        assertTrue(avecTableau.stream().anyMatch(s -> s.getNom().equals(seconde.getNom())));
    }

    @Test
    public void annulerReservationLaSupprimeDeLaBase() {
        Utilisateur utilisateur = nouvelUtilisateur();
        Salle salle = service.creerSalle(unique("salle"), 10);
        Reservation reservation = service.reserver(utilisateur.getId(), salle.getId(), DEBUT, FIN);

        service.annulerReservation(reservation.getId());

        assertTrue(service.reservationsDeUtilisateur(utilisateur.getId()).isEmpty());
        assertTrue(service.estDisponible(salle.getId(), DEBUT, FIN));
    }

    @Test
    public void supprimerUtilisateurConserveSalleEtEquipement() {
        String equipement = unique("projecteur");
        Utilisateur utilisateur = nouvelUtilisateur();
        Salle salle = service.creerSalle(unique("salle"), 10, equipement);
        service.reserver(utilisateur.getId(), salle.getId(), DEBUT, FIN);

        service.supprimerUtilisateur(utilisateur.getId());

        assertTrue(service.reservationsDeUtilisateur(utilisateur.getId()).isEmpty());
        assertFalse(service.trouverUtilisateurParEmail(utilisateur.getEmail()).isPresent());
        assertEquals(1, service.sallesAvecEquipement(equipement).size());
        assertTrue(service.estDisponible(salle.getId(), DEBUT, FIN));
    }

    @Test
    public void emailEnDoubleDeclencheUneErreurSansBloquerLeService() {
        String email = unique("doublon") + "@example.com";
        service.creerUtilisateur("Premier", email);

        assertThrows(GestionReservationException.class, () -> service.creerUtilisateur("Second", email));

        // La transaction a été annulée proprement : le service reste utilisable.
        assertNotNull(service.creerUtilisateur("Autre", unique("autre") + "@example.com").getId());
    }
}

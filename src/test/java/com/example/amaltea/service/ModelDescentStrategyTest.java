package com.example.amaltea.service;

import com.example.amaltea.model.LlmRole;
import com.example.amaltea.model.catalog.SlmCatalog;
import com.example.amaltea.model.catalog.SlmModel;
import com.example.amaltea.model.process.ConfigurationTrial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.example.amaltea.service.DescentFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the search strategy.
 * <p>
 * This is the one piece of non-trivial logic in AMALTEA, and the one whose failure would be
 * silent: an off-by-one in the interval arithmetic would return a wrong threshold without
 * raising anything. The campaign would run, produce numbers, and those numbers would end up
 * in the thesis. Since the strategy needs no infrastructure — only a ladder and a record of
 * what passed — there is no reason to leave it unverified.
 * <p>
 * The ladder used throughout is {@code 64, 32, 16, 8, 4, 2, 1} billion, largest first, so
 * index 0 is the largest model and index 6 the smallest.
 */
class ModelDescentStrategyTest {

    private static final String FAMILY = "acme";
    private final ModelDescentStrategy strategy = new ModelDescentStrategy();

    private SlmCatalog catalog() {
        return ladder(FAMILY, 64, 32, 16, 8, 4, 2, 1);
    }

    // =====================================================================
    @Nested
    @DisplayName("Ricerca binaria uniforme")
    class UniformSearch {

        @Test
        @DisplayName("senza storico parte dal centro della scala, non dall'estremo")
        void startsFromTheMiddle() {
            Optional<SlmModel> first = strategy.nextUniformCandidate(catalog(), FAMILY, List.of());

            assertTrue(first.isPresent());
            // Starting from an end would make the search linear and cost the hours the
            // binary search exists to save.
            assertEquals(catalog().ladderFor(FAMILY).get(3), first.get(),
                    "il primo candidato deve essere il mediano della scala");
        }

        @Test
        @DisplayName("un successo sposta la ricerca verso i modelli più piccoli")
        void aPassMovesDownwards() {
            SlmCatalog catalog = catalog();
            SlmModel middle = catalog.ladderFor(FAMILY).get(3);

            Optional<SlmModel> next = strategy.nextUniformCandidate(
                    catalog, FAMILY, List.of(uniformTrial(middle, true)));

            assertTrue(next.isPresent());
            assertTrue(next.get().totalParameterCount() < middle.totalParameterCount(),
                    "dopo un successo si deve provare un modello più piccolo");
        }

        @Test
        @DisplayName("un fallimento sposta la ricerca verso i modelli più grandi")
        void aFailureMovesUpwards() {
            SlmCatalog catalog = catalog();
            SlmModel middle = catalog.ladderFor(FAMILY).get(3);

            Optional<SlmModel> next = strategy.nextUniformCandidate(
                    catalog, FAMILY, List.of(uniformTrial(middle, false)));

            assertTrue(next.isPresent());
            assertTrue(next.get().totalParameterCount() > middle.totalParameterCount(),
                    "dopo un fallimento si deve risalire");
        }

        @Test
        @DisplayName("la ricerca converge e si ferma, invece di proporre all'infinito")
        void terminates() {
            SlmCatalog catalog = catalog();
            List<ConfigurationTrial> history = new ArrayList<>();

            // The real termination guarantee: without it the orchestrator would loop
            // forever, provisioning machines each time.
            int guard = 0;
            Optional<SlmModel> candidate;
            while ((candidate = strategy.nextUniformCandidate(catalog, FAMILY, history)).isPresent()) {
                boolean passes = candidate.get().totalParameterCount() >= 8_000_000_000L;
                history.add(uniformTrial(candidate.get(), passes));
                assertTrue(++guard < 20, "la ricerca non converge");
            }
            assertTrue(guard <= 3, "con sette taglie la binaria non deve costare più di tre trial");
        }

        @Test
        @DisplayName("individua la soglia corretta su una scala monotòna")
        void findsTheThreshold() {
            SlmCatalog catalog = catalog();
            List<ConfigurationTrial> history = simulate(catalog, 8_000_000_000L);

            Optional<SlmModel> threshold =
                    strategy.thresholdFor(catalog, FAMILY, history, LlmRole.REQUIREMENTS_AGENT);

            assertTrue(threshold.isPresent());
            assertEquals(8_000_000_000L, threshold.get().totalParameterCount(),
                    "la soglia è il modello più piccolo che ha superato le verifiche");
        }

        @Test
        @DisplayName("nessuna soglia se anche il modello più grande fallisce")
        void noThresholdWhenEverythingFails() {
            SlmCatalog catalog = catalog();
            List<ConfigurationTrial> history = simulate(catalog, Long.MAX_VALUE);

            // A negative result is still a valid result, and must not be reported as if
            // some model had worked.
            assertTrue(strategy.thresholdFor(catalog, FAMILY, history, LlmRole.LATEX_REPORT)
                    .isEmpty());
        }

        @Test
        @DisplayName("i trial senza verdetto vengono ignorati")
        void ignoresTrialsWithoutVerdict() {
            SlmCatalog catalog = catalog();
            SlmModel middle = catalog.ladderFor(FAMILY).get(3);

            Optional<SlmModel> withPending = strategy.nextUniformCandidate(
                    catalog, FAMILY, List.of(pendingTrial(middle)));
            Optional<SlmModel> withNothing =
                    strategy.nextUniformCandidate(catalog, FAMILY, List.of());

            // A trial still running says nothing yet. Treating it as a failure would
            // narrow the interval on evidence that does not exist.
            assertEquals(withNothing, withPending);
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Passo di conferma")
    class Confirmation {

        @Test
        @DisplayName("propone il modello sotto la soglia quando la binaria non l'ha già coperto")
        void proposesTheModelBelowTheThreshold() {
            SlmCatalog catalog = catalog();
            List<SlmModel> ladder = catalog.ladderFor(FAMILY);

            // Storico minimo e già convergiuto: 8B passa, 4B fallisce, e nulla sotto è
            // stato provato. È il caso in cui la conferma serve davvero.
            List<ConfigurationTrial> history = List.of(
                    uniformTrial(ladder.get(3), true),
                    uniformTrial(ladder.get(4), false));

            Optional<SlmModel> confirmation =
                    strategy.confirmationCandidate(catalog, FAMILY, history);

            assertTrue(confirmation.isPresent(),
                    "la monotonicità va messa alla prova, non data per buona");
            assertEquals(ladder.get(5), confirmation.get(),
                    "la conferma si esegue sul modello immediatamente sotto quello fallito");
        }

        @Test
        @DisplayName("nessuna conferma se la binaria ha già valutato sotto la soglia")
        void noConfirmationWhenTheSearchAlreadyWentBelow() {
            SlmCatalog catalog = catalog();
            List<ConfigurationTrial> history = simulate(catalog, 8_000_000_000L);

            SlmModel threshold = strategy
                    .thresholdFor(catalog, FAMILY, history, LlmRole.REQUIREMENTS_AGENT)
                    .orElseThrow();

            boolean evidenceBelow = history.stream()
                    .filter(t -> t.verdict() != null && !t.verdict().passed())
                    .flatMap(t -> t.modelAssignment().values().stream())
                    .anyMatch(m -> m.totalParameterCount() < threshold.totalParameterCount());

            // La ricerca binaria attraversa la scala, quindi capita spesso che abbia già
            // valutato un modello sotto la soglia. In quel caso l'evidenza esiste e un
            // trial in più sarebbe speso per nulla — ore di macchina, non un dettaglio.
            assertTrue(evidenceBelow,
                    "la binaria ha già prodotto evidenza sotto la soglia");
            assertTrue(strategy.confirmationCandidate(catalog, FAMILY, history).isEmpty(),
                    "non va speso un trial per confermare ciò che è già stato osservato");
        }

        @Test
        @DisplayName("non propone nulla mentre la binaria è ancora in corso")
        void staysQuietDuringTheSearch() {
            assertTrue(strategy.confirmationCandidate(catalog(), FAMILY, List.of()).isEmpty());
        }

        @Test
        @DisplayName("tace una volta che la conferma è stata eseguita")
        void staysQuietOnceConfirmed() {
            SlmCatalog catalog = catalog();
            List<SlmModel> ladder = catalog.ladderFor(FAMILY);

            List<ConfigurationTrial> history = new ArrayList<>(List.of(
                    uniformTrial(ladder.get(3), true),
                    uniformTrial(ladder.get(4), false)));

            SlmModel confirmation = strategy.confirmationCandidate(catalog, FAMILY, history)
                    .orElseThrow();
            history.add(uniformTrial(confirmation, false));

            // I limiti non registrano che la conferma è avvenuta: `hi` traccia il
            // fallimento meno profondo, quindi uno più profondo lo lascia dov'era. Senza
            // un controllo esplicito lo stesso candidato tornerebbe all'infinito, e il
            // chiamante provisionerebbe una macchina a ogni iterazione.
            assertTrue(strategy.confirmationCandidate(catalog, FAMILY, history).isEmpty(),
                    "la conferma è un trial solo: riproporla significherebbe non terminare");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Verifica della monotonicità")
    class Monotonicity {

        @Test
        @DisplayName("non segnala nulla su una scala che si comporta bene")
        void silentWhenMonotonic() {
            SlmCatalog catalog = catalog();
            List<ConfigurationTrial> history = simulate(catalog, 8_000_000_000L);

            assertFalse(strategy.monotonicityViolated(catalog, FAMILY, history));
        }

        @Test
        @DisplayName("segnala quando un modello più piccolo passa dove uno più grande ha fallito")
        void detectsViolation() {
            SlmCatalog catalog = catalog();
            List<SlmModel> ladder = catalog.ladderFor(FAMILY);

            List<ConfigurationTrial> history = List.of(
                    uniformTrial(ladder.get(2), false),  // 16B fallisce
                    uniformTrial(ladder.get(5), true));  // 2B passa

            // The confirmation step exists to surface exactly this. Without a way to read
            // its outcome the extra trial would be spent and the finding lost.
            assertTrue(strategy.monotonicityViolated(catalog, FAMILY, history),
                    "una violazione è un risultato da riportare, non un'anomalia da ignorare");
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Raffinamento per ruolo")
    class Refinement {

        @Test
        @DisplayName("non risale mai sopra un modello che per quel ruolo ha già funzionato")
        void neverRegresses() {
            SlmCatalog catalog = catalog();
            List<SlmModel> ladder = catalog.ladderFor(FAMILY);
            SlmModel small = ladder.get(5); // 2B

            List<ConfigurationTrial> history = List.of(uniformTrial(small, true));

            Optional<Map<LlmRole, SlmModel>> next =
                    strategy.nextRefinement(catalog, FAMILY, history);

            next.ifPresent(assignment -> assignment.forEach((role, model) ->
                    assertTrue(model.totalParameterCount() <= small.totalParameterCount(),
                            "il ruolo %s è risalito a %s dopo che %s aveva funzionato"
                                    .formatted(role, model.name(), small.name()))));
        }

        @Test
        @DisplayName("un'assegnazione mista restringe l'intervallo dei singoli ruoli")
        void mixedAssignmentsNarrowPerRole() {
            SlmCatalog catalog = catalog();
            List<SlmModel> ladder = catalog.ladderFor(FAMILY);
            SlmModel base = ladder.get(3);  // 8B
            SlmModel tiny = ladder.get(6);  // 1B

            // Only the LaTeX role is lowered, and the trial fails: the evidence is about
            // that role, not about the five that stayed where they were.
            List<ConfigurationTrial> history = List.of(
                    uniformTrial(base, true),
                    mixedTrial(assignmentWith(base, LlmRole.LATEX_REPORT, tiny), false));

            Optional<SlmModel> latexThreshold =
                    strategy.thresholdFor(catalog, FAMILY, history, LlmRole.LATEX_REPORT);

            assertTrue(latexThreshold.isPresent());
            assertEquals(base.totalParameterCount(), latexThreshold.get().totalParameterCount(),
                    "il ruolo colpevole deve restare alla taglia che funzionava");
        }

        @Test
        @DisplayName("il raffinamento termina")
        void terminates() {
            SlmCatalog catalog = catalog();
            List<ConfigurationTrial> history = new ArrayList<>(simulate(catalog, 8_000_000_000L));

            int guard = 0;
            Optional<Map<LlmRole, SlmModel>> next;
            while ((next = strategy.nextRefinement(catalog, FAMILY, history)).isPresent()) {
                history.add(mixedTrial(next.get(), false));
                assertTrue(++guard < 50, "il raffinamento non converge");
            }
            assertTrue(strategy.shouldStop(catalog, FAMILY, history));
        }
    }

    // =====================================================================
    @Nested
    @DisplayName("Selezione delle famiglie e dei MoE")
    class CatalogueBehaviour {

        @Test
        @DisplayName("una famiglia con meno di quattro taglie non è un asse su cui scendere")
        void shortLaddersAreDropped() {
            SlmCatalog mixed = new SlmCatalog(
                    List.of(dense("wide", 32), dense("wide", 16), dense("wide", 8),
                            dense("wide", 4),
                            dense("narrow", 8), dense("narrow", 4)),
                    java.time.LocalDateTime.now(), criteria(4));

            assertEquals(List.of("wide"), mixed.descendableFamilies(),
                    "una famiglia con due sole taglie è un punto isolato, non una scala");
        }

        @Test
        @DisplayName("i MoE restano fuori dalla scala principale")
        void mixtureOfExpertsStaysOut() {
            SlmCatalog mixed = new SlmCatalog(
                    List.of(dense(FAMILY, 32), dense(FAMILY, 16), dense(FAMILY, 8),
                            dense(FAMILY, 4), mixtureOfExperts(FAMILY, 47, 13)),
                    java.time.LocalDateTime.now(), criteria(4));

            assertTrue(mixed.ladderFor(FAMILY).stream().noneMatch(SlmModel::mixtureOfExperts),
                    "il MoE va provato come esperimento mirato, non inserito nella scala");
            assertNotNull(mixed.mixtureOfExpertsNear(16),
                    "deve però restare raggiungibile per l'esperimento di fase 4");
        }
    }

    // =====================================================================

    /**
     * Runs a full uniform search against a ladder where everything at or above
     * {@code passesAtOrAbove} succeeds.
     */
    private List<ConfigurationTrial> simulate(SlmCatalog catalog, long passesAtOrAbove) {
        List<ConfigurationTrial> history = new ArrayList<>();
        Optional<SlmModel> candidate;
        int guard = 0;
        while ((candidate = strategy.nextUniformCandidate(catalog, FAMILY, history)).isPresent()
                && guard++ < 20) {
            history.add(uniformTrial(candidate.get(),
                    candidate.get().totalParameterCount() >= passesAtOrAbove));
        }
        return history;
    }
}

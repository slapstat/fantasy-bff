package com.fantasy.bff.mapper;

import com.fantasy.bff.dto.response.LeagueProjectionSettingsResponse;
import com.fantasy.bff.dto.response.ScoringBasis;
import com.fantasy.bff.dto.response.RosterSlots;
import com.fantasy.bff.generated.espn.model.LeagueSettingsResponse;
import com.fantasy.bff.generated.espn.model.RosterSlot;
import com.fantasy.bff.generated.espn.model.StatCategory;
import com.fantasy.bff.service.InjuredReserve;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Translates an ESPN league's settings into the projection-domain shape the web applies —
 * the ESPN counterpart to {@link YahooLeagueSettingsMapper}, reusing {@link StatKey} and the
 * same {@link LeagueProjectionSettingsResponse} output.
 *
 * <p>ESPN's scoringType bundles the matchup format and the scoring basis (e.g. H2H_POINTS,
 * H2H_CATEGORY, ROTO). Only the basis affects player valuation, so it collapses to points vs
 * category: a "POINT" type is points, a "CATEGOR"/"ROTO" type is category. Unrecognised codes
 * fall back to per-stat point-value presence (points leagues carry non-zero weights, category
 * ones don't).
 */
@Component
public class EspnLeagueSettingsMapper {

    /**
     * ESPN hockey (fhl) stat id -> projection stat. ESPN publishes no glossary for these ids,
     * so every entry was read off real season lines (a known player's totals identify the stat
     * unambiguously). Both games-played ids are mapped: 30 is the universal one ESPN reports
     * for goalies too, 34 is skater-only.
     */
    private static final Map<Integer, StatKey> STAT_ID_TO_KEY = Map.ofEntries(
            Map.entry(30, StatKey.GP), Map.entry(34, StatKey.GP),
            Map.entry(27, StatKey.TOI_PER_GAME),
            Map.entry(13, StatKey.GOALS), Map.entry(14, StatKey.ASSISTS),
            Map.entry(16, StatKey.POINTS),
            Map.entry(15, StatKey.PLUS_MINUS), Map.entry(17, StatKey.PIM),
            Map.entry(18, StatKey.PPG), Map.entry(19, StatKey.PPA),
            Map.entry(20, StatKey.SHG), Map.entry(21, StatKey.SHA), Map.entry(22, StatKey.GWG),
            Map.entry(38, StatKey.PPP), Map.entry(39, StatKey.SHP),
            Map.entry(35, StatKey.STPG), Map.entry(36, StatKey.STPA), Map.entry(37, StatKey.STP),
            Map.entry(28, StatKey.HAT_TRICKS), Map.entry(25, StatKey.SHIFTS),
            Map.entry(33, StatKey.DEF_POINTS),
            Map.entry(26, StatKey.TOI), Map.entry(8, StatKey.TOI),
            Map.entry(29, StatKey.SOG), Map.entry(23, StatKey.FW), Map.entry(24, StatKey.FL),
            Map.entry(31, StatKey.HITS), Map.entry(32, StatKey.BLOCKS),
            Map.entry(0, StatKey.GS), Map.entry(1, StatKey.W), Map.entry(2, StatKey.L),
            Map.entry(9, StatKey.OTL),
            Map.entry(7, StatKey.SHO), Map.entry(3, StatKey.SA), Map.entry(6, StatKey.SV),
            Map.entry(4, StatKey.GA), Map.entry(10, StatKey.GAA), Map.entry(11, StatKey.SV_PCT),
            Map.entry(12, StatKey.WIN_PCT)
    );

    /** ESPN roster slot code -> projection roster slot. F is the forward flex (C, LW or RW). */
    private static final Map<String, Slot> POSITION_TO_SLOT = Map.of(
            "C", Slot.C, "LW", Slot.LW, "RW", Slot.RW, "F", Slot.F, "D", Slot.D, "G", Slot.G,
            "BN", Slot.BN, "UTIL", Slot.UTIL
    );

    /** Non-active ESPN slots that don't belong in a draft roster. */
    private static final Set<String> IGNORED_POSITION_CODES = Set.of("IR");

    private enum Slot {
        C, LW, RW, F, D, UTIL, BN, G
    }

    public LeagueProjectionSettingsResponse toProjectionSettings(LeagueSettingsResponse settings) {
        ScoringBasis scoringType = scoringBasis(settings);

        List<String> activeScoringColumns = new ArrayList<>();
        List<String> activeUtilityColumns = new ArrayList<>();
        List<String> unsupportedStats = new ArrayList<>();
        Map<String, Double> scoredWeights = new LinkedHashMap<>();

        for (StatCategory category : settings.getStatCategories()) {
            StatKey statKey = STAT_ID_TO_KEY.get(category.getStatId());
            if (scoringType == ScoringBasis.POINTS && scoresNothing(category)
                    && (statKey == null || !statKey.isUtility())) {
                continue;
            }
            if (statKey == null) {
                unsupportedStats.add(category.getName());
                continue;
            }
            String key = statKey.key();
            if (statKey.isUtility()) {
                if (!activeUtilityColumns.contains(key)) {
                    activeUtilityColumns.add(key);
                }
                continue;
            }
            if (!activeScoringColumns.contains(key)) {
                activeScoringColumns.add(key);
            }
            if (scoringType == ScoringBasis.POINTS && category.getPointValue() != null) {
                scoredWeights.put(key, category.getPointValue());
            }
        }

        if (!activeUtilityColumns.contains(StatKey.GP.key())) {
            activeUtilityColumns.addFirst(StatKey.GP.key());
        }

        RosterMapping roster = mapRoster(settings.getRosterPositions());

        Map<String, Double> statWeights = scoringType == ScoringBasis.POINTS ? buildWeights(scoredWeights) : null;

        return new LeagueProjectionSettingsResponse(
                scoringType,
                activeScoringColumns,
                activeUtilityColumns,
                statWeights,
                settings.getName(),
                roster.rosterSlots(),
                clampLeagueSize(settings.getSize()).orElse(null),
                unsupportedStats,
                roster.unsupported(),
                roster.reserveSlots(),
                null
        );
    }

    private ScoringBasis scoringBasis(LeagueSettingsResponse settings) {
        String scoringType = settings.getScoringType();
        String code = scoringType == null ? "" : scoringType.trim().toUpperCase(Locale.ROOT);
        if (code.contains("POINT")) {
            return ScoringBasis.POINTS;
        }
        if (code.contains("CATEGOR") || code.contains("ROTO")) {
            return ScoringBasis.CATEGORY;
        }
        boolean hasWeights = settings.getStatCategories().stream()
                .anyMatch(category -> category.getPointValue() != null && !scoresNothing(category));
        return hasWeights ? ScoringBasis.POINTS : ScoringBasis.CATEGORY;
    }

    /**
     * ESPN lists display-only stats among a points league's scoringItems at 0 points (a real
     * H2H points league listed +/-, P, PPG, PPA, SHG, SHA, FW, L and SA that way). They score
     * nothing, so they are not league stats: no column, no weight, no unsupported warning.
     */
    private static boolean scoresNothing(StatCategory category) {
        Double points = category.getPointValue();
        return points != null && points == 0.0;
    }

    private record RosterMapping(
            RosterSlots rosterSlots, List<String> unsupported, Map<String, Integer> reserveSlots) {
    }

    private RosterMapping mapRoster(List<RosterSlot> positions) {
        Map<Slot, Integer> counts = new EnumMap<>(Slot.class);
        for (Slot slot : Slot.values()) {
            counts.put(slot, 0);
        }
        List<String> unsupported = new ArrayList<>();
        Map<String, Integer> reserveSlots = new LinkedHashMap<>();
        for (RosterSlot position : positions) {
            String raw = position.getPosition();
            String code = raw.toUpperCase(Locale.ROOT);
            int count = position.getCount();
            if (InjuredReserve.isSlot(code) && count > 0) {
                // No lineup slot, but room off the roster for an injured player.
                reserveSlots.merge(code, count, Integer::sum);
            }
            if (IGNORED_POSITION_CODES.contains(code)) {
                unsupported.add(raw);
                continue;
            }
            Slot slot = POSITION_TO_SLOT.get(code);
            if (slot == null) {
                counts.merge(Slot.UTIL, count, Integer::sum);
                unsupported.add(raw);
                continue;
            }
            counts.merge(slot, count, Integer::sum);
        }
        RosterSlots slots = new RosterSlots(
                counts.get(Slot.C), counts.get(Slot.LW), counts.get(Slot.RW), 0, counts.get(Slot.F),
                counts.get(Slot.D), counts.get(Slot.UTIL), counts.get(Slot.BN), counts.get(Slot.G));
        return new RosterMapping(slots, unsupported, reserveSlots);
    }

    private Map<String, Double> buildWeights(Map<String, Double> scored) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (StatKey statKey : StatKey.values()) {
            if (!statKey.isUtility()) {
                weights.put(statKey.key(), scored.getOrDefault(statKey.key(), 0.0));
            }
        }
        return weights;
    }

    private Optional<Integer> clampLeagueSize(Integer numTeams) {
        return Optional.ofNullable(numTeams).map(teams -> Math.clamp(teams, 2, 30));
    }
}

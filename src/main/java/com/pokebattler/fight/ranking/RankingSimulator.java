package com.pokebattler.fight.ranking;

import static com.pokebattler.fight.data.proto.PokemonIdOuterClass.PokemonId.DRAGONITE;
import static com.pokebattler.fight.data.proto.PokemonIdOuterClass.PokemonId.LAPRAS;
import static com.pokebattler.fight.data.proto.PokemonIdOuterClass.PokemonId.SNORLAX;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pokebattler.fight.calculator.AttackSimulator;
import com.pokebattler.fight.data.MoveRepository;
import com.pokebattler.fight.data.PokemonDataCreator;
import com.pokebattler.fight.data.PokemonRepository;
import com.pokebattler.fight.data.proto.FightOuterClass.AttackStrategyType;
import com.pokebattler.fight.data.proto.FightOuterClass.CombatantResultOrBuilder;
import com.pokebattler.fight.data.proto.FightOuterClass.Fight;
import com.pokebattler.fight.data.proto.FightOuterClass.FightResult;
import com.pokebattler.fight.data.proto.PokemonDataOuterClass.PokemonData;
import com.pokebattler.fight.data.proto.PokemonIdOuterClass.PokemonId;
import com.pokebattler.fight.data.proto.PokemonOuterClass.Pokemon;
import com.pokebattler.fight.data.proto.Ranking.AttackerResult;
import com.pokebattler.fight.data.proto.Ranking.AttackerResult.Builder;
import com.pokebattler.fight.data.proto.Ranking.AttackerSubResult;
import com.pokebattler.fight.data.proto.Ranking.DefenderResult;
import com.pokebattler.fight.data.proto.Ranking.DefenderSubResult;
import com.pokebattler.fight.data.proto.Ranking.DefenderSubResultOrBuilder;
import com.pokebattler.fight.data.proto.Ranking.RankingResult;
import com.pokebattler.fight.data.proto.Ranking.SubResultTotal;
import com.pokebattler.fight.data.proto.Ranking.SubResultTotalOrBuilder;

@Service
public class RankingSimulator {
    @Resource
    AttackSimulator simulator;
    @Resource
    PokemonRepository pokemonRepository;
    @Resource
    MoveRepository moveRepository;

    Logger log = LoggerFactory.getLogger(getClass());

    public RankingResult rank(RankingParams params) {
        final RankingResult.Builder retval = RankingResult.newBuilder().setAttackStrategy(params.getAttackStrategy())
                .setDefenseStrategy(params.getDefenseStrategy());
        final List<AttackerResult.Builder> results = params.getFilter().getAttackers(pokemonRepository).parallelStream().map((attacker) -> {
            Builder result = rankAttacker(attacker, params);
            return result;
        }).collect(Collectors.toList());
        
        //TODO reuse comparators
        results.stream()
        .sorted(params.getSort().getAttackerResultComparator())
                .forEach((result) -> retval.addAttackers(result));        
        return retval.build();
    }


    public AttackerResult.Builder rankAttacker(Pokemon attacker, RankingParams params) {
        final ArrayList<AttackerSubResult.Builder> results = new ArrayList<>();
        final AttackerResult.Builder retval = AttackerResult.newBuilder().setPokemonId(attacker.getPokemonId());
        attacker.getMovesetsList().forEach((moveset) -> {
            final PokemonData attackerData = params.getAttackerCreator().createPokemon(attacker.getPokemonId(), 
            		moveset.getQuickMove(), moveset.getCinematicMove());
            if (attackerData != null) {
	            // yes we set this a few times its ok
	            retval.setCp(attackerData.getCp());
	            results.add(rankAttackerByMoves(attackerData, params));
            }
        });
        results.stream()
        .sorted(params.getSort().getAttackerSubResultComparator())
        .forEach(result -> {
            // defender list is too big sometimes
        	if (params.getFilter().compressResults()) {
        		result.clearDefenders();
        	}
            retval.addByMove(result);
        });;
        
        return retval;
    }

    public AttackerSubResult.Builder rankAttackerByMoves(PokemonData attackerData, RankingParams params) {
        final AttackerSubResult.Builder retval = AttackerSubResult.newBuilder().setMove1(attackerData.getMove1())
                .setMove2(attackerData.getMove2());
        final ArrayList<DefenderResult.Builder> results = new ArrayList<>();
        
        
        params.getFilter().getDefenders(pokemonRepository).stream().forEach((defender) -> {
            final DefenderResult.Builder rankDefender = subRankDefender(defender, attackerData, params);
            if (rankDefender.getTotal().getCombatTime() > 0) {
	            // only retain the subtotals
	        	if (params.getFilter().compressResults()) {
		            DefenderSubResult.Builder bestMove = rankDefender.getByMoveBuilder(0);
		            rankDefender.clearByMove();
		            rankDefender.addByMove(bestMove);
	        	}
	            results.add(rankDefender);
            }
        });
        // sort by winner first then damagetaken then damage dealt for tie breaker (unlikely)
        SubResultTotal.Builder subTotal = SubResultTotal.newBuilder();
        results.stream()
                .sorted(params.getSort().getDefenderResultComparator())
                .skip(Math.max(0, results.size() - params.getFilter().getNumWorstDefenderToKeep()))
                .forEach(result->  {
                    SubResultTotal.Builder subResultTotalBuilder = result.getTotalBuilder();
                    subTotal.setNumWins(subTotal.getNumWins() + subResultTotalBuilder.getNumWins());
                    subTotal.setCombatTime(subTotal.getCombatTime() + subResultTotalBuilder.getCombatTime());
                    subTotal.setDamageDealt(subTotal.getDamageDealt() + subResultTotalBuilder.getDamageDealt());
                    subTotal.setDamageTaken(subTotal.getDamageTaken() + subResultTotalBuilder.getDamageTaken());
                    subTotal.setNumLosses(subTotal.getNumLosses() + subResultTotalBuilder.getNumLosses());
                    subTotal.setPower(subTotal.getPower() + subResultTotalBuilder.getPower());
                    subTotal.setEffectiveCombatTime(subTotal.getEffectiveCombatTime() + subResultTotalBuilder.getEffectiveCombatTime());
                    subTotal.setPotions(subTotal.getPotions() + subResultTotalBuilder.getPotions());
                	retval.addDefenders(result);
                });
        
        // normalize power
        subTotal.setPower( Math.pow(10, subTotal.getPower()/(subTotal.getNumWins() + subTotal.getNumLosses())));
        retval.setTotal(subTotal );
        return retval;
    }
    public DefenderResult.Builder subRankDefender(Pokemon defender, PokemonData attackerData, RankingParams params) {
        final DefenderResult.Builder retval = DefenderResult.newBuilder().setPokemonId(defender.getPokemonId());
        final ArrayList<DefenderSubResult.Builder> results = new ArrayList<>();
        
        defender.getMovesetsList().forEach((moveset) -> {
            final PokemonData defenderData = params.getDefenderCreator().createPokemon(defender.getPokemonId(),
            		moveset.getQuickMove(), moveset.getCinematicMove());
            if (defenderData != null) {
	                // yes we set this a few times its ok
	            retval.setCp(defenderData.getCp());
	            results.add(subRankDefenderByMoves(attackerData, defenderData, params.getAttackStrategy(), params.getDefenseStrategy()));
            }
        });
        
        SubResultTotal.Builder subTotal = SubResultTotal.newBuilder();
        results.stream()
                .sorted(params.getSort().getDefenderSubResultComparator())
                // skip all but 1 if we are skipping any
                .skip((params.getFilter().getNumWorstSubDefenderToKeep() == Integer.MAX_VALUE)? 0: Math.max(0, results.size() - 1))
                .forEach((result) -> {
                    boolean win =result.getResultOrBuilder().getWin();
                    subTotal.setNumWins(subTotal.getNumWins() + (win?1:0));
                    subTotal.setNumLosses(subTotal.getNumLosses() + (win?0:1));
                    subTotal.setCombatTime(subTotal.getCombatTime() + result.getResultOrBuilder().getTotalCombatTime());
                    subTotal.setDamageDealt(subTotal.getDamageDealt() + result.getResultOrBuilder().getCombatantsOrBuilder(0).getDamageDealt());
                    subTotal.setDamageTaken(subTotal.getDamageTaken() + result.getResultOrBuilder().getCombatantsOrBuilder(1).getDamageDealt());
                    subTotal.setPower(subTotal.getPower() + result.getResultOrBuilder().getPowerLog());
                    subTotal.setEffectiveCombatTime(subTotal.getEffectiveCombatTime() + result.getResultOrBuilder().getEffectiveCombatTime());
                    subTotal.setPotions(subTotal.getPotions() + result.getResultOrBuilder().getPotions());
                	if (params.getFilter().compressResults()) {
                        // reduce the json size a ton
	                    result.clearResult();
                	} else {
                		// reduce the json size a bit
                		result.getResultBuilder().clearPowerLog();
                		result.getResultBuilder().getCombatantsBuilder(0).clearCp().clearDps().clearEndHp().clearStartHp();
                		result.getResultBuilder().getCombatantsBuilder(1).clearCp().clearDps().clearEndHp().clearStartHp();
                		
                	}
                    retval.addByMove(result);   
                });
        retval.setTotal(subTotal);
        return retval;
    }



    public DefenderSubResult.Builder subRankDefenderByMoves(PokemonData attackerData, PokemonData defenderData,
            AttackStrategyType attackStrategy, AttackStrategyType defenseStrategy) {
        Fight fight = Fight.newBuilder().setAttacker1(attackerData).setDefender(defenderData).setStrategy(attackStrategy)
                .setDefenseStrategy(defenseStrategy).build();      
        final FightResult.Builder result = simulator.fight(fight, false);
        result.clearCombatResult().clearFightParameters();
        result.getCombatantsBuilder(0).clearStrategy().clearCombatTime().clearPokemon()
                .clearEnergy().clearCombatant();
        result.getCombatantsBuilder(1).clearStrategy().clearCombatTime().clearPokemon()
                .clearEnergy().clearCombatant();
        DefenderSubResult.Builder retval = DefenderSubResult.newBuilder().setMove1(defenderData.getMove1()).setMove2(defenderData.getMove2())
                .setResult(result);
        return retval;
    }

}

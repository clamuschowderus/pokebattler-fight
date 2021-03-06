package com.pokebattler.fight.ranking;

import com.pokebattler.fight.data.PokemonDataCreator;
import com.pokebattler.fight.data.proto.PokemonDataOuterClass.PokemonData;
import com.pokebattler.fight.data.proto.PokemonIdOuterClass.PokemonId;
import com.pokebattler.fight.data.proto.PokemonMoveOuterClass.PokemonMove;

public class ExactStatPokemonCreator implements PokemonCreator {
	private final String level;
	private final PokemonDataCreator creator;
	private final int attack;
	private final int defense;
	private final int stamina;
	
	public ExactStatPokemonCreator(PokemonDataCreator creator, String level) {
		this(creator, level, 15, 15, 15);
	}
	public ExactStatPokemonCreator(PokemonDataCreator creator, String level, int attack, int defense, int stamina) {
		this.level = level;
		this.creator = creator;
		this.attack = attack;
		this.defense = defense;
		this.stamina = stamina;
	}

	@Override
	public PokemonData createPokemon(PokemonId pokemon, PokemonMove quickMove, PokemonMove cinematicMove) {
		return creator.createPokemon(pokemon, level, attack, defense, stamina, quickMove, cinematicMove);
	}

	@Override
	public int hashCode() {
		return level.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ExactStatPokemonCreator other = (ExactStatPokemonCreator) obj;
		if (level == null) {
			if (other.level != null)
				return false;
		} else if (!level.equals(other.level))
			return false;
		return true;
	}

}

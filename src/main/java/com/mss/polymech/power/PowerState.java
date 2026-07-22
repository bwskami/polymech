package com.mss.polymech.power;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class PowerState extends SavedData {

    public int storedEnergy = 0;

    public PowerState() {}

    public static PowerState fromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        PowerState state = new PowerState();
        state.storedEnergy = tag.getInt("storedEnergy");
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("storedEnergy", storedEnergy);
        return tag;
    }
}

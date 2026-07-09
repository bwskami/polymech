package com.mss.polymech.api.material;

import net.minecraft.world.level.block.SoundType;

public enum PipeMaterial {
    IRON("iron", 3.0F, 6.0F, SoundType.METAL),
    BRONZE("bronze", 3.0F, 6.0F, SoundType.METAL),
    STAINLESS_STEEL("stainless_steel", 4.0F, 8.0F, SoundType.METAL),
    BRASS("brass", 3.0F, 6.0F, SoundType.METAL);

    private final String name;
    private final float strength;
    private final float resistance;
    private final SoundType soundType;

    PipeMaterial(String name, float strength, float resistance, SoundType soundType) {
        this.name = name;
        this.strength = strength;
        this.resistance = resistance;
        this.soundType = soundType;
    }

    public String getName() { return name; }
    public float getStrength() { return strength; }
    public float getResistance() { return resistance; }
    public SoundType getSoundType() { return soundType; }
}
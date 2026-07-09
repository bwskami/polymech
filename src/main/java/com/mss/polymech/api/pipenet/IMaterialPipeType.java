package com.mss.polymech.api.pipenet;

import com.mss.polymech.api.material.PipeMaterial;

public interface IMaterialPipeType extends IPipeType {
    String getName();

    default String getRegistryName(PipeMaterial material) {
        String prefix = material == PipeMaterial.IRON ? "" : material.getName() + "_";
        return prefix + getName();
    }
}
package com.mss.polymech.client;

import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.PipeBlock;

public record PipeIdentifier(PipeMaterial material, PipeBlock.PipeSize size) {}
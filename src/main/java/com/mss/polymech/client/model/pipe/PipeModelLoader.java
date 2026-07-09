package com.mss.polymech.client.model.pipe;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mss.polymech.Polymech;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;

import java.util.EnumMap;
import java.util.Map;

public class PipeModelLoader implements IGeometryLoader<UnbakedPipeModel> {
    public static final PipeModelLoader INSTANCE = new PipeModelLoader();
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "pipe");

    @Override
    public UnbakedPipeModel read(JsonObject json, JsonDeserializationContext context) throws JsonParseException {
        ResourceLocation centerModel = ResourceLocation.parse(
                GsonHelper.getAsString(json, "center", "poly_mech:block/pipes/template_pipe_core"));

        Map<Direction, ArmConfig> armConfigs = new EnumMap<>(Direction.class);

        if (json.has("arms")) {
            JsonObject armsJson = GsonHelper.getAsJsonObject(json, "arms");
            for (Direction dir : Direction.values()) {
                String key = dir.getName();
                if (armsJson.has(key)) {
                    JsonElement armElement = armsJson.get(key);
                    if (armElement.isJsonObject()) {
                        JsonObject armObj = armElement.getAsJsonObject();
                        String modelPath = GsonHelper.getAsString(armObj, "model");
                        int xRot = GsonHelper.getAsInt(armObj, "x", 0);
                        int yRot = GsonHelper.getAsInt(armObj, "y", 0);
                        armConfigs.put(dir, new ArmConfig(ResourceLocation.parse(modelPath), xRot, yRot));
                    } else {
                        String modelPath = armElement.getAsString();
                        armConfigs.put(dir, new ArmConfig(ResourceLocation.parse(modelPath), 0, 0));
                    }
                }
            }
        }

        if (armConfigs.isEmpty()) {
            ResourceLocation defaultArm = ResourceLocation.parse("poly_mech:block/pipes/template_pipe_arm");
            for (Direction dir : Direction.values()) {
                armConfigs.put(dir, new ArmConfig(defaultArm, 0, 0));
            }
        }

        return new UnbakedPipeModel(centerModel, armConfigs);
    }

    public record ArmConfig(ResourceLocation model, int xRot, int yRot) {}
}
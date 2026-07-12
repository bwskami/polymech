package com.mss.polymech.client.model.conveyor;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mss.polymech.Polymech;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;

public class ConveyorModelLoader implements IGeometryLoader<UnbakedConveyorModel> {
    public static final ConveyorModelLoader INSTANCE = new ConveyorModelLoader();
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "conveyor");

    @Override
    public UnbakedConveyorModel read(JsonObject json, JsonDeserializationContext context) throws JsonParseException {
        ResourceLocation center = ResourceLocation.parse(
                GsonHelper.getAsString(json, "center", "poly_mech:block/conveyor_belt/conveyor_belt"));
        ResourceLocation leftRail = ResourceLocation.parse(
                GsonHelper.getAsString(json, "left_rail", "poly_mech:block/conveyor_belt/conveyor_belt_left"));
        ResourceLocation rightRail = ResourceLocation.parse(
                GsonHelper.getAsString(json, "right_rail", "poly_mech:block/conveyor_belt/conveyor_belt_right"));
        return new UnbakedConveyorModel(center, leftRail, rightRail);
    }
}

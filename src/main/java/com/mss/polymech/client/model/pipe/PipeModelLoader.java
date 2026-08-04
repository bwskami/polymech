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
        parseDirectionalSection(json, "arms", armConfigs);

        if (armConfigs.isEmpty()) {
            ResourceLocation defaultArm = ResourceLocation.parse("poly_mech:block/pipes/template_pipe_arm");
            for (Direction dir : Direction.values()) {
                armConfigs.put(dir, new ArmConfig(defaultArm, 0, 0));
            }
        }

        // 抽取口（input）模型：仅在 EXTRACT 状态叠加渲染在管臂外侧，可为空
        Map<Direction, ArmConfig> inputConfigs = new EnumMap<>(Direction.class);
        parseDirectionalSection(json, "inputs", inputConfigs);

        return new UnbakedPipeModel(centerModel, armConfigs, inputConfigs);
    }

    /** 解析按方向组织的模型段（arms/inputs 同构）：每方向 model + x/y 旋转 */
    private static void parseDirectionalSection(JsonObject json, String section, Map<Direction, ArmConfig> out) {
        if (!json.has(section)) return;
        JsonObject sectionJson = GsonHelper.getAsJsonObject(json, section);
        for (Direction dir : Direction.values()) {
            String key = dir.getName();
            if (!sectionJson.has(key)) continue;
            JsonElement element = sectionJson.get(key);
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                String modelPath = GsonHelper.getAsString(obj, "model");
                int xRot = GsonHelper.getAsInt(obj, "x", 0);
                int yRot = GsonHelper.getAsInt(obj, "y", 0);
                out.put(dir, new ArmConfig(ResourceLocation.parse(modelPath), xRot, yRot));
            } else {
                out.put(dir, new ArmConfig(ResourceLocation.parse(element.getAsString()), 0, 0));
            }
        }
    }

    public record ArmConfig(ResourceLocation model, int xRot, int yRot) {}
}
package org.embeddedt.modernfix.mixin.perf.dynamic_resources;

import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import com.mojang.math.Transformation;
import net.minecraft.Util;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.texture.AtlasSet;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistryEntry;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.Logger;
import org.embeddedt.modernfix.ModernFix;
import org.embeddedt.modernfix.dynamicresources.DynamicBakedModelProvider;
import org.embeddedt.modernfix.dynamicresources.DynamicModelBakeEvent;
import org.embeddedt.modernfix.dynamicresources.ModelLocationCache;
import org.embeddedt.modernfix.dynamicresources.ResourcePackHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mixin(ModelBakery.class)
public abstract class ModelBakeryMixin {

    private static final boolean debugDynamicModelLoading = Boolean.getBoolean("modernfix.debugDynamicModelLoading");

    @Shadow @Final @Mutable public Map<ResourceLocation, UnbakedModel> unbakedCache;

    @Shadow @Final public static ModelResourceLocation MISSING_MODEL_LOCATION;

    @Shadow protected abstract BlockModel loadBlockModel(ResourceLocation location) throws IOException;

    @Shadow @Final protected static Set<Material> UNREFERENCED_TEXTURES;
    @Shadow private Map<ResourceLocation, Pair<TextureAtlas, TextureAtlas.Preparations>> atlasPreparations;
    @Shadow @Final protected ResourceManager resourceManager;
    @Shadow @Nullable private AtlasSet atlasSet;
    @Shadow @Final private Set<ResourceLocation> loadingStack;

    @Shadow protected abstract void loadModel(ResourceLocation blockstateLocation) throws Exception;

    @Shadow @Final private static Logger LOGGER;

    @Shadow @Final private static Splitter COMMA_SPLITTER;
    @Shadow @Final private static Splitter EQUAL_SPLITTER;
    @Shadow @Nullable static <T extends Comparable<T>> T getValueHelper(Property<T> property, String value) {
        throw new AssertionError();
    }

    @Shadow @Final @Mutable
    private Map<ResourceLocation, BakedModel> bakedTopLevelModels;

    @Shadow @Final @Mutable private Map<Triple<ResourceLocation, Transformation, Boolean>, BakedModel> bakedCache;

    @Shadow @Final public static BlockModel GENERATION_MARKER;

    @Shadow @Final private static ItemModelGenerator ITEM_MODEL_GENERATOR;

    @Shadow @Final public static BlockModel BLOCK_ENTITY_MARKER;

    @Shadow public abstract Set<ResourceLocation> getSpecialModels();

    private Cache<Triple<ResourceLocation, Transformation, Boolean>, BakedModel> loadedBakedModels;
    private Cache<ResourceLocation, UnbakedModel> loadedModels;

    private HashMap<ResourceLocation, UnbakedModel> smallLoadingCache = new HashMap<>();


    @Inject(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/client/color/block/BlockColors;Z)V", at = @At("RETURN"))
    private void replaceTopLevelBakedModels(ResourceManager manager, BlockColors colors, boolean vanillaBakery, CallbackInfo ci) {
        this.loadedBakedModels = CacheBuilder.newBuilder()
                .expireAfterAccess(3, TimeUnit.MINUTES)
                .maximumSize(1000)
                .concurrencyLevel(8)
                .removalListener(this::onModelRemoved)
                .softValues()
                .build();
        this.loadedModels = CacheBuilder.newBuilder()
                .expireAfterAccess(3, TimeUnit.MINUTES)
                .maximumSize(1000)
                .concurrencyLevel(8)
                .removalListener(this::onModelRemoved)
                .softValues()
                .build();
        this.bakedCache = loadedBakedModels.asMap();
        this.unbakedCache = loadedModels.asMap();
        this.bakedTopLevelModels = new DynamicBakedModelProvider((ModelBakery)(Object)this, bakedCache);
    }

    private <K, V> void onModelRemoved(RemovalNotification<K, V> notification) {
        if(!debugDynamicModelLoading)
            return;
        Object k = notification.getKey();
        if(k == null)
            return;
        ResourceLocation rl;
        boolean baked = false;
        if(k instanceof ResourceLocation) {
            rl = (ResourceLocation)k;
        } else {
            rl = ((Triple<ResourceLocation, Transformation, Boolean>)k).getLeft();
            baked = true;
        }
        ModernFix.LOGGER.warn("Evicted {} model {}", baked ? "baked" : "unbaked", rl);
    }

    private UnbakedModel missingModel;

    private Set<ResourceLocation> blockStateFiles;
    private Set<ResourceLocation> modelFiles;

    @Redirect(method = "processLoading", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/ModelBakery;loadBlockModel(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/block/model/BlockModel;", ordinal = 0))
    private BlockModel captureMissingModel(ModelBakery bakery, ResourceLocation location) throws IOException {
        this.missingModel = this.loadBlockModel(location);
        this.blockStateFiles = new HashSet<>();
        this.modelFiles = new HashSet<>();
        return (BlockModel)this.missingModel;
    }

    /**
     * @author embeddedt
     * @reason don't actually load the model. instead, keep track of if we need to load a blockstate or a model,
     * and save the info into the two lists
     */
    @Overwrite
    private void loadTopLevel(ModelResourceLocation location) {
        if(Objects.equals(location.getVariant(), "inventory")) {
            modelFiles.add(new ResourceLocation(location.getNamespace(), "item/" + location.getPath()));
        } else {
            blockStateFiles.add(new ResourceLocation(location.getNamespace(), location.getPath()));
        }
    }

    @Redirect(method = "processLoading", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/client/ForgeHooksClient;gatherFluidTextures(Ljava/util/Set;)V", remap = false))
    private void gatherModelTextures(Set<Material> materialSet) {
        ForgeHooksClient.gatherFluidTextures(materialSet);
        gatherModelMaterials(materialSet);
    }

    /**
     * Load all blockstate JSONs and model files, collect textures.
     */
    private void gatherModelMaterials(Set<Material> materialSet) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        List<CompletableFuture<Pair<ResourceLocation, JsonElement>>> blockStateData = new ArrayList<>();
        for(ResourceLocation blockstate : blockStateFiles) {
            blockStateData.add(CompletableFuture.supplyAsync(() -> {
                ResourceLocation fileLocation = new ResourceLocation(blockstate.getNamespace(), "blockstates/" + blockstate.getPath() + ".json");
                try(Resource resource = this.resourceManager.getResource(fileLocation)) {
                    JsonParser parser = new JsonParser();
                    return Pair.of(blockstate, parser.parse(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)));
                } catch(IOException | JsonParseException e) {
                    ModernFix.LOGGER.error("Error reading blockstate {}: {}", blockstate, e);
                }
                return Pair.of(blockstate, null);
            }, Util.backgroundExecutor()));
        }
        blockStateFiles = null;
        CompletableFuture.allOf(blockStateData.toArray(new CompletableFuture[0])).join();
        for(CompletableFuture<Pair<ResourceLocation, JsonElement>> result : blockStateData) {
            Pair<ResourceLocation, JsonElement> pair = result.join();
            if(pair.getSecond() != null) {
                try {
                    JsonObject obj = pair.getSecond().getAsJsonObject();
                    if(obj.has("variants")) {
                        JsonObject eachVariant = obj.getAsJsonObject("variants");
                        for(Map.Entry<String, JsonElement> entry : eachVariant.entrySet()) {
                            JsonElement variantData = entry.getValue();
                            List<JsonObject> variantModels;
                            if(variantData.isJsonArray()) {
                                variantModels = new ArrayList<>();
                                for(JsonElement model : variantData.getAsJsonArray()) {
                                    variantModels.add(model.getAsJsonObject());
                                }
                            } else
                                variantModels = Collections.singletonList(variantData.getAsJsonObject());
                            for(JsonObject variant : variantModels) {
                                modelFiles.add(new ResourceLocation(variant.get("model").getAsString()));
                            }
                        }

                    } else {
                        JsonArray multipartData = obj.get("multipart").getAsJsonArray();
                        for(JsonElement element : multipartData) {
                            JsonObject self = element.getAsJsonObject();
                            JsonElement apply = self.get("apply");
                            List<JsonObject> applyObjects;
                            if(apply.isJsonArray()) {
                                applyObjects = new ArrayList<>();
                                for(JsonElement e : apply.getAsJsonArray()) {
                                    applyObjects.add(e.getAsJsonObject());
                                }
                            } else
                                applyObjects = Collections.singletonList(apply.getAsJsonObject());
                            for(JsonObject applyEntry : applyObjects) {
                                modelFiles.add(new ResourceLocation(applyEntry.get("model").getAsString()));
                            }
                        }

                    }
                } catch(RuntimeException e) {
                    ModernFix.LOGGER.error("Error with blockstate {}: {}", pair.getFirst(), e);
                }

            }
        }
        blockStateData = null;
        Map<ResourceLocation, BlockModel> basicModels = new HashMap<>();
        basicModels.put(MISSING_MODEL_LOCATION, (BlockModel)missingModel);
        basicModels.put(new ResourceLocation("builtin/generated"), GENERATION_MARKER);
        basicModels.put(new ResourceLocation("builtin/entity"), BLOCK_ENTITY_MARKER);
        Set<Pair<String, String>> errorSet = Sets.newLinkedHashSet();
        while(modelFiles.size() > 0) {
            List<CompletableFuture<Pair<ResourceLocation, JsonElement>>> modelBytes = new ArrayList<>();
            for(ResourceLocation model : modelFiles) {
                if(basicModels.containsKey(model))
                    continue;
                ResourceLocation fileLocation = new ResourceLocation(model.getNamespace(), "models/" + model.getPath() + ".json");
                modelBytes.add(CompletableFuture.supplyAsync(() -> {
                    try(Resource resource = this.resourceManager.getResource(fileLocation)) {
                        JsonParser parser = new JsonParser();
                        return Pair.of(model, parser.parse(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)));
                    } catch(IOException | JsonParseException e) {
                        ModernFix.LOGGER.error("Error reading model {}: {}", fileLocation, e);
                        return Pair.of(fileLocation, null);
                    }
                }, Util.backgroundExecutor()));
            }
            modelFiles.clear();
            CompletableFuture.allOf(modelBytes.toArray(new CompletableFuture[0])).join();
            for(CompletableFuture<Pair<ResourceLocation, JsonElement>> future : modelBytes) {
                Pair<ResourceLocation, JsonElement> pair = future.join();
                try {
                    if(pair.getSecond() != null) {
                        BlockModel model = ModelLoaderRegistry.ExpandedBlockModelDeserializer.INSTANCE.fromJson(pair.getSecond(), BlockModel.class);
                        model.name = pair.getFirst().toString();
                        modelFiles.addAll(model.getDependencies());
                        basicModels.put(pair.getFirst(), model);
                        continue;
                    }
                } catch(Throwable e) {
                    ModernFix.LOGGER.warn("Unable to parse {}: {}", pair.getFirst(), e);
                }
                basicModels.put(pair.getFirst(), (BlockModel)missingModel);
            }
        }
        modelFiles = null;
        Function<ResourceLocation, UnbakedModel> modelGetter = loc -> basicModels.getOrDefault(loc, (BlockModel)this.missingModel);
        for(BlockModel model : basicModels.values()) {
            materialSet.addAll(model.getMaterials(modelGetter, errorSet));
        }
        //errorSet.stream().filter(pair -> !pair.getSecond().equals(MISSING_MODEL_LOCATION_STRING)).forEach(pair -> LOGGER.warn("Unable to resolve texture reference: {} in {}", pair.getFirst(), pair.getSecond()));
        stopwatch.stop();
        ModernFix.LOGGER.info("Resolving model textures took " + stopwatch);
    }

    @Inject(method = "uploadTextures", at = @At(value = "FIELD", target = "Lnet/minecraft/client/resources/model/ModelBakery;topLevelModels:Ljava/util/Map;", ordinal = 0), cancellable = true)
    private void skipBake(TextureManager resourceManager, ProfilerFiller profiler, CallbackInfoReturnable<AtlasSet> cir) {
        profiler.pop();
        cir.setReturnValue(atlasSet);
    }

    /**
     * Use the already loaded missing model instead of the cache entry (which will probably get evicted).
     */
    @Redirect(method = "loadModel", at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 1))
    private Object getMissingModel(Map map, Object rl) {
        if(rl == MISSING_MODEL_LOCATION && map == unbakedCache)
            return missingModel;
        return unbakedCache.get(rl);
    }

    @Inject(method = "cacheAndQueueDependencies", at = @At("RETURN"))
    private void addToSmallLoadingCache(ResourceLocation location, UnbakedModel model, CallbackInfo ci) {
        smallLoadingCache.put(location, model);
    }


    /**
     * @author embeddedt
     * @reason synchronize
     */
    @Overwrite
    public UnbakedModel getModel(ResourceLocation modelLocation) {
        if(modelLocation.equals(MISSING_MODEL_LOCATION))
            return missingModel;
        UnbakedModel existing = this.unbakedCache.get(modelLocation);
        if (existing != null) {
            return existing;
        } else {
            synchronized(this) {
                if (this.loadingStack.contains(modelLocation)) {
                    throw new IllegalStateException("Circular reference while loading " + modelLocation);
                } else {
                    this.loadingStack.add(modelLocation);
                    UnbakedModel iunbakedmodel = missingModel;

                    while(!this.loadingStack.isEmpty()) {
                        ResourceLocation resourcelocation = this.loadingStack.iterator().next();

                        try {
                            if (!this.unbakedCache.containsKey(resourcelocation)) {
                                if(debugDynamicModelLoading)
                                    LOGGER.info("Loading {}", resourcelocation);
                                this.loadModel(resourcelocation);
                            }
                        } catch (ModelBakery.BlockStateDefinitionException var9) {
                            LOGGER.warn(var9.getMessage());
                            this.unbakedCache.put(resourcelocation, iunbakedmodel);
                            smallLoadingCache.put(resourcelocation, iunbakedmodel);
                        } catch (Exception var10) {
                            LOGGER.warn("Unable to load model: '{}' referenced from: {}: {}", resourcelocation, modelLocation, var10);
                            this.unbakedCache.put(resourcelocation, iunbakedmodel);
                            smallLoadingCache.put(resourcelocation, iunbakedmodel);
                        } finally {
                            this.loadingStack.remove(resourcelocation);
                        }
                    }

                    // We have to get the result from the temporary cache used for a model load
                    // As in pathological cases (e.g. Pedestals on 1.19) unbakedCache can lose
                    // the model immediately
                    UnbakedModel result = smallLoadingCache.getOrDefault(modelLocation, iunbakedmodel);
                    // We are done with loading, so clear this cache to allow GC of any unneeded models
                    smallLoadingCache.clear();
                    return result;
                }
            }
        }
    }

    private <T extends Comparable<T>, V extends T> BlockState setPropertyGeneric(BlockState state, Property<T> prop, Object o) {
        return state.setValue(prop, (V)o);
    }
    @Redirect(method = "loadModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/StateDefinition;getPossibleStates()Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<BlockState> loadOnlyRelevantBlockState(StateDefinition<Block, BlockState> stateDefinition, ResourceLocation location) {
        Set<Property<?>> fixedProperties = new HashSet<>();
        ModelResourceLocation mrl = (ModelResourceLocation)location;
        BlockState fixedState = stateDefinition.any();
        for(String s : COMMA_SPLITTER.split(mrl.getVariant())) {
            Iterator<String> iterator = EQUAL_SPLITTER.split(s).iterator();
            if (iterator.hasNext()) {
                String s1 = iterator.next();
                Property<?> property = stateDefinition.getProperty(s1);
                if (property != null && iterator.hasNext()) {
                    String s2 = iterator.next();
                    Object value = getValueHelper(property, s2);
                    if (value == null) {
                        throw new RuntimeException("Unknown value: '" + s2 + "' for blockstate property: '" + s1 + "' " + property.getPossibleValues());
                    }
                    fixedState = setPropertyGeneric(fixedState, property, value);
                    fixedProperties.add(property);
                } else if (!s1.isEmpty()) {
                    throw new RuntimeException("Unknown blockstate property: '" + s1 + "'");
                }
            }
        }
        // generate all possible blockstates from the remaining properties
        ArrayList<Property<?>> anyProperties = new ArrayList<>(stateDefinition.getProperties());
        anyProperties.removeAll(fixedProperties);
        ArrayList<BlockState> finalList = new ArrayList<>();
        finalList.add(fixedState);
        for(Property<?> property : anyProperties) {
            ArrayList<BlockState> newPermutations = new ArrayList<>();
            for(BlockState state : finalList) {
                for(Comparable<?> value : property.getPossibleValues()) {
                    newPermutations.add(setPropertyGeneric(state, property, value));
                }
            }
            finalList = newPermutations;
        }
        return ImmutableList.copyOf(finalList);
    }

    @Overwrite
    public BakedModel getBakedModel(ResourceLocation arg, ModelState arg2, Function<Material, TextureAtlasSprite> textureGetter) {
        Triple<ResourceLocation, Transformation, Boolean> triple = Triple.of(arg, arg2.getRotation(), arg2.isUvLocked());
        BakedModel existing = this.bakedCache.get(triple);
        if (existing != null) {
            return existing;
        } else if (this.atlasSet == null) {
            throw new IllegalStateException("bake called too early");
        } else {
            synchronized (this) {
                if(debugDynamicModelLoading)
                    LOGGER.info("Baking {}", arg);
                UnbakedModel iunbakedmodel = this.getModel(arg);
                iunbakedmodel.getMaterials(this::getModel, new HashSet<>());
                BakedModel ibakedmodel = null;
                if (iunbakedmodel instanceof BlockModel) {
                    BlockModel blockmodel = (BlockModel)iunbakedmodel;
                    if (blockmodel.getRootModel() == GENERATION_MARKER) {
                        ibakedmodel = ITEM_MODEL_GENERATOR.generateBlockModel(textureGetter, blockmodel).bake((ModelBakery)(Object)this, blockmodel, this.atlasSet::getSprite, arg2, arg, false);
                    }
                }
                if(ibakedmodel == null) {
                    ibakedmodel = iunbakedmodel.bake((ModelBakery) (Object) this, textureGetter, arg2, arg);
                }
                DynamicModelBakeEvent event = new DynamicModelBakeEvent(arg, iunbakedmodel, ibakedmodel, (ModelLoader)(Object)this);
                MinecraftForge.EVENT_BUS.post(event);
                this.bakedCache.put(triple, event.getModel());
                return event.getModel();
            }
        }
    }
}

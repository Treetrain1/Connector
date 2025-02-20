package dev.su5ed.sinytra.connector.locator;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.SecureJar;
import dev.su5ed.sinytra.connector.ConnectorUtil;
import dev.su5ed.sinytra.connector.loader.ConnectorEarlyLoader;
import dev.su5ed.sinytra.connector.loader.ConnectorLoaderModMetadata;
import dev.su5ed.sinytra.connector.transformer.jar.JarTransformer;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.StringUtils;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModProvider;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModJarMetadata;
import net.minecraftforge.fml.loading.progress.StartupNotificationManager;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.IModProvider;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.rethrowFunction;
import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;
import static dev.su5ed.sinytra.connector.transformer.jar.JarTransformer.cacheTransformableJar;
import static net.minecraftforge.fml.loading.LogMarkers.SCAN;

public class ConnectorLocator extends AbstractJarFileModProvider implements IDependencyLocator {
    private static final String NAME = "connector_locator";
    private static final String SUFFIX = ".jar";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MethodHandle MJM_INIT = uncheck(() -> MethodHandles.privateLookupIn(ModJarMetadata.class, MethodHandles.lookup()).findConstructor(ModJarMetadata.class, MethodType.methodType(void.class)));

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        if (ConnectorEarlyLoader.hasEncounteredException()) {
            LOGGER.error("Skipping mod scan due to previously encountered error");
            return List.of();
        }
        try {
            return locateFabricMods(loadedMods);
        } catch (EarlyLoadingException e) {
            // Let these pass through
            throw e;
        } catch (Throwable t) {
            // Rethrow other exceptions
            StartupNotificationManager.addModMessage("CONNECTOR LOCATOR ERROR");
            throw ConnectorEarlyLoader.createGenericLoadingException(t, "Fabric mod discovery failed");
        } finally {
            // Handle forge mod split packages
            ForgeModPackageFilter.filterPackages(loadedMods);
        }
    }

    private List<IModFile> locateFabricMods(Iterable<IModFile> loadedMods) {
        LOGGER.debug(SCAN, "Scanning mods dir {} for mods", FMLPaths.MODSDIR.get());
        List<Path> excluded = ModDirTransformerDiscoverer.allExcluded();
        Path tempDir = ConnectorUtil.CONNECTOR_FOLDER.resolve("temp");
        // Get all existing mod ids
        Collection<SimpleModInfo> loadedModInfos = StreamSupport.stream(loadedMods.spliterator(), false)
            .flatMap(modFile -> Optional.ofNullable(modFile.getModFileInfo()).stream())
            .flatMap(modFileInfo -> {
                IModFile modFile = modFileInfo.getFile();
                List<IModInfo> modInfos = modFileInfo.getMods();
                if (!modInfos.isEmpty()) {
                    return modInfos.stream().map(modInfo -> new SimpleModInfo(modInfo.getModId(), modInfo.getVersion(), false, modFile));
                }
                String version = modFileInfo.getFile().getSecureJar().moduleDataProvider().descriptor().version().map(ModuleDescriptor.Version::toString).orElse("0.0");
                return Stream.of(new SimpleModInfo(modFileInfo.moduleName(), new DefaultArtifactVersion(version), true, modFile));
            })
            .toList();
        Collection<String> loadedModIds = loadedModInfos.stream().filter(mod -> !mod.library()).map(SimpleModInfo::modid).collect(Collectors.toUnmodifiableSet());
        // Discover fabric mod jars
        List<JarTransformer.TransformableJar> discoveredJars = uncheck(() -> Files.list(FMLPaths.MODSDIR.get()))
            .filter(p -> !excluded.contains(p) && StringUtils.toLowerCase(p.getFileName().toString()).endsWith(SUFFIX))
            .sorted(Comparator.comparing(path -> StringUtils.toLowerCase(path.getFileName().toString())))
            .filter(ConnectorLocator::locateFabricModJar)
            .map(rethrowFunction(p -> cacheTransformableJar(p.toFile())))
            .filter(jar -> {
                String modid = jar.modPath().metadata().modMetadata().getId();
                return !shouldIgnoreMod(modid, loadedModIds);
            })
            .toList();
        Multimap<JarTransformer.TransformableJar, JarTransformer.TransformableJar> parentToChildren = HashMultimap.create();
        // Discover fabric nested mod jars
        List<JarTransformer.TransformableJar> discoveredNestedJars = discoveredJars.stream()
            .flatMap(jar -> {
                ConnectorLoaderModMetadata metadata = jar.modPath().metadata().modMetadata();
                return shouldIgnoreMod(metadata.getId(), loadedModIds) ? Stream.empty() : discoverNestedJarsRecursive(tempDir, jar, metadata.getJars(), parentToChildren, loadedModIds);
            })
            .toList();
        // Collect mods that are (likely) going to be excluded by FML's UniqueModListBuilder. Exclude them from global split package filtering
        Collection<? super IModFile> ignoredModFiles = new ArrayList<>();
        // Remove mods loaded by FML
        List<JarTransformer.TransformableJar> uniqueJars = handleDuplicateMods(discoveredJars, discoveredNestedJars, loadedModInfos, ignoredModFiles);
        // Ensure we have all required dependencies before transforming
        List<JarTransformer.TransformableJar> candidates = DependencyResolver.resolveDependencies(uniqueJars, parentToChildren, loadedMods);
        // Get renamer library classpath
        List<Path> renameLibs = StreamSupport.stream(loadedMods.spliterator(), false).map(modFile -> modFile.getSecureJar().getRootPath()).toList();
        // Run jar transformations (or get existing outputs from cache)
        List<JarTransformer.FabricModPath> transformed = JarTransformer.transform(candidates, renameLibs);
        // Skip last step to save time if an error occured during transformation
        if (ConnectorEarlyLoader.hasEncounteredException()) {
            StartupNotificationManager.addModMessage("JAR TRANSFORMATION ERROR");
            LOGGER.error("Cancelling jar discovery due to previous error");
            return List.of();
        }
        // Deal with split packages (thanks modules)
        List<SplitPackageMerger.FilteredModPath> moduleSafeJars = SplitPackageMerger.mergeSplitPackages(transformed, loadedMods, ignoredModFiles);

        List<IModFile> modFiles = new ArrayList<>(moduleSafeJars.stream().map(mod -> createConnectorModFile(mod, this)).toList());
        // Create mod file for generated adapter mixins jar
        Path generatedAdapterJar = JarTransformer.getGeneratedJarPath();
        if (Files.exists(generatedAdapterJar)) {
            IModLocator.ModFileOrException moe = createMod(generatedAdapterJar);
            if (moe.ex() != null) {
                ConnectorEarlyLoader.addGenericLoadingException(new Throwable(), "Error creating generated adapter jar mod");
                StartupNotificationManager.addModMessage("JAR TRANSFORMATION ERROR");
                LOGGER.error("Cancelling jar discovery due to an error");
                return List.of();
            }
            modFiles.add(moe.file());
        }
        return modFiles;
    }

    private static IModFile createConnectorModFile(SplitPackageMerger.FilteredModPath modPath, IModProvider provider) {
        ModJarMetadata mjm = ConnectorUtil.uncheckThrowable(() -> (ModJarMetadata) MJM_INIT.invoke());
        SecureJar modJar = SecureJar.from(Manifest::new, jar -> mjm, modPath.filter(), modPath.paths());
        IModFile mod = new ModFile(modJar, provider, modFile -> ConnectorModMetadataParser.createForgeMetadata(modFile, modPath.metadata().modMetadata()));
        mjm.setModFile(mod);
        return mod;
    }

    private static boolean locateFabricModJar(Path path) {
        SecureJar secureJar = SecureJar.from(path);
        String name = secureJar.name();
        if (secureJar.moduleDataProvider().findFile(ConnectorUtil.MODS_TOML).isPresent()) {
            LOGGER.debug(SCAN, "Skipping jar {} as it contains a mods.toml file", path);
            return false;
        }
        if (secureJar.moduleDataProvider().findFile(ConnectorUtil.FABRIC_MOD_JSON).isPresent()) {
            LOGGER.debug(SCAN, "Found {} mod: {}", ConnectorUtil.FABRIC_MOD_JSON, path);
            return true;
        }
        LOGGER.info(SCAN, "Fabric mod metadata not found in jar {}, ignoring", name);
        return false;
    }

    private static Stream<JarTransformer.TransformableJar> discoverNestedJarsRecursive(Path tempDir, JarTransformer.TransformableJar parent, Collection<NestedJarEntry> jars, Multimap<JarTransformer.TransformableJar, JarTransformer.TransformableJar> parentToChildren, Collection<String> loadedModIds) {
        SecureJar secureJar = SecureJar.from(parent.input().toPath());
        return jars.stream()
            .map(entry -> secureJar.getPath(entry.getFile()))
            .filter(Files::exists)
            .flatMap(path -> {
                JarTransformer.TransformableJar jar = uncheck(() -> prepareNestedJar(tempDir, secureJar.getPrimaryPath().getFileName().toString(), path));
                ConnectorLoaderModMetadata metadata = jar.modPath().metadata().modMetadata();
                if (shouldIgnoreMod(metadata.getId(), loadedModIds)) {
                    return Stream.empty();
                }
                parentToChildren.put(parent, jar);
                return Stream.concat(Stream.of(jar), discoverNestedJarsRecursive(tempDir, jar, metadata.getJars(), parentToChildren, loadedModIds));
            });
    }

    private static JarTransformer.TransformableJar prepareNestedJar(Path tempDir, String parentName, Path path) throws IOException {
        Files.createDirectories(tempDir);

        String parentNameWithoutExt = parentName.split("\\.(?!.*\\.)")[0];
        // Extract JiJ
        Path extracted = tempDir.resolve(parentNameWithoutExt + "$" + path.getFileName().toString());
        ConnectorUtil.cache(path, extracted, () -> Files.copy(path, extracted));

        return uncheck(() -> JarTransformer.cacheTransformableJar(extracted.toFile()));
    }

    // Removes any duplicates from located connector mods, as well as mods that are already located by FML.
    private static List<JarTransformer.TransformableJar> handleDuplicateMods(List<JarTransformer.TransformableJar> rootMods, List<JarTransformer.TransformableJar> nestedMods, Collection<SimpleModInfo> loadedMods, Collection<? super IModFile> ignoredModFiles) {
        return Stream.concat(rootMods.stream(), nestedMods.stream())
            .filter(jar -> {
                String id = jar.modPath().metadata().modMetadata().getId();
                List<SimpleModInfo> forgeMods = loadedMods.stream()
                    .filter(mod -> mod.modid().equals(id))
                    .toList();
                // Add mods that are going to be excluded by FML's UniqueModListBuilder to the ignore list 
                if (forgeMods.stream().anyMatch(SimpleModInfo::library)) {
                    ArtifactVersion artifactVersion = new DefaultArtifactVersion(jar.modPath().metadata().modMetadata().getVersion().getFriendlyString());
                    SimpleModInfo fabricModInfo = new SimpleModInfo(id, artifactVersion, false, null);
                    // Sort mods by version, descending
                    List<SimpleModInfo> modsByVersion = Stream.concat(Stream.of(fabricModInfo), forgeMods.stream())
                        .sorted(Comparator.comparing(SimpleModInfo::version).reversed())
                        .toList();
                    // The fabric mod has the latest version - ignore others
                    if (modsByVersion.get(0) == fabricModInfo) {
                        modsByVersion.subList(1, modsByVersion.size()).forEach(mod -> {
                            IModFile modFile = Objects.requireNonNull(mod.origin(), "Missing mod origin for mod " + mod.modid());
                            ignoredModFiles.add(modFile);
                        });
                        return true;
                    }
                }
                if (loadedMods.stream().anyMatch(mod -> mod.modid().equals(id))) {
                    LOGGER.info(SCAN, "Removing duplicate mod {} in file {}", id, jar.modPath().path().toAbsolutePath());
                    return false;
                }
                return true;
            })
            .toList();
    }

    private static boolean shouldIgnoreMod(String id, Collection<String> loadedModIds) {
        return ConnectorUtil.DISABLED_MODS.contains(id) || loadedModIds.contains(id);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {}

    private record SimpleModInfo(String modid, ArtifactVersion version, boolean library, @Nullable IModFile origin) {}
}

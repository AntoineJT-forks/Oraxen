package io.th0rgal.oraxen.pack.generation;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.items.OraxenItems;
import io.th0rgal.oraxen.settings.Pack;
import io.th0rgal.oraxen.settings.ResourcesManager;
import io.th0rgal.oraxen.utils.Utils;
import io.th0rgal.oraxen.utils.ZipUtils;

import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ResourcePack {

    private static File modelsFolder;
    private static final List<Consumer<File>> PACK_MODIFIERS = new ArrayList<>();
    JavaPlugin plugin;
    private File pack;
    byte[] sha1;

    public ResourcePack(JavaPlugin plugin) {
        this.plugin = plugin;
        File packFolder = new File(plugin.getDataFolder(), "pack");
        makeDirsIfNotExists(packFolder);

        File texturesFolder = new File(packFolder, "textures");
        modelsFolder = new File(packFolder, "models");

        boolean extractModels = !modelsFolder.exists();
        boolean extractTextures = !texturesFolder.exists();

        if (extractModels || extractTextures) {
            ZipInputStream zip = ResourcesManager.browse();
            try {
                ZipEntry entry = zip.getNextEntry();
                ResourcesManager resourcesManager = new ResourcesManager(OraxenPlugin.get());

                while (entry != null) {
                    String name = entry.getName();
                    boolean isSuitable = (extractModels && name.startsWith("pack/models"))
                            || (extractTextures && name.startsWith("pack/textures"));

                    resourcesManager.extractFileIfTrue(entry, name, isSuitable);
                    entry = zip.getNextEntry();
                }
                zip.closeEntry();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        this.pack = new File(packFolder, packFolder.getName() + ".zip");

        if (!Boolean.parseBoolean(Pack.GENERATE.toString()))
            return;

        if (pack.exists())
            pack.delete();

        extractInPackIfNotExists(plugin, new File(packFolder, "pack.mcmeta"));
        extractInPackIfNotExists(plugin, new File(packFolder, "pack.png"));

        // Sorting items to keep only one with models (and generate it if needed)
        Map<Material, List<ItemBuilder>> texturedItems = new HashMap<>();
        for (Map.Entry<String, ItemBuilder> entry : OraxenItems.getEntries()) {
            ItemBuilder item = entry.getValue();
            if (item.hasPackInfos()) {
                if (item.getPackInfos().shouldGenerateModel()) {
                    Utils.writeStringToFile(
                            new File(modelsFolder, item.getPackInfos().getModelName() + ".json"),
                            new ModelGenerator(item.getPackInfos()).getJson().toString());
                }
                List<ItemBuilder> items = texturedItems.getOrDefault(item.build().getType(), new ArrayList<>());
                //todo: could be improved by using items.get(i).getPackInfos().getCustomModelData() when items.add(customModelData, item) with catch when not possible
                if (items.isEmpty())
                    items.add(item);
                else
                    // for some reason those breaks are needed to avoid some nasty "memory leak"
                    for (int i = 0; i < items.size(); i++)
                        if (items.get(i).getPackInfos().getCustomModelData() > item.getPackInfos().getCustomModelData()) {
                            items.add(i, item);
                            break;
                        } else if (i == items.size() - 1) {
                            items.add(item);
                            break;
                        }
                texturedItems.put(item.build().getType(), items);
            }
        }
        generatePredicates(texturedItems);
        for (Consumer<File> packModifier : PACK_MODIFIERS)
            packModifier.accept(packFolder);

        //zipping resourcepack
        List<File> rootFolder = new ArrayList<>();
        ZipUtils.getFilesInFolder(packFolder, rootFolder, packFolder.getName() + ".zip");

        List<File> subfolders = new ArrayList<>();
        // needs to be ordered, forEach cannot be used
        Arrays.stream(packFolder.listFiles())
                .filter(File::isDirectory)
                .forEachOrdered(folder -> ZipUtils.getAllFiles(folder, subfolders));

        Map<String, List<File>> fileListByZipDirectory = new HashMap<>();
        fileListByZipDirectory.put("assets/minecraft", subfolders);
        fileListByZipDirectory.put("", rootFolder);

        ZipUtils.writeZipFile(pack, packFolder, fileListByZipDirectory);
    }

    public File getFile() {
        return pack;
    }

    private void extractInPackIfNotExists(JavaPlugin plugin, File file) {
        if (!file.exists())
            plugin.saveResource("pack/" + file.getName(), true);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void makeDirsIfNotExists(File folder) {
        if (!folder.exists())
            folder.mkdirs();
    }

    @SafeVarargs
    public static void addModifiers(Consumer<File>... modifiers) {
        PACK_MODIFIERS.addAll(Arrays.asList(modifiers));
    }

    private void generatePredicates(Map<Material, List<ItemBuilder>> texturedItems) {
        File itemsFolder = new File(modelsFolder, "item");
        makeDirsIfNotExists(itemsFolder);

        for (Map.Entry<Material, List<ItemBuilder>> texturedItemsEntry : texturedItems.entrySet()) {
            Material entryMaterial = texturedItemsEntry.getKey();
            PredicatesGenerator predicatesGenerator = new PredicatesGenerator(entryMaterial, texturedItemsEntry.getValue());
            String vanillaModelName = predicatesGenerator.getVanillaModelName(entryMaterial) + ".json";

            Utils.writeStringToFile(
                    new File(modelsFolder, vanillaModelName),
                    predicatesGenerator.toJSON().toString());
        }
    }

}
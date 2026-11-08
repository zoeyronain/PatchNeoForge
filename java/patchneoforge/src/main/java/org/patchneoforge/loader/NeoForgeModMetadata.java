package org.patchneoforge.loader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NeoForgeModMetadata {

    private String modLoader = "javafml";
    private String modId = "";
    private String version = "1.0.0";
    private String displayName = "";
    private String description = "";
    private String authors = "";

    public NeoForgeModMetadata() {}

    public NeoForgeModMetadata(String modId, String displayName, String version, String description) {
        this.modId = modId;
        this.displayName = displayName;
        this.version = version;
        this.description = description;
    }

    public static List<NeoForgeModMetadata> parseFromToml(InputStream in) {
        List<NeoForgeModMetadata> mods = new ArrayList<>();
        if (in == null) return mods;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            NeoForgeModMetadata currentMod = null;
            boolean inModsSection = false;
            String globalModLoader = "javafml";

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;

                if (line.startsWith("[[mods]]")) {
                    currentMod = new NeoForgeModMetadata();
                    currentMod.modLoader = globalModLoader;
                    mods.add(currentMod);
                    inModsSection = true;
                    continue;
                } else if (line.startsWith("[") && !line.startsWith("[[mods]]")) {
                    inModsSection = false;
                }

                if (line.contains("=")) {
                    int eq = line.indexOf('=');
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    // Strip inline comments
                    int hashIdx = val.indexOf('#');
                    if (hashIdx >= 0) {
                        val = val.substring(0, hashIdx).trim();
                    }
                    // Strip quotes
                    if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                        val = val.substring(1, val.length() - 1);
                    } else if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                        val = val.substring(1, val.length() - 1);
                    }

                    if ("modLoader".equals(key)) {
                        globalModLoader = val;
                        if (currentMod != null) currentMod.modLoader = val;
                    }

                    if (inModsSection && currentMod != null) {
                        switch (key) {
                            case "modId" -> currentMod.modId = val;
                            case "version" -> currentMod.version = val;
                            case "displayName" -> currentMod.displayName = val;
                            case "description" -> currentMod.description = val;
                            case "authors" -> currentMod.authors = val;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log or fallback
        }

        return mods;
    }

    public String getModLoader() { return modLoader; }

    public String getModId() { return modId; }
    public String getVersion() { return version; }
    public String getDisplayName() { return displayName.isEmpty() ? modId : displayName; }
    public String getDescription() { return description; }
    public String getAuthors() { return authors; }

    @Override
    public String toString() {
        return "NeoForgeModMetadata{" + "modId='" + modId + '\'' + ", version='" + version + '\'' + '}';
    }
}

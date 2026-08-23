package com.sparrowwallet.sparrow.joinstr;

import com.sparrowwallet.sparrow.io.Config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The config file is written with gson, which reflects into every field it serialises.
 *
 * Reaching a JavaFX property that way throws InaccessibleObjectException in the packaged image,
 * because javafx.base does not open javafx.beans.property to com.google.gson. It does not happen
 * when running from Gradle or an IDE, where everything is on the classpath and module rules do
 * not apply, so only a packaged build shows it. This walks the fields gson would touch and fails
 * before that can ship.
 */
public class ConfigSerialisationTest {

    /** Types gson will not reflect into, so recursion can stop there. */
    private boolean isLeaf(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type.getName().startsWith("java.");
    }

    private void collectReachable(Type type, Set<Class<?>> seen, List<String> javafxTypes,
            String path) {
        if (type instanceof ParameterizedType parameterized) {
            collectReachable(parameterized.getRawType(), seen, javafxTypes, path);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collectReachable(argument, seen, javafxTypes, path);
            }
            return;
        }

        if (!(type instanceof Class<?> clazz) || !seen.add(clazz)) {
            return;
        }

        if (clazz.getName().startsWith("javafx.")) {
            javafxTypes.add(path + " -> " + clazz.getName());
            return;
        }

        if (isLeaf(clazz)) {
            return;
        }

        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            collectReachable(field.getGenericType(), seen, javafxTypes,
                    path + "." + field.getName());
        }
    }

    @Test
    public void noJavafxTypeIsReachableFromTheConfigFile() {
        List<String> javafxTypes = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();

        for (Field field : Config.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            collectReachable(field.getGenericType(), seen, javafxTypes, "Config." + field.getName());
        }

        assertTrue(javafxTypes.isEmpty(),
                "gson would reflect into a JavaFX type writing the config, which throws "
                        + "InaccessibleObjectException in the packaged build:\n  "
                        + String.join("\n  ", javafxTypes));
    }

    /**
     * The pool and history files are written with gson too, so the same rule applies to the
     * wrappers they go through. Those convert to plain data classes precisely so gson never
     * reaches a JavaFX property, and this keeps a field from being added straight to a wrapper.
     */
    @Test
    public void noJavafxTypeIsReachableFromThePoolOrHistoryFiles() {
        List<String> javafxTypes = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();

        for (Class<?> nested : JoinstrPool.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("JoinstrPoolStoreWrapper")) {
                collectReachable(nested, seen, javafxTypes, "pools.json");
            }
        }
        for (Class<?> nested : JoinstrHistoryEntry.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("JoinstrHistoryStoreWrapper")) {
                collectReachable(nested, seen, javafxTypes, "history.json");
            }
        }

        assertFalse(seen.isEmpty(), "the store wrappers should have been found");
        assertTrue(javafxTypes.isEmpty(),
                "gson would reflect into a JavaFX type writing the joinstr files:\n  "
                        + String.join("\n  ", javafxTypes));
    }

    /** The pool store holds JavaFX properties, so it must stay out of the config file. */
    @Test
    public void thePoolAndHistoryStoresAreNotSerialised() throws Exception {
        assertTrue(Modifier.isTransient(Config.class.getDeclaredField("poolStore").getModifiers()),
                "poolStore must be transient");
        assertTrue(Modifier.isTransient(Config.class.getDeclaredField("historyStore").getModifiers()),
                "historyStore must be transient");
    }

    /** They are kept in their own files instead, so nothing is lost by not serialising them. */
    @Test
    public void thePoolsAreReadBackFromTheirOwnFile() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("pools", ".json");
        try {
            java.nio.file.Files.writeString(file, "{\"poolsList\":[{\"relay\":\"wss://nos.lol\","
                    + "\"pubkey\":\"pk\",\"denomination\":\"0.001\",\"peers\":\"3\","
                    + "\"timeout\":\"1750000000\",\"privateKey\":\"aabb\",\"status\":\"\"}]}");

            ArrayList<JoinstrPool> pools = JoinstrPool.loadPoolsFile(file.toString());

            assertEquals(1, pools.size());
            assertEquals("wss://nos.lol", pools.get(0).getRelay());
            assertEquals("0.001", pools.get(0).getDenomination());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    public void aMissingOrEmptyPoolsFileIsNotAnError() throws Exception {
        assertTrue(JoinstrPool.loadPoolsFile("/nonexistent/pools.json").isEmpty());
        assertTrue(JoinstrHistoryEntry.loadHistoryFile("/nonexistent/history.json").isEmpty());

        java.nio.file.Path empty = java.nio.file.Files.createTempFile("pools", ".json");
        try {
            assertTrue(JoinstrPool.loadPoolsFile(empty.toString()).isEmpty());
        } finally {
            java.nio.file.Files.deleteIfExists(empty);
        }
    }
}

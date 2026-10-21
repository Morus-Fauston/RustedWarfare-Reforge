package com.rustedwarfare.reforge.reference.instrumentation;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed public original API; no original classes are linked into this module. */
final class SceneAccess {
    private final ClassLoader loader;
    private final ScenePlan plan;
    private final Class<?> engineType, playerType, unitType, definitionType, customType, mapType, layerType, baseType, elementType;
    private boolean modPreparationAttempted;
    private Object preparedDefinition, preparedMod;

    SceneAccess(ClassLoader loader,ScenePlan plan) throws Exception {
        this.plan=plan;
        this.loader = loader;
        engineType = type("com.corrodinggames.rts.gameFramework.k");
        playerType = type("com.corrodinggames.rts.game.p");
        unitType = type("com.corrodinggames.rts.game.units.ce");
        definitionType = type("com.corrodinggames.rts.game.units.custom.l");
        customType = type("com.corrodinggames.rts.game.units.custom.j");
        mapType = type("com.corrodinggames.rts.game.b.b");
        layerType = type("com.corrodinggames.rts.game.b.g");
        baseType = type("com.corrodinggames.rts.gameFramework.ah");
        elementType = type("com.corrodinggames.rts.game.units.el");
    }

    private Class<?> type(String name) throws Exception {
        Class<?> c = Class.forName(name, false, loader);
        InitialProtocol.require(Modifier.isPublic(c.getModifiers()) && c.getClassLoader() == loader, "original-public-owner:" + name);
        return c;
    }

    private Object invoke(Class<?> owner, String name, Class<?> result, boolean isStatic,
            Object instance, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = owner.getMethod(name, parameters);
        InitialProtocol.require(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()) == isStatic
                && method.getReturnType() == result, "original-method-descriptor:" + owner.getName() + "." + name);
        return method.invoke(instance, arguments);
    }

    private Field field(Class<?> owner, String name, Class<?> valueType, boolean isStatic) throws Exception {
        Field field = owner.getField(name);
        InitialProtocol.require(Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()) == isStatic
                && field.getType() == valueType, "original-field-descriptor:" + owner.getName() + "." + name);
        return field;
    }

    private Object value(Class<?> owner, String name, Class<?> valueType, Object instance) throws Exception {
        return field(owner, name, valueType, instance == null).get(instance);
    }

    Object engine() throws Exception { return invoke(engineType, "t", engineType, true, null, new Class<?>[0]); }
    boolean running(Object engine) throws Exception { return (Boolean) invoke(engineType, "z", boolean.class, false, engine, new Class<?>[0]); }

    /** Public native lifecycle only; used before the controlled unit exists. */
    void stopForMotionUi() throws Exception {
        Object engine=engine();
        invoke(engineType,"B",void.class,false,engine,new Class<?>[0]);
        InitialProtocol.require(!running(engine),"motion-ui-stop-failed");
    }

    Map<String,Object> motionUiState() throws Exception {return motionUiState(null);}
    Map<String,Object> motionUiState(Object expectedActivity) throws Exception {
        Object engine=engine();
        synchronized(engine) {
            Map<String,Object> state=map();
            boolean active=running(engine);state.put("gameThreadRunning",active);
            InitialProtocol.require(!active,"motion-ui-running-no-read");
            for(String name:new String[]{"ap","bE","bm","bD"}) state.put(name,value(engineType,name,boolean.class,engine));
            for(String name:new String[]{"bu","bv"}) state.put(name,value(engineType,name,int.class,engine));
            Object context=value(engineType,"am",Context.class,engine);
            state.put("activityClass",context==null?null:context.getClass().getName());
            state.put("expectedActivitySame",context!=null && context==expectedActivity);
            Class<?> viewType=type("com.corrodinggames.rts.appFramework.ab");
            Object view=value(engineType,"ao",viewType,engine);
            state.put("viewClass",view==null?null:view.getClass().getName());
            Object activity=view==null?null:invoke(viewType,"getInGameActivity",type("com.corrodinggames.rts.appFramework.InGameActivity"),false,view,new Class<?>[0]);
            state.put("viewActivityIsContext",activity!=null && activity==context);
            state.put("surfaceExists",view!=null && (Boolean)invoke(viewType,"getSurfaceExists",boolean.class,false,view,new Class<?>[0]));
            Class<?> uiType=type("com.corrodinggames.rts.gameFramework.f.i"),networkType=type("com.corrodinggames.rts.gameFramework.j.ae");
            Object ui=value(engineType,"bP",uiType,engine),network=value(engineType,"bU",networkType,engine);
            state.put("interfacePaused",value(uiType,"u",boolean.class,ui));
            state.put("networked",value(networkType,"C",boolean.class,network));
            state.put("networkBlocked",invoke(networkType,"m",boolean.class,false,network,new Class<?>[0]));
            return state;
        }
    }

    Object boot(String method, Class<?> result) throws Exception {
        return invoke(type("com.rustedwarfare.reforge.reference.boot.GbootBootstrap"), method, result, true, null, new Class<?>[0]);
    }

    void initialize(Context targetContext) throws Exception {
        InitialProtocol.require(targetContext != null && targetContext.getClassLoader() == loader, "original-bootstrap-target-loader");
        invoke(type("com.rustedwarfare.reforge.reference.boot.GbootBootstrap"), "init", void.class, true, null,
                new Class<?>[] {Context.class}, targetContext);
    }

    void configureSettings(String runId) throws Exception {
        invoke(type("com.rustedwarfare.reforge.reference.boot.BatchSettings"), "configure", void.class, true, null,
                new Class<?>[] {String.class}, runId);
    }

    void closeUpdateDialogs() throws Exception {
        invoke(type("com.rustedwarfare.reforge.reference.boot.FixedUpdateDialogCleanup"), "close", void.class, true, null, new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> updateDialogStatus() throws Exception {
        return (Map<String, Object>) invoke(type("com.rustedwarfare.reforge.reference.boot.FixedUpdateDialogCleanup"), "status", Map.class, true, null, new Class<?>[0]);
    }

    Map<String, Object> settingsBatch(String runId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runId", runId);
        Class<?> owner = type("com.rustedwarfare.reforge.reference.boot.BatchSettings");
        for (String method : new String[] {"name", "xmlName", "backupName"})
            result.put(method, invoke(owner, method, String.class, true, null, new Class<?>[0]));
        return result;
    }

    void load(String map) throws Exception {
        invoke(type("com.corrodinggames.rts.appFramework.LevelSelectActivity"), "loadSinglePlayerMapRaw", void.class, true, null,
                new Class<?>[] {String.class, boolean.class, int.class, int.class, boolean.class, boolean.class}, map, true, 0, 0, false, false);
    }

    /** Same selection/save/reload APIs as ModsActivity; only the allocated private mod is enabled. */
    synchronized void prepareFixedDefinition(String ini, Map<String, Object> raw) throws Throwable {
        InitialProtocol.require(!modPreparationAttempted, "mod-preparation-already-attempted");
        modPreparationAttempted = true; raw.put("ini", ini); raw.put("lastCompletedOperation", "begin");
        String directory = new File((String) boot("unitsRoot", String.class), "p001").getPath();
        InitialProtocol.require(ini != null && new File(ini).isAbsolute()
                && ini.equals(new File(directory, "weaponless_tank.ini").getPath()), "mod-fixed-source");
        InitialProtocol.require(new File(ini).getCanonicalPath().equals(ini), "mod-ini-canonical");
        final Object engine = engine(); InitialProtocol.require(engine != null, "original-engine-missing");
        raw.put("runningBeforeStop", running(engine));
        invoke(engineType, "B", void.class, false, engine, new Class<?>[0]);
        InitialProtocol.require(!running(engine), "mod-prepare-original-thread-running");
        raw.put("stopReturned", true);
        final boolean oldBo = (Boolean) value(engineType, "bo", boolean.class, engine);
        raw.put("boBefore", oldBo); InitialProtocol.require(!oldBo, "mod-load-preexisting");
        final Class<?> managerType = type("com.corrodinggames.rts.gameFramework.i.a");
        final Class<?> modType = type("com.corrodinggames.rts.gameFramework.i.b");
        final Class<?> settingsType = type("com.corrodinggames.rts.gameFramework.SettingsEngine");
        final Object manager = value(engineType, "bW", managerType, engine);
        final Object settings = value(engineType, "bN", settingsType, engine);
        InitialProtocol.require(manager != null && settings != null, "mod-manager-settings-missing");
        Map<Object, Map<String, Object>> before = new IdentityHashMap<Object, Map<String, Object>>();
        List<Object> beforeRows = new ArrayList<Object>(); raw.put("modsBefore", beforeRows);
        Object selected = null;
        for (Object mod : modObjects(managerType, manager)) {
            Map<String, Object> row = modRow(modType, mod); beforeRows.add(row); before.put(mod, row);
            if (directory.equals(row.get("o"))) {
                InitialProtocol.require(selected == null && "p001".equals(row.get("c"))
                        && Boolean.FALSE.equals(row.get("k")) && Boolean.FALSE.equals(row.get("w")), "mod-source-not-unique-private");
                selected = mod;
            }
        }
        InitialProtocol.require(selected != null && before.size() == beforeRows.size(), "mod-source-missing-or-duplicate");
        for (Map.Entry<Object, Map<String, Object>> row : before.entrySet())
            InitialProtocol.require(row.getKey() == selected || Boolean.TRUE.equals(row.getValue().get("f")), "unallocated-enabled-mod");
        InitialProtocol.require(before.get(selected).get("P") == null, "mod-preexisting-load-error");
        checkModSettings(settingsType, settings, before.size(), raw, "Before");
        raw.put("lastCompletedOperation", "selection-checked");
        Throwable primary = null; boolean changedBo = false;
        try {
            field(modType, "f", boolean.class, false).setBoolean(selected, false);
            invoke(managerType, "d", void.class, false, manager, new Class<?>[0]);
            boolean saved = (Boolean) invoke(settingsType, "save", boolean.class, false, settings, new Class<?>[0]);
            raw.put("saveReturned", saved); boot("requireBackendPublished", void.class);
            InitialProtocol.require(saved, "mod-selection-save-failed");
            checkModSettings(settingsType, settings, before.size(), raw, "AfterSave");
            raw.put("lastCompletedOperation", "selection-saved");
            field(engineType, "bo", boolean.class, false).setBoolean(engine, true); changedBo = true;
            invoke(engineType, "f", void.class, false, engine, new Class<?>[0]);
            InitialProtocol.require(!running(engine), "mod-prepare-original-thread-running");
            raw.put("lastCompletedOperation", "original-world-cleared");
            invoke(managerType, "a", void.class, false, manager, new Class<?>[] {boolean.class, boolean.class}, false, false);
            raw.put("lastCompletedOperation", "native-reload-returned"); boot("requireBackendPublished", void.class);
            List<Object> afterRows = new ArrayList<Object>(); raw.put("modsAfter", afterRows);
            List<?> after = modObjects(managerType, manager);
            InitialProtocol.require(after.size() == before.size(), "mod-set-changed");
            Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
            for (Object mod : after) {
                Map<String, Object> row = modRow(modType, mod); afterRows.add(row);
                Map<String, Object> old = before.get(mod);
                InitialProtocol.require(seen.put(mod, true) == null && old != null && equal(old.get("c"), row.get("c")) && equal(old.get("e"), row.get("e"))
                        && equal(old.get("o"), row.get("o")) && Boolean.valueOf(mod != selected).equals(row.get("f")), "mod-selection-identity-changed");
            }
            raw.put("modObjectsPreserved", true);
            InitialProtocol.require(value(modType, "P", String.class, selected) == null, "mod-native-load-error");
            checkModSettings(settingsType, settings, before.size(), raw, "AfterReload");
            Object definition = invoke(definitionType, "k", definitionType, true, null, new Class<?>[] {String.class}, "p001_weaponless_tank");
            raw.put("definitionRegistered", definition != null);
            InitialProtocol.require(definition != null, "unit-definition-missing-after-reload");
            Object name = value(definitionType, "M", String.class, definition), source = value(definitionType, "D", String.class, definition);
            raw.put("definitionM", name); raw.put("definitionD", source);
            boolean sameMod = value(definitionType, "J", modType, definition) == selected; raw.put("definitionUsesSelectedMod", sameMod);
            InitialProtocol.require("p001_weaponless_tank".equals(name) && ini.equals(source) && sameMod, "unit-definition-reload-identity");
            preparedDefinition = definition; preparedMod = selected; raw.put("lastCompletedOperation", "registered-definition-checked");
        } catch (Throwable error) { primary = error; throw error; }
        finally {
            Throwable secondary = null;
            try {
                if (changedBo) field(engineType, "bo", boolean.class, false).setBoolean(engine, oldBo);
                raw.put("boAfter", value(engineType, "bo", boolean.class, engine));
            } catch (Throwable error) { secondary = error; raw.put("boRestoreError", error.getClass().getName() + ":" + error.getMessage()); }
            try {
                List<Object> rows = new ArrayList<Object>(); raw.put("modsFinalReadback", rows);
                for (Object mod : modObjects(managerType, manager)) rows.add(modRow(modType, mod));
            } catch (Throwable error) {
                raw.put("modReadbackError", error.getClass().getName() + ":" + error.getMessage());
                if (secondary == null) secondary = error; else if (secondary != error) secondary.addSuppressed(error);
            }
            if (secondary != null) { if (primary != null) { if (primary != secondary) primary.addSuppressed(secondary); } else throw secondary; }
        }
    }

    private List<?> modObjects(Class<?> managerType, Object manager) throws Exception {
        Object mods = value(managerType, "e", ArrayList.class, manager);
        InitialProtocol.require(mods instanceof List && ((List<?>) mods).size() > 0 && ((List<?>) mods).size() <= 32, "mod-list-limit");
        return new ArrayList<Object>((List<?>) mods);
    }

    private Map<String, Object> modRow(Class<?> owner, Object mod) throws Exception {
        InitialProtocol.require(mod != null && mod.getClass() == owner, "mod-object-type");
        Map<String, Object> row = map();
        for (String name : new String[] {"c", "e", "o", "P", "Q"}) row.put(name, value(owner, name, String.class, mod));
        for (String name : new String[] {"f", "k", "w", "A", "B"}) row.put(name, value(owner, name, boolean.class, mod));
        for (String name : new String[] {"C", "D"}) row.put(name, value(owner, name, int.class, mod));
        return row;
    }

    private void checkModSettings(Class<?> owner, Object settings, int count, Map<String, Object> raw, String suffix) throws Exception {
        int last = (Integer) value(owner, "lastModCount", int.class, settings), version = (Integer) value(owner, "modSettingsVersion", int.class, settings);
        raw.put("lastModCount" + suffix, last); raw.put("modSettingsVersion" + suffix, version);
        InitialProtocol.require(last == count && version > 0, "mod-first-scan-state");
    }

    private static boolean equal(Object a, Object b) { return a == b || a != null && a.equals(b); }

    Snapshot snapshot(final String mapPath, final Object factoryDefinition) {
        final Snapshot s = new Snapshot();
        s.readFailure = ReadbackEvidence.capture(s.raw, new ReadbackEvidence.Read() {
            @Override public void run() throws Throwable { readSnapshot(s, mapPath, factoryDefinition); }
        });
        return s;
    }

    private void readSnapshot(Snapshot s, String mapPath, Object factoryDefinition) throws Exception {
        s.engine = engine();
        s.raw.put("enginePresent", s.engine != null);
        InitialProtocol.require(s.engine != null, "original-engine-missing");
        Map<String, Object> raw = s.raw;
        raw.put("engineClass", s.engine.getClass().getName());
        raw.put("di", value(engineType, "di", String.class, s.engine));
        s.loading = (Boolean) value(engineType, "bF", boolean.class, s.engine);
        raw.put("bF", s.loading);
        s.ready = (Boolean) value(engineType, "bD", boolean.class, s.engine);
        raw.put("bD", s.ready);
        boolean running = running(s.engine); raw.put("gameThreadRunning", running);
        if (running) {
            raw.put("tableReadSkippedReason", "original-game-thread-running-no-stable-table-read");
            throw new IllegalArgumentException("native-running-no-table-read");
        }
        raw.put("bu", value(engineType, "bu", int.class, s.engine)); raw.put("bv", value(engineType, "bv", int.class, s.engine));
        s.map = value(engineType, "bI", mapType, s.engine);
        raw.put("mapClass", s.map == null ? null : s.map.getClass().getName());
        if (s.map != null) {
            s.mapReady = (Boolean) value(mapType, "X", boolean.class, s.map);
            raw.put("mapX", s.mapReady); raw.put("D", value(mapType, "D", int.class, s.map)); raw.put("E", value(mapType, "E", int.class, s.map));
            layer(value(mapType, "u", layerType, s.map), raw, "Ground");
            layer(value(mapType, "y", layerType, s.map), raw, "Items");
        } else raw.put("mapX", null);
        s.player = value(engineType, "bp", playerType, s.engine);
        Object[] players = (Object[]) value(playerType, "j", java.lang.reflect.Array.newInstance(playerType, 0).getClass(), null);
        raw.put("playerArrayLength", players == null ? null : players.length);
        InitialProtocol.require(players != null && players.length <= 64, "native-player-limit");
        List<Object> rows = new ArrayList<Object>();
        raw.put("players", rows);
        for (int index = 0; index < players.length; index++) {
            Map<String, Object> row = players[index] == null ? null : map(); rows.add(row);
            player(players[index], index, s.player, players, row);
        }
        Map<String, Object> neutral = map(); raw.put("neutral", neutral);
        for (String name : new String[] {"h", "i"}) {
            Object object = value(playerType, name, playerType, null);
            Map<String, Object> row = object == null ? null : map(); neutral.put(name, row);
            player(object, null, s.player, players, row);
            if (row != null) row.put("sourceField", "p." + name);
        }
        Class<?> listType = type("com.corrodinggames.rts.gameFramework.utility.x");
        Object table = value(unitType, "bG", listType, null);
        InitialProtocol.require(table instanceof List, "native-table-type");
        List<?> list = (List<?>) table;
        raw.put("bGSize", list.size());
        InitialProtocol.require(list.size() <= 256, "native-unit-limit:" + list.size());
        s.units = list.toArray();
        InitialProtocol.require(s.units.length == list.size(), "native-table-race");
        List<Object> units = new ArrayList<Object>();
        raw.put("units", units);
        for (Object unit : s.units) {
            Map<String, Object> row = map(); units.add(row); unit(unit, players, s.player, factoryDefinition, row);
        }
        raw.put("bGSize", s.units.length);
        s.pathMatches = mapPath.equals(raw.get("di"));
        s.dimensionsMatch = s.map != null && Integer.valueOf(30).equals(raw.get("D")) && Integer.valueOf(20).equals(raw.get("E"))
                && validLayer(raw.get("Ground")) && validLayer(raw.get("Items"));
        s.playerMatches = s.player != null && players.length > 0 && players[0] == s.player
                && Integer.valueOf(0).equals(value(playerType, "l", int.class, s.player));
    }

    private void layer(Object layer, Map<String, Object> parent, String key) throws Exception {
        if (layer == null) { parent.put(key, null); return; }
        Map<String, Object> r = map(); parent.put(key, r); r.put("class", layer.getClass().getName());
        for (String name : new String[] {"k", "l"}) r.put(name, value(layerType, name, String.class, layer));
        for (String name : new String[] {"n", "o"}) r.put(name, value(layerType, name, int.class, layer));
    }

    private boolean validLayer(Object layer) {
        if (!(layer instanceof Map)) return false;
        Map<?, ?> r = (Map<?, ?>) layer;
        return Integer.valueOf(30).equals(r.get("n")) && Integer.valueOf(20).equals(r.get("o"));
    }

    private void player(Object player, Integer index, Object current, Object[] slots, Map<String, Object> r) throws Exception {
        if (player == null) return;
        r.put("index", index); r.put("class", player.getClass().getName());
        r.put("objectRelations", ReadbackEvidence.relations(player, slots, current));
        r.put("l", value(playerType, "l", int.class, player)); r.put("s", value(playerType, "s", int.class, player));
        r.put("w", value(playerType, "w", String.class, player));
    }

    private void unit(Object unit, Object[] players, Object current, Object factoryDefinition, Map<String, Object> r) throws Exception {
        InitialProtocol.require(unit != null && unitType.isInstance(unit), "native-table-item");
        r.put("class", unit.getClass().getName());
        r.put("ej", Long.toString((Long) value(baseType, "ej", long.class, unit)));
        Object owner = value(unitType, "bZ", playerType, unit); int index = -1;
        for (int i = 0; i < players.length; i++) if (players[i] == owner && owner != null) index = i;
        r.put("ownerIndex", index); r.put("ownerIsBp", owner != null && owner == current);
        for (String name : new String[] {"eq", "er"}) r.put(name, floatValue((Float) value(baseType, name, float.class, unit)));
        for (String name : new String[] {"ci", "cl", "cw", "cx", "cy", "cz"}) r.put(name, floatValue((Float) value(unitType, name, float.class, unit)));
        for (String name : new String[] {"bO", "bQ", "bR", "bN", "bX", "ca"}) r.put(name, value(unitType, name, boolean.class, unit));
        Object def = invoke(unitType, "q", elementType, false, unit, new Class<?>[0]);
        r.put("definitionClass", def == null ? null : def.getClass().getName());
        r.put("qIsFactoryDefinition", def == null || factoryDefinition == null ? null : ReadbackEvidence.same(def, factoryDefinition));
        r.put("factoryDefinitionKnown", factoryDefinition != null);
        r.put("customDefinitionFieldsApplicable", definitionType.isInstance(def));
        if (definitionType.isInstance(def)) {
            r.put("M", value(definitionType, "M", String.class, def)); r.put("D", value(definitionType, "D", String.class, def));
        }
    }

    void spawn(Object player, String ini, Spawn result) throws Exception {
        Object def = invoke(definitionType, "k", definitionType, true, null, new Class<?>[] {String.class}, "p001_weaponless_tank");
        InitialProtocol.require(def != null, "unit-definition-missing");
        boolean preparedSame = def == preparedDefinition && value(definitionType, "J", type("com.corrodinggames.rts.gameFramework.i.b"), def) == preparedMod;
        result.raw.put("preparedDefinitionSame", preparedSame);
        InitialProtocol.require(preparedSame, "unit-definition-not-prepared");
        result.definition = def; result.raw.put("definitionClass", def.getClass().getName());
        Object name = value(definitionType, "M", String.class, def); result.raw.put("M", name);
        Object source = value(definitionType, "D", String.class, def); result.raw.put("D", source);
        InitialProtocol.require("p001_weaponless_tank".equals(name), "unit-definition-alias:M=" + name + ";D=" + source);
        InitialProtocol.require(source instanceof String && ini.equals(source) && new File((String) source).getCanonicalPath().equals(ini), "unit-definition-source:M=" + name + ";D=" + source);
        Object unit = invoke(definitionType, "a", customType, true, null, new Class<?>[] {boolean.class, definitionType}, false, def);
        result.unit = unit; result.raw.put("unitCreated", unit != null); result.raw.put("lastCompletedOperation", "l.a(false,definition)");
        field(baseType, "eq", float.class, false).setFloat(unit, plan.x); field(baseType, "er", float.class, false).setFloat(unit, plan.y);
        invoke(unitType, "h", void.class, false, unit, new Class<?>[] {float.class}, plan.ci);
        field(unitType, "ci", float.class, false).setFloat(unit,plan.ci);
        invoke(unitType, "b", void.class, false, unit, new Class<?>[] {playerType}, player);
        field(unitType, "bQ", boolean.class, false).setBoolean(unit, false); field(unitType, "bR", boolean.class, false).setBoolean(unit, false);
        field(unitType, "bO", boolean.class, false).setBoolean(unit, true);
        result.raw.put("lastCompletedOperation", "position-heading-owner-flags");
        invoke(unitType, "m", void.class, false, unit, new Class<?>[0]);
        result.raw.put("lastCompletedOperation", "m");
        invoke(playerType, "c", void.class, true, null, new Class<?>[] {unitType}, unit);
        result.raw.put("lastCompletedOperation", "p.c");
        invoke(baseType, "dk", void.class, true, null, new Class<?>[0]);
        result.raw.put("lastCompletedOperation", "ah.dk");
    }

    void requireInitial(Snapshot before, Snapshot after, Spawn factoryResult) throws Exception {
        InitialReadbackGate.requireInitial(after.loading, after.ready, after.mapReady, before.units.length, after.units.length,
                after.units.length == 1 && after.units[0] == factoryResult.unit, after.pathMatches && after.dimensionsMatch && after.playerMatches,
                after.units.length == 1 && (Boolean) value(unitType, "bN", boolean.class, after.units[0]),
                after.units.length == 1 && (Boolean) value(unitType, "bX", boolean.class, after.units[0]),
                after.units.length == 1 && (Boolean) value(unitType, "ca", boolean.class, after.units[0]));
        Object unit = after.units[0];
        InitialProtocol.require(!running(after.engine) && value(unitType, "bZ", playerType, unit) == before.player
                && after.player == before.player && after.map == before.map, "native-world-identity");
        InitialProtocol.require((Long) value(baseType, "ej", long.class, unit) != 0L, "native-ej-zero");
        InitialProtocol.require(invoke(unitType, "q", elementType, false, unit, new Class<?>[0]) == factoryResult.definition, "native-definition-identity");
        InitialProtocol.require(Float.floatToRawIntBits((Float) value(baseType, "eq", float.class, unit)) == Float.floatToRawIntBits(plan.x)
                && Float.floatToRawIntBits((Float) value(baseType, "er", float.class, unit)) == Float.floatToRawIntBits(plan.y), "native-position-readback");
        for (String name : new String[] {"ci", "cl", "cw", "cx", "cy", "cz"}) {
            float observed = (Float) value(unitType, name, float.class, unit);
            InitialProtocol.require(!Float.isNaN(observed) && !Float.isInfinite(observed), "native-nonfinite:" + name);
        }
        InitialProtocol.require((Boolean) value(unitType, "bO", boolean.class, unit)
                && !(Boolean) value(unitType, "bQ", boolean.class, unit)
                && !(Boolean) value(unitType, "bR", boolean.class, unit), "native-spawn-flags");
    }

    static Map<String, Object> floatValue(float value) {
        Map<String, Object> r = map(); r.put("bits", String.format(java.util.Locale.ROOT, "%08x", Float.floatToRawIntBits(value)));
        r.put("value", Float.isNaN(value) || Float.isInfinite(value) ? null : value);
        r.put("finite", !Float.isNaN(value) && !Float.isInfinite(value)); return r;
    }

    static Map<String, Object> map() { return new LinkedHashMap<String, Object>(); }
    static final class Spawn {
        Object unit, definition; final Map<String, Object> raw = map();
    }
    static final class Snapshot {
        final Map<String, Object> raw = map(); Object engine, map, player; Object[] units;
        Throwable readFailure;
        boolean loading, ready, mapReady, pathMatches, dimensionsMatch, playerMatches;
        void requireReadable() throws Throwable { if (readFailure != null) throw readFailure; }
        void requirePre(SceneAccess access) throws Exception {
            InitialProtocol.requireSnapshot(loading, ready, mapReady, units.length, 0, true, true, false, true);
            InitialProtocol.require(pathMatches && dimensionsMatch && playerMatches && !access.running(engine), "native-map-player-state");
        }
    }
}

package ftl.saves.js;

import ftl.saves.Catalogue;
import ftl.saves.CloudSave;
import ftl.saves.Json;
import ftl.saves.JsonKt;
import ftl.saves.LocalSave;
import ftl.saves.LockInfo;
import ftl.saves.Locks;
import ftl.saves.ModsKt;
import ftl.saves.NativeSave;
import ftl.saves.OldProfile;
import ftl.saves.RunSummary;
import ftl.saves.SaveMeta;
import ftl.saves.SaveNames;
import ftl.saves.Sha256;
import ftl.saves.Sync;
import ftl.saves.SyncDecision;
import java.util.ArrayList;
import java.util.List;
import org.teavm.jso.JSExport;

/**
 * The save core for the website (savecore.js, built by TeaVM like engine.js): the same Kotlin rules the Android app
 * runs, exported as functions that take and return JSON text (docs/SAVES.md). Loaded by web/savecore.mjs.
 */
public final class SaveCoreJs {
    private SaveCoreJs() {
    }

    public static void main(String[] args) {
    }

    private static Json parse(String json) {
        return json == null ? null : Json.Companion.parse(json);
    }

    private static String write(Json json) {
        return Json.Companion.write(json);
    }

    private static LocalSave local(String json) {
        return json == null ? null : LocalSave.Companion.fromJson(parse(json));
    }

    private static CloudSave cloud(String json) {
        return json == null ? null : CloudSave.Companion.fromJson(parse(json));
    }

    @JSExport
    public static String savecoreDecide(String localJson, String cloudJson, boolean deleted) {
        SyncDecision d = Sync.INSTANCE.decide(local(localJson), cloud(cloudJson), deleted);
        return "{\"status\":\"" + d.getStatus().name() + "\",\"action\":\"" + d.getAction().name() + "\",\"rename\":" + d.getRename() + "}";
    }

    @JSExport
    public static String savecoreWritten(String localJson, String run, String metaJson) {
        return write(Sync.INSTANCE.written(local(localJson), run, SaveMeta.Companion.fromJson(parse(metaJson))).toJson(true));
    }

    /** The save after the engine wrote its profile, or null when it did not change. */
    @JSExport
    public static String savecoreProfileWritten(String localJson, String xml) {
        LocalSave next = Sync.INSTANCE.profileWritten(local(localJson), xml);
        return next == null ? null : write(next.toJson(true));
    }

    @JSExport
    public static String savecoreUploaded(String localJson, String uploadedRun, String uploadedProfile, double revision) {
        return write(Sync.INSTANCE.uploaded(local(localJson), uploadedRun, uploadedProfile, (long) revision).toJson(true));
    }

    @JSExport
    public static String savecoreFetched(String localJson, String cloudJson, String run, String profile) {
        return write(Sync.INSTANCE.fetched(local(localJson), cloud(cloudJson), run, profile).toJson(true));
    }

    @JSExport
    public static String savecoreRunForSetup(String run, String setupKey) {
        return Sync.INSTANCE.runForSetup(run, setupKey);
    }

    @JSExport
    public static String savecoreKeepMine(String localJson, String cloudJson) {
        return write(Sync.INSTANCE.keepMine(local(localJson), cloud(cloudJson)).toJson(true));
    }

    @JSExport
    public static String savecoreOverridden(String localJson, String lockJson, String at) {
        return write(Sync.INSTANCE.overridden(local(localJson), LockInfo.Companion.fromJson(parse(lockJson)), at).toJson(true));
    }

    @JSExport
    public static String savecoreLockState(String lockJson, String instance) {
        return Locks.INSTANCE.state(LockInfo.Companion.fromJson(parse(lockJson)), instance).name();
    }

    /** {"profile": without the settings, "settings": the document they became, or null}. */
    @JSExport
    public static String savecoreSplitOldProfile(String xml) {
        OldProfile.Split split = OldProfile.INSTANCE.split(xml);
        return "{\"profile\":" + write(JsonKt.toJson(split.getProfile())) + ",\"settings\":" + write(JsonKt.toJson(split.getSettings())) + "}";
    }

    @JSExport
    public static String savecoreModSetHash(String sha256sJson) {
        List<String> list = new ArrayList<>();
        for (Json j : JsonKt.arr(parse(sha256sJson))) list.add(JsonKt.str(j));
        return ModsKt.modSetHash(list);
    }

    @JSExport
    public static String savecoreClassify(String catalogueJson, String fileName, String sha256, double size) {
        return write(Catalogue.INSTANCE.classify(Catalogue.INSTANCE.parse(catalogueJson), fileName, sha256, (long) size).toJson());
    }

    @JSExport
    public static String savecoreSummary(String run, double playSeconds) {
        return write(RunSummary.INSTANCE.read(run, (long) playSeconds).toJson());
    }

    @JSExport
    public static String savecoreCleanName(String name) {
        return SaveNames.INSTANCE.clean(name);
    }

    @JSExport
    public static String savecoreNextName(String namesJson) {
        List<String> list = new ArrayList<>();
        for (Json j : JsonKt.arr(parse(namesJson))) list.add(JsonKt.str(j));
        return SaveNames.INSTANCE.next(list);
    }

    @JSExport
    public static String savecoreSha256(String text) {
        return Sha256.INSTANCE.hex(text);
    }

    /**
     * Files of the original game chosen for import ([{name, base64}]): {prefix, setup, from, run: {file, summary},
     * profile: {file}, wrappedRun, wrappedProfile}, or {error} with the reason for the player.
     */
    @JSExport
    public static String savecoreNativePick(String filesJson) {
        try {
            List<NativeSave.Found> found = new ArrayList<>();
            for (Json j : JsonKt.arr(parse(filesJson))) {
                String name = JsonKt.str(((Json.Obj) j).get("name"));
                byte[] bytes;
                try {
                    bytes = java.util.Base64.getDecoder().decode(JsonKt.str(((Json.Obj) j).get("base64")));
                } catch (IllegalArgumentException e) {
                    throw new NativeSave.Invalid(NativeSave.NOT_A_SAVE);
                }
                found.add(NativeSave.INSTANCE.identify(name == null ? "" : name, bytes));
            }
            NativeSave.Picked picked = NativeSave.INSTANCE.pick(found);
            StringBuilder out = new StringBuilder("{\"prefix\":").append(write(new Json.Str(picked.getPrefix())))
                .append(",\"setup\":").append(write(new Json.Str(picked.getSetup())))
                .append(",\"from\":").append(write(new Json.Str(NativeSave.INSTANCE.describe(picked.getPrefix()))));
            if (picked.getRun() != null) out.append(",\"run\":{\"file\":").append(write(new Json.Str(picked.getRun().getFileName())))
                .append(",\"summary\":").append(write(picked.getRun().getSummary().toJson())).append("},\"wrappedRun\":").append(write(new Json.Str(picked.getWrappedRun())));
            if (picked.getProfile() != null) out.append(",\"profile\":{\"file\":").append(write(new Json.Str(picked.getProfile().getFileName())))
                .append("},\"wrappedProfile\":").append(write(new Json.Str(picked.getWrappedProfile())));
            return out.append("}").toString();
        } catch (NativeSave.Invalid e) {
            return "{\"error\":" + write(new Json.Str(e.getMessage())) + "}";
        }
    }
}

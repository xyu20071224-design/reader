import java.util.*;
import java.util.zip.*;
import com.tencent.tinker.android.dex.Dex;
import com.tencent.tinker.android.dex.ClassDef;
import com.tencent.tinker.android.dex.ClassData;
import com.tencent.tinker.build.util.DexClassesComparator;

/** Verify immutable loader classes across every DEX, including ART native multidex. */
public final class CheckHotfixLoader {
    private static Dex withoutLoaderDebugInfo(Dex dex) {
        // R8 shares PC-to-line tables between methods. An unrelated method can change the
        // shared table's length past this method's last instruction, fooling Tinker's comparator.
        // Ignore only debug offsets in this in-memory comparison, never in either input APK.
        // Signatures, instructions, constants, annotations, catches and access flags remain checked.
        for (ClassDef type : dex.classDefs()) {
            String name = dex.typeNames().get(type.typeIndex);
            if (!(name.startsWith("Lcom/tencent/tinker/loader/") ||
                    name.equals("Lcom/linguareader/app/hotfix/bootstrap/ReaderApplication;")) || type.classDataOffset == 0) continue;
            ClassData data = dex.readClassData(type);
            for (ClassData.Method[] methods : new ClassData.Method[][] {data.directMethods, data.virtualMethods}) {
                for (ClassData.Method method : methods) {
                    if (method.codeOffset != 0) dex.openSection(method.codeOffset + 8).writeInt(0);
                }
            }
        }
        return dex;
    }
    private static Dex[] dexes(String apk) throws Exception {
        List<Dex> result = new ArrayList<>();
        try (ZipFile file = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().matches("classes[0-9]*\\.dex")) {
                    try (java.io.InputStream in = file.getInputStream(entry)) { result.add(withoutLoaderDebugInfo(new Dex(in.readAllBytes()))); }
                }
            }
        }
        return result.toArray(new Dex[0]);
    }
    public static void main(String[] args) throws Exception {
        DexClassesComparator check = new DexClassesComparator("com.tencent.tinker.loader.*", "com.linguareader.app.hotfix.bootstrap.ReaderApplication");
        check.startCheck(DexClassesComparator.DexGroup.wrap(dexes(args[0])), DexClassesComparator.DexGroup.wrap(dexes(args[1])));
        if (!check.getAddedClassInfos().isEmpty() || !check.getDeletedClassInfos().isEmpty() || !check.getChangedClassDescToInfosMap().isEmpty()) {
            System.err.println("Added loaders: " + check.getAddedClassInfos());
            System.err.println("Removed loaders: " + check.getDeletedClassInfos());
            System.err.println("Changed loaders: " + check.getChangedClassDescToInfosMap().keySet());
            throw new IllegalArgumentException("Hotfix loader changed: publish a new base APK");
        }
        System.out.println("Loader runtime semantics unchanged across all DEX files (debug tables excluded).");
    }
}

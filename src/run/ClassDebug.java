package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.sections.classes.DwgClassDefinition;
import io.dwg.sections.classes.DwgClassRegistry;
import io.dwg.entities.DwgObject;

import java.io.File;

public class ClassDebug {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "samples/2018/Dynblocks.dwg";
        File f = new File(path);
        if (!f.exists()) { System.out.println("No file: " + path); return; }

        DwgDocument doc = DwgReader.defaultReader().open(f.toPath());
        System.out.println("=== " + path + " (" + doc.version() + ") ===");

        // 1. Check custom classes (from Classes section)
        java.util.List<DwgClassDefinition> customClasses = doc.customClasses();
        System.out.println("\n1. Classes section entries: " + (customClasses == null ? 0 : customClasses.size()));
        if (customClasses != null) {
            for (int i = 0; i < Math.min(customClasses.size(), 30); i++) {
                DwgClassDefinition def = customClasses.get(i);
                System.out.printf("   #%d classNum=%-5d version=%-5d dxf='%s' cpp='%s' isEntity=%s%n",
                    i, def.classNumber(), def.version(), def.dxfRecordName(),
                    def.cppClassName(), def.isEntity());
            }
        }

        // 2. Check class registry
        DwgClassRegistry registry = doc.classRegistry();
        System.out.println("\n2. ClassRegistry entries:");
        if (registry != null) {
            int count = 0;
            for (int n = 1; n < 5000 && count < 30; n++) {
                java.util.Optional<DwgClassDefinition> def = registry.find(n);
                if (def.isPresent()) {
                    DwgClassDefinition d = def.get();
                    System.out.printf("   classNum=%-5d dxf='%s' entity=%s%n",
                        n, d.dxfRecordName(), d.isEntity());
                    count++;
                }
            }
            if (count == 0) System.out.println("   (none found)");
        } else {
            System.out.println("   (registry is null)");
        }

        // 3. Object summary
        java.util.Map<Long, DwgObject> map = doc.objectMap();
        System.out.println("\n3. Object map size: " + (map == null ? 0 : map.size()));

        // 4. Dump objects and try to identify which are blocks
        System.out.println("\n4. Looking for block-related objects:");
        if (map != null) {
            int blkCount = 0;
            for (DwgObject o : map.values()) {
                String cls = o.getClass().getSimpleName();
                if (cls.contains("Block") || cls.contains("Insert") || o.rawTypeCode() == 0x30 || o.rawTypeCode() == 0x31) {
                    System.out.printf("   handle=0x%X typeCode=0x%X (%d) class=%s%n",
                        o.handle(), o.rawTypeCode(), o.rawTypeCode(), cls);
                    blkCount++;
                }
            }
            if (blkCount == 0) System.out.println("   (no block objects found)");
        }

        // 5. Distribution
        System.out.println("\n5. Object type distribution:");
        java.util.Map<String, Integer> dist = new java.util.TreeMap<>();
        if (map != null) {
            for (DwgObject o : map.values()) {
                dist.merge(o.getClass().getSimpleName() + " (rawType=" + o.rawTypeCode() + ")", 1, Integer::sum);
            }
            for (var e : dist.entrySet()) {
                System.out.printf("   %s: %d%n", e.getKey(), e.getValue());
            }
        }
    }
}

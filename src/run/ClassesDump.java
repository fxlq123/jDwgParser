package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.SectionInputStream;
import io.dwg.core.version.DwgVersion;

import java.io.File;
import java.util.Map;

public class ClassesDump {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "samples/2018/Dynblocks.dwg";
        File f = new File(path);
        if (!f.exists()) { System.out.println("No file: " + path); return; }

        DwgDocument doc = DwgReader.defaultReader().open(f.toPath());
        System.out.println("=== " + path + " (" + doc.version() + ") ===");

        // Try to access classes section directly - but we need to re-open
        // Let's try another approach: dump first bytes of rawBytes
        // Actually, the doc doesn't expose sections directly, so let me check customClasses
        System.out.println("customClasses count: " + (doc.customClasses() == null ? 0 : doc.customClasses().size()));

        // Dump first bytes from the Classes section through reflection is not possible
        // Let me instead look at what's in the map for the document's layerTable/entities
        System.out.println("\nTotal objects in map: " + (doc.objectMap() == null ? 0 : doc.objectMap().size()));
        System.out.println("Total entities: " + (doc.entities() == null ? 0 : doc.entities().size()));
        System.out.println("Layers: " + (doc.layers() == null ? 0 : doc.layers().size()));

        // The real issue: the Classes section format might be different for R2007+
        // Let me try to understand by checking what section structure the file uses
        // For R2007+, sections are decompressed through the section map
        // The ClassesSectionParser expects: START_SENTINEL(16) + sectionSize(RL) + data + END_SENTINEL
        // But R2007+ sections might NOT have sentinels in the same format
    }
}

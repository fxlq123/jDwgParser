package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.io.SectionInputStream;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.DwgFileStructureHandler;
import io.dwg.format.common.DwgFileStructureHandlerFactory;
import io.dwg.sections.classes.ClassesSectionParser;
import io.dwg.sections.classes.DwgClassDefinition;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class ClassSectionDiagnostic {
    public static void main(String[] args) throws Exception {
        String[] testFiles = {
            args.length > 0 ? args[0] : "samples/2018/Dynblocks.dwg",
            "samples/2018/circle.dwg",
            "samples/2007/circle.dwg",
            "samples/2004/circle.dwg",
            "samples/2000/circle.dwg",
        };

        for (String path : testFiles) {
            File f = new File(path);
            if (!f.exists()) { System.out.println("SKIP: " + path); continue; }

            byte[] data = Files.readAllBytes(f.toPath());
            System.out.println("\n=== " + path + " ===");

            DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.detect(data);
            Map<String, SectionInputStream> sections = handler.readSections(
                wrapInput(data), null);

            SectionInputStream classStream = sections.get("AcDb:Classes");
            if (classStream == null) {
                System.out.println("No Classes section");
                // Try alternate names
                for (var e : sections.entrySet()) {
                    if (e.getKey().toLowerCase().contains("class")) {
                        System.out.println("Found: " + e.getKey());
                        classStream = e.getValue();
                    }
                }
            }

            if (classStream != null) {
                byte[] raw = classStream.rawBytes();
                System.out.println("Classes section size: " + raw.length + " bytes");

                System.out.print("First 64 bytes: ");
                for (int i = 0; i < Math.min(64, raw.length); i++) {
                    System.out.printf("%02X ", raw[i] & 0xFF);
                    if ((i + 1) % 16 == 0) System.out.print("\n               ");
                }
                System.out.println();

                // Check if it starts with sentinel 8D A1 C4 B8
                if (raw.length >= 4) {
                    System.out.println("First bytes: " + String.format("%02X%02X%02X%02X",
                        raw[0] & 0xFF, raw[1] & 0xFF, raw[2] & 0xFF, raw[3] & 0xFF));
                }

                // Try parsing
                try {
                    DwgVersion ver = DwgVersionDetector2.detect(data);
                    List<DwgClassDefinition> classes = new ClassesSectionParser().parse(classStream, ver);
                    System.out.println("Parsed classes: " + classes.size());
                    for (int i = 0; i < Math.min(classes.size(), 10); i++) {
                        DwgClassDefinition c = classes.get(i);
                        System.out.printf("  %d: classNum=%d version=%d dxf='%s' cpp='%s' entity=%s%n",
                            i, c.classNumber(), c.version(), c.dxfRecordName(), c.cppClassName(), c.isEntity());
                    }
                } catch (Exception e) {
                    System.out.println("Parse error: " + e.getMessage());
                }
            }
        }
    }

    private static io.dwg.core.io.BitInput wrapInput(byte[] data) {
        return new io.dwg.core.io.ByteBufferBitInput(data);
    }
}

class DwgVersionDetector2 {
    public static DwgVersion detect(byte[] data) {
        String sig = new String(data, 0, 6);
        switch (sig) {
            case "AC1032": return DwgVersion.R2018;
            case "AC1027": return DwgVersion.R2013;
            case "AC1024": return DwgVersion.R2010;
            case "AC1021": return DwgVersion.R2007;
            case "AC1018": return DwgVersion.R2004;
            case "AC1015": return DwgVersion.R2000;
            default: return DwgVersion.R13;
        }
    }
}

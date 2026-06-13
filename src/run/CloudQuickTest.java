package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.version.DwgVersion;
import java.io.File;

public class CloudQuickTest {
    public static void main(String[] args) {
        System.out.println("=== jDwgParser Cloud Quick Test ===");
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println();

        String[] samplePaths = {
            "samples/2004/circle.dwg",
            "samples/2007/circle.dwg",
            "samples/2010/circle.dwg",
            "samples/2013/circle.dwg",
            "samples/2018/circle.dwg",
            "samples/2000/circle.dwg",
        };

        int passed = 0;
        int failed = 0;

        for (String path : samplePaths) {
            System.out.printf("[%s] ", path);
            try {
                File f = new File(path);
                if (!f.exists()) {
                    System.out.println("SKIP (file not found)");
                    continue;
                }

                DwgDocument doc = DwgReader.defaultReader().open(f.toPath());
                DwgVersion ver = doc.version();
                int objs = doc.objectMap() != null ? doc.objectMap().size() : 0;
                int entities = doc.entities() != null ? doc.entities().size() : 0;

                System.out.printf("OK  ver=%s  objects=%d  entities=%d%n", ver, objs, entities);
                passed++;
            } catch (Exception e) {
                System.out.printf("FAIL %s%n", e.getMessage());
                failed++;
            }
        }

        System.out.println();
        System.out.printf("Result: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }
}

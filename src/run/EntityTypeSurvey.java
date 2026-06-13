package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgBlockEnd;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class EntityTypeSurvey {
    public static void main(String[] args) {
        String[] candidates = {
            "samples/2018/Constraints.dwg",
            "samples/2013/Constraints.dwg",
            "samples/2007/Constraints.dwg",
            "samples/2004/Constraints.dwg",
            "samples/2000/Arc.dwg",
            "samples/r2.10/entities.dwg",
            "samples/r10/entities.dwg",
            "samples/r1.4/entities.dwg",
            "samples/r9/entities.dwg",
            "samples/r14/Constraints.dwg",
            "samples/r14/Leader.dwg",
            "samples/example_2018.dwg",
            "samples/sample_2018.dwg",
        };

        for (String path : candidates) {
            File f = new File(path);
            if (!f.exists()) { System.out.printf("%-34s  NOT FOUND%n", path); continue; }

            try {
                DwgDocument doc = DwgReader.defaultReader().open(f.toPath());
                System.out.println();
                System.out.println("=".repeat(80));
                System.out.println(path + "  [ver=" + doc.version() + "]");
                System.out.println("=".repeat(80));

                Map<Long, DwgObject> map = doc.objectMap();
                if (map == null || map.isEmpty()) {
                    System.out.println("  (empty object map)");
                    continue;
                }

                // Entity type counts
                Map<Integer, Integer> byCode = new TreeMap<>();
                int total = 0;
                for (DwgObject o : map.values()) {
                    byCode.merge(o.rawTypeCode(), 1, Integer::sum);
                    total++;
                }

                System.out.println("  Total objects: " + total);
                System.out.println("  By type code:");
                for (var e : byCode.entrySet()) {
                    int code = e.getKey();
                    String hex = "0x" + Integer.toHexString(code).toUpperCase();
                    System.out.printf("    %-6s (%-3d) -> %d%n", hex, code, e.getValue());
                }

                // BLOCK_HEADER names
                System.out.println("  BLOCK_HEADER names:");
                for (DwgObject o : map.values()) {
                    if (o instanceof DwgBlockHeader bh) {
                        System.out.printf("    handle=0x%X  name=%-24s  flags=0x%04X%n",
                            o.handle(), bh.blockName(), bh.flags());
                    }
                }
                long be = map.values().stream().filter(o -> o instanceof DwgBlockEnd).count();
                System.out.println("  BLOCK_END count: " + be);

            } catch (Exception e) {
                System.out.printf("%-34s  ERROR: %s%n", path, e.getMessage());
            }
        }
    }
}

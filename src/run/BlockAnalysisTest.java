package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.core.version.DwgVersion;
import io.dwg.entities.DwgEntity;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgBlockEnd;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.entities.concrete.DwgLayer;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class BlockAnalysisTest {

    static class FileStats {
        String path;
        DwgVersion version;
        int totalObjects;
        int blockHeaders;
        int blockEnds;
        int inserts;
        int layers;
        List<String> blockNames = new ArrayList<>();
        Map<String, Integer> insertByBlock = new LinkedHashMap<>();
        boolean failed = false;
        String error = null;
    }

    public static void main(String[] args) {
        String[] candidates = {
            "samples/r2.10/block.dwg",
            "samples/2018/Arc.dwg",
            "samples/2018/Constraints.dwg",
            "samples/2013/Constraints.dwg",
            "samples/2010/Constraints.dwg",
            "samples/2007/Constraints.dwg",
            "samples/2004/Constraints.dwg",
            "samples/2000/Arc.dwg",
            "samples/2018/circle.dwg",
            "samples/2013/circle.dwg",
            "samples/2010/circle.dwg",
            "samples/2007/circle.dwg",
            "samples/2004/circle.dwg",
            "samples/2000/circle.dwg",
        };

        System.out.println("=".repeat(110));
        System.out.println("jDwgParser - BLOCK / INSERT Analysis");
        System.out.println("=".repeat(110));
        System.out.println();

        List<FileStats> all = new ArrayList<>();

        for (String path : candidates) {
            FileStats s = analyze(path);
            all.add(s);
        }

        // Summary table
        System.out.println();
        System.out.println("=".repeat(110));
        System.out.println("SUMMARY TABLE");
        System.out.println("=".repeat(110));
        System.out.printf("%-36s %-8s %8s %6s %6s %6s %6s%n",
            "FILE", "VERSION", "OBJECTS", "BLOCK_H", "BLOCK_E", "INSERT", "LAYER");
        System.out.println("-".repeat(110));

        int totalBlocks = 0, totalInserts = 0;
        for (FileStats s : all) {
            if (s.failed) {
                System.out.printf("%-36s FAIL  %s%n", truncate(s.path, 36), s.error);
                continue;
            }
            System.out.printf("%-36s %-8s %8d %6d %6d %6d %6d%n",
                truncate(s.path, 36), s.version, s.totalObjects,
                s.blockHeaders, s.blockEnds, s.inserts, s.layers);
            totalBlocks += s.blockHeaders;
            totalInserts += s.inserts;
        }

        System.out.println("-".repeat(110));
        System.out.printf("%-36s %8s %6d %6d %6d%n", "TOTALS", "", 0, totalBlocks, 0, totalInserts);
        System.out.println();

        // Find the best candidate for deep analysis
        FileStats best = all.stream()
            .filter(s -> !s.failed)
            .max(Comparator.comparingInt(s -> s.inserts + s.blockHeaders))
            .orElse(null);

        if (best != null && (best.inserts > 0 || best.blockHeaders > 2)) {
            System.out.println();
            System.out.println("=".repeat(110));
            System.out.println("DEEP ANALYSIS: " + best.path);
            System.out.println("=".repeat(110));
            deepPrint(best.path);
        } else {
            System.out.println("No sample file contains significant block data. "
                + "Upload your own DWG for deep analysis.");
        }
    }

    private static FileStats analyze(String path) {
        FileStats s = new FileStats();
        s.path = path;
        File f = new File(path);
        if (!f.exists()) { s.failed = true; s.error = "not found"; return s; }

        try {
            DwgDocument doc = DwgReader.defaultReader().open(f.toPath());
            s.version = doc.version();
            Map<Long, DwgObject> map = doc.objectMap();
            s.totalObjects = map == null ? 0 : map.size();

            if (map != null) {
                for (DwgObject o : map.values()) {
                    if (o instanceof DwgBlockHeader bh) {
                        s.blockHeaders++;
                        s.blockNames.add(bh.blockName());
                    } else if (o instanceof DwgBlockEnd) {
                        s.blockEnds++;
                    } else if (o instanceof DwgInsert ins) {
                        s.inserts++;
                        long bh = ins.blockHeaderHandle() != null ? ins.blockHeaderHandle().rawHandle() : 0L;
                        String refName = resolveFromHandle(map, bh);
                        if (refName == null || refName.isEmpty()) refName = "<handle=0x" + Long.toHexString(bh) + ">";
                        s.insertByBlock.merge(refName, 1, Integer::sum);
                    } else if (o instanceof DwgLayer) {
                        s.layers++;
                    }
                }
            }
            return s;
        } catch (Exception e) {
            s.failed = true;
            s.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return s;
        }
    }

    private static void deepPrint(String path) {
        try {
            DwgDocument doc = DwgReader.defaultReader().open(Paths.get(path));
            System.out.println("Version: " + doc.version());
            System.out.println("Total objects: " + (doc.objectMap() == null ? 0 : doc.objectMap().size()));
            System.out.println("Total entities: " + (doc.entities() == null ? 0 : doc.entities().size()));
            System.out.println();

            // Layers
            System.out.println("--- LAYERS ---");
            for (DwgLayer ly : doc.layers()) {
                int ci = ly.color() != null ? ly.color().getColorIndex() : -1;
                System.out.printf("  %-24s  color=%d%n", ly.name(), ci);
            }
            System.out.println();

            // Block definitions
            Map<Long, DwgObject> map = doc.objectMap();
            System.out.println("--- BLOCK DEFINITIONS (BLOCK_HEADER) ---");
            int bc = 0;
            for (DwgObject o : map.values()) {
                if (o instanceof DwgBlockHeader bh) {
                    bc++;
                    System.out.printf("  #%-3d  handle=0x%X  name=%-24s  flags=0x%04X  base=(%s)%n",
                        bc, o.handle(), bh.blockName(), bh.flags(), bh.basePoint());
                }
            }
            System.out.println("  (total: " + bc + ")");
            System.out.println();

            // INSERT references
            System.out.println("--- BLOCK INSERTIONS (INSERT references) ---");
            int ic = 0;
            for (DwgObject o : map.values()) {
                if (o instanceof DwgInsert ins) {
                    ic++;
                    long bhRef = ins.blockHeaderHandle() != null ? ins.blockHeaderHandle().rawHandle() : 0L;
                    String blockName = resolveFromHandle(map, bhRef);
                    System.out.printf("  #%-3d  handle=0x%X  ->blockHandle=0x%-10X  name=%-24s  pos=(%s)  scale=(%.3f,%.3f,%.3f)  rot=%.4f%n",
                        ic, o.handle(), bhRef, blockName, ins.insertionPoint(),
                        ins.xScale(), ins.yScale(), ins.zScale(), ins.rotation());
                    if (ic > 50) { System.out.println("  ... (truncated)"); break; }
                }
            }
            System.out.println("  (total inserts: " + ic + ")");
            System.out.println();

            // Entity summary
            System.out.println("--- ENTITY TYPE COUNTS ---");
            Map<String, Integer> typeCount = new TreeMap<>();
            for (DwgEntity e : doc.entities()) {
                typeCount.merge(e.objectType().name(), 1, Integer::sum);
            }
            typeCount.forEach((k, v) -> System.out.printf("  %-24s %d%n", k, v));

        } catch (Exception e) {
            System.out.println("Deep print failed: " + e);
            e.printStackTrace();
        }
    }

    private static String truncate(String s, int w) {
        return s.length() <= w ? s : s.substring(0, w - 3) + "...";
    }

    private static String fmt(Double d) { return d == null ? "?" : String.format("%.3f", d); }

    private static String resolveFromHandle(Map<Long, DwgObject> map, long handle) {
        if (map == null || handle == 0L) return null;
        DwgObject o = map.get(handle);
        if (o instanceof DwgBlockHeader bh) return bh.blockName();
        return "0x" + Long.toHexString(handle);
    }
}

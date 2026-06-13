package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;

import java.nio.file.Paths;
import java.util.Map;

public class TryParseUserFile {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        if (!new java.io.File(path).exists()) {
            path = "samples/2018/Dynblocks.dwg";
            System.out.println("Using alternate file: " + path);
        }
        
        System.out.println("Parsing: " + path);
        long startTime = System.currentTimeMillis();
        
        try {
            DwgDocument doc = DwgReader.defaultReader().open(Paths.get(path));
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("Parsed in " + elapsed + "ms");
            System.out.println("Version: " + doc.version());
            
            Map<Long, DwgObject> objects = doc.objectMap();
            System.out.println("Total objects: " + objects.size());
            
            // Count by type
            Map<String, Integer> typeCounts = new java.util.LinkedHashMap<>();
            int blockHeaders = 0, blockEnds = 0, inserts = 0, mInserts = 0;
            int lines = 0, circles = 0, arcs = 0, polylines = 0, text = 0;
            
            for (Map.Entry<Long, DwgObject> entry : objects.entrySet()) {
                DwgObject obj = entry.getValue();
                String name = obj.getClass().getSimpleName();
                typeCounts.merge(name, 1, Integer::sum);
                
                if (name.contains("BlockH") || name.contains("BlockHeader")) blockHeaders++;
                else if (name.contains("BlockEnd") || name.contains("EndBlk")) blockEnds++;
                else if (name.equals("DwgInsert")) inserts++;
                else if (name.equals("DwgMinsert")) mInserts++;
                else if (name.equals("DwgLine")) lines++;
                else if (name.equals("DwgCircle")) circles++;
                else if (name.equals("DwgArc")) arcs++;
                else if (name.equals("DwgLwPolyline") || name.equals("DwgPolyline2D")) polylines++;
                else if (name.equals("DwgText") || name.equals("DwgMText")) text++;
            }
            
            System.out.println("\n=== Object type summary ===");
            typeCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(25)
                .forEach(e -> System.out.printf("  %s: %d%n", e.getKey(), e.getValue()));
            
            System.out.println("\n=== Block-related ===");
            System.out.println("Block Header (BLOCK): " + blockHeaders);
            System.out.println("Block End (ENDBLK): " + blockEnds);
            System.out.println("Insert: " + inserts);
            System.out.println("Minsert: " + mInserts);
            
            System.out.println("\n=== Geometry entities ===");
            System.out.println("Lines: " + lines);
            System.out.println("Circles: " + circles);
            System.out.println("Arcs: " + arcs);
            System.out.println("Polylines: " + polylines);
            System.out.println("Text/MText: " + text);
            
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }
}

package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.entities.concrete.DwgBlockEnd;
import io.dwg.entities.concrete.DwgLine;
import io.dwg.entities.concrete.DwgCircle;
import io.dwg.entities.concrete.DwgArc;
import io.dwg.entities.concrete.DwgText;
import io.dwg.entities.concrete.DwgMText;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BlockReport {
    
    static class BlockInfo {
        long handle;
        String name;
        String blockType;
        List<String> properties = new ArrayList<>();
        BlockInfo(long h) { this.handle = h; }
    }
    
    static class InsertInfo {
        long handle;
        String blockName;
        String position;
        List<String> properties = new ArrayList<>();
    }
    
    public static void main(String[] args) throws Exception {
        String[] paths = {
            "samples/2018/210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg",
            "samples/2018/Dynblocks.dwg",
        };
        
        String path = null;
        for (String p : paths) {
            if (new java.io.File(p).exists()) { path = p; break; }
        }
        if (path == null) { System.out.println("No R2018 DWG found."); return; }
        
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              DWG BLOCK ANALYSIS REPORT                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("File: " + path);
        
        DwgDocument doc = DwgReader.defaultReader().open(Paths.get(path));
        System.out.println("Version: " + doc.version());
        
        Map<Long, DwgObject> objects = doc.objectMap();
        int totalObjects = objects.size();
        System.out.println("Total objects: " + totalObjects);
        System.out.println();
        
        // === Phase 1: Object type summary ===
        System.out.println("┌───────────────────────────────────────────────────────────────┐");
        System.out.println("│ OBJECT TYPE BREAKDOWN                                         │");
        System.out.println("└───────────────────────────────────────────────────────────────┘");
        
        Map<String, Integer> typeCounts = new java.util.LinkedHashMap<>();
        int totalEntities = 0;
        for (DwgObject obj : objects.values()) {
            String name = obj.getClass().getSimpleName();
            typeCounts.merge(name, 1, Integer::sum);
            if (isEntity(obj)) totalEntities++;
        }
        
        typeCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .limit(25)
            .forEach(e -> {
                int pct = (int)((e.getValue() * 100.0) / totalObjects);
                System.out.printf("  %-25s : %5d  (%d%%)%n", e.getKey(), e.getValue(), pct);
            });
        System.out.println("  " + "-".repeat(55));
        System.out.printf("  %-25s : %5d%n", "Total entities", totalEntities);
        System.out.println();
        
        // === Phase 2: Block Definitions ===
        System.out.println("┌───────────────────────────────────────────────────────────────┐");
        System.out.println("│ BLOCK DEFINITIONS (BLOCK_HEADER objects)                      │");
        System.out.println("└───────────────────────────────────────────────────────────────┘");
        
        List<BlockInfo> blocks = new ArrayList<>();
        Map<Long, String> blockHandleToName = new java.util.HashMap<>();
        int blockNum = 1;
        for (Map.Entry<Long, DwgObject> entry : objects.entrySet()) {
            DwgObject obj = entry.getValue();
            if (obj instanceof DwgBlockHeader) {
                BlockInfo bi = new BlockInfo(entry.getKey());
                
                // Try to get block name from object fields
                bi.name = extractBlockName(obj, entry.getKey());
                bi.blockType = determineBlockType(bi.name);
                bi.properties = extractProperties(obj);
                
                blocks.add(bi);
                blockHandleToName.put(entry.getKey(), bi.name);
                
                System.out.println();
                System.out.println("  BLOCK #" + blockNum + ": " + bi.name);
                System.out.println("    Handle  : 0x" + Long.toHexString(entry.getKey()));
                System.out.println("    Type    : " + bi.blockType);
                for (String prop : bi.properties) {
                    System.out.println("    " + prop);
                }
                blockNum++;
            }
        }
        System.out.println();
        System.out.println("  Total block definitions: " + blocks.size());
        System.out.println();
        
        // === Phase 3: Block References (INSERT) ===
        System.out.println("┌───────────────────────────────────────────────────────────────┐");
        System.out.println("│ BLOCK REFERENCES (INSERT objects)                             │");
        System.out.println("└───────────────────────────────────────────────────────────────┘");
        
        List<InsertInfo> inserts = new ArrayList<>();
        int insertNum = 1;
        Map<String, Integer> insertByBlock = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, DwgObject> entry : objects.entrySet()) {
            DwgObject obj = entry.getValue();
            if (obj instanceof DwgInsert) {
                InsertInfo ii = new InsertInfo();
                ii.handle = entry.getKey();
                ii.blockName = extractInsertBlockName(obj);
                ii.position = extractPosition(obj);
                ii.properties = extractProperties(obj);
                inserts.add(ii);
                insertByBlock.merge(ii.blockName, 1, Integer::sum);
                
                System.out.println();
                System.out.println("  INSERT #" + insertNum + ": " + ii.blockName);
                System.out.println("    Handle  : 0x" + Long.toHexString(entry.getKey()));
                System.out.println("    Position: " + ii.position);
                for (String prop : ii.properties) {
                    System.out.println("    " + prop);
                }
                insertNum++;
            }
        }
        System.out.println();
        System.out.println("  Total block references: " + inserts.size());
        System.out.println();
        if (!insertByBlock.isEmpty()) {
            System.out.println("  Block reference frequency:");
            insertByBlock.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> System.out.printf("    %-30s : %d%n", e.getKey(), e.getValue()));
        }
        System.out.println();
        
        // === Phase 4: Geometry Entities ===
        System.out.println("┌───────────────────────────────────────────────────────────────┐");
        System.out.println("│ GEOMETRY ENTITIES (by type)                                   │");
        System.out.println("└───────────────────────────────────────────────────────────────┘");
        
        int lines = 0, circles = 0, arcs = 0, texts = 0;
        for (DwgObject obj : objects.values()) {
            if (obj instanceof DwgLine) lines++;
            else if (obj instanceof DwgCircle) circles++;
            else if (obj instanceof DwgArc) arcs++;
            else if (obj instanceof DwgText || obj instanceof DwgMText) texts++;
        }
        
        System.out.println("  LINES     : " + lines);
        System.out.println("  CIRCLES   : " + circles);
        System.out.println("  ARCS      : " + arcs);
        System.out.println("  TEXT/MTEXT: " + texts);
        System.out.println();
        
        // === Phase 5: Block-end markers ===
        System.out.println("┌───────────────────────────────────────────────────────────────┐");
        System.out.println("│ BLOCK END MARKERS                                             │");
        System.out.println("└───────────────────────────────────────────────────────────────┘");
        
        int blockEndCount = 0;
        for (DwgObject obj : objects.values()) {
            if (obj instanceof DwgBlockEnd) blockEndCount++;
        }
        System.out.println("  BLOCK_END objects: " + blockEndCount);
        System.out.println();
        
        // === Summary ===
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    SUMMARY                                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("  File              : " + path);
        System.out.println("  DWG Version       : " + doc.version());
        System.out.println("  Total objects     : " + totalObjects);
        System.out.println("  Total entities    : " + totalEntities);
        System.out.println("  Block definitions : " + blocks.size());
        System.out.println("    - Model Space   : " + (int)blocks.stream().filter(b -> b.blockType.equals("Model Space")).count());
        System.out.println("    - Paper Space   : " + (int)blocks.stream().filter(b -> b.blockType.equals("Paper Space")).count());
        System.out.println("    - Custom blocks : " + (int)blocks.stream().filter(b -> b.blockType.equals("Custom Block")).count());
        System.out.println("  Block references  : " + inserts.size());
        System.out.println("  Block end markers : " + blockEndCount);
        System.out.println();
        
        System.out.println("  Block definitions found:");
        for (BlockInfo bi : blocks) {
            System.out.println("    - " + bi.name + " (0x" + Long.toHexString(bi.handle) + ")");
        }
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                END OF ANALYSIS REPORT                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
    
    static boolean isEntity(DwgObject obj) {
        String n = obj.getClass().getSimpleName();
        return n.contains("Line") || n.contains("Circle") || n.contains("Arc") ||
               n.contains("Polyline") || n.contains("Text") || n.contains("Insert") ||
               n.contains("Block") || n.contains("Dimension") || n.contains("Solid") ||
               n.contains("Surface") || n.contains("Body") || n.contains("Shape") ||
               n.contains("Viewport") || n.contains("Trace") || n.contains("Mline");
    }
    
    static String determineBlockType(String name) {
        if (name == null || name.isEmpty()) return "Custom Block";
        String ln = name.toUpperCase().trim();
        if (ln.contains("*MODEL")) return "Model Space";
        if (ln.contains("*PAPER")) return "Paper Space";
        if (ln.startsWith("*")) return "System Block";
        return "Custom Block";
    }
    
    static String extractBlockName(DwgObject obj, long handle) {
        // Try to get a readable name from the object using reflection
        try {
            for (Field f : getAllFields(obj.getClass())) {
                f.setAccessible(true);
                String fn = f.getName().toLowerCase();
                if (fn.contains("name") || fn.contains("block") || fn.equals("n")) {
                    Object val = f.get(obj);
                    if (val != null && val instanceof String && !((String)val).isEmpty()) {
                        return (String)val;
                    }
                }
            }
        } catch (Exception e) {}
        
        // Fallback: use toString() and extract name
        String str = obj.toString();
        if (str.contains("name") || str.contains("Name")) {
            int idx = str.toLowerCase().indexOf("name");
            int start = str.indexOf("=", idx);
            if (start > 0) {
                int end = str.indexOf(",", start);
                if (end > 0) return str.substring(start + 1, end).trim();
            }
        }
        return "Block_0x" + Long.toHexString(handle);
    }
    
    static String extractInsertBlockName(DwgObject obj) {
        try {
            for (Field f : getAllFields(obj.getClass())) {
                f.setAccessible(true);
                String fn = f.getName().toLowerCase();
                if (fn.contains("block") || fn.contains("name") || fn.contains("blockname")) {
                    Object val = f.get(obj);
                    if (val != null && val instanceof String && !((String)val).isEmpty()) {
                        return (String)val;
                    }
                }
            }
        } catch (Exception e) {}
        return "Unknown Block";
    }
    
    static String extractPosition(DwgObject obj) {
        double x = 0, y = 0, z = 0;
        boolean found = false;
        try {
            for (Field f : getAllFields(obj.getClass())) {
                f.setAccessible(true);
                String fn = f.getName().toLowerCase();
                Object val = f.get(obj);
                if (val instanceof Number) {
                    double d = ((Number)val).doubleValue();
                    if (fn.equals("x") || fn.contains("xcoord") || fn.contains("insx")) { x = d; found = true; }
                    else if (fn.equals("y") || fn.contains("ycoord") || fn.contains("insy")) { y = d; found = true; }
                    else if (fn.equals("z") || fn.contains("zcoord") || fn.contains("insz")) { z = d; found = true; }
                }
            }
        } catch (Exception e) {}
        if (found) return String.format("(%.2f, %.2f, %.2f)", x, y, z);
        return "(unknown)";
    }
    
    static List<String> extractProperties(DwgObject obj) {
        List<String> props = new ArrayList<>();
        try {
            for (Field f : getAllFields(obj.getClass())) {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val != null) {
                    String str = val.toString();
                    if (str.length() < 80 && !str.contains("@") && !str.equals("0") && !str.equals("0.0")) {
                        props.add(f.getName() + " = " + str);
                    }
                }
                if (props.size() >= 10) break;
            }
        } catch (Exception e) {}
        return props;
    }
    
    static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getName().startsWith("this$")) fields.add(f);
            }
        }
        return fields;
    }
}

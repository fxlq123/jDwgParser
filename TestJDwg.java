import io.dwg.DwgFile;
import io.dwg.core.version.DwgVersion;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.sections.handles.*;
import io.dwg.sections.objects.*;
import io.dwg.sections.classes.*;
import io.dwg.format.common.*;
import java.nio.file.Paths;
import java.util.*;

public class TestJDwg {
    public static void main(String[] args) throws Exception {
        String path = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        DwgFile file = DwgFile.open(Paths.get(path));
        DwgVersion version = file.getVersion();
        System.out.println("Version: " + version);

        // 检查 section 类型
        System.out.println("\n=== Sections ===");
        for (SectionInfo s : file.getSections()) {
            System.out.println("  " + s.getType() + " @ 0x" + Long.toHexString(s.getOffset()) + " size=" + s.getSize());
        }

        // 解析 objects
        System.out.println("\n=== Object Types Found ===");
        Map<Long, DwgObject> objects = file.getObjects();
        Map<Integer, Integer> typeCount = new LinkedHashMap<>();
        for (DwgObject obj : objects.values()) {
            int tc = obj.getRawTypeCode();
            typeCount.put(tc, typeCount.getOrDefault(tc, 0) + 1);
        }
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(typeCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        for (Map.Entry<Integer, Integer> e : sorted) {
            System.out.printf("  type=%4d: %5d  %s%n", e.getKey(), e.getValue(),
                DwgObjectType.names.getOrDefault(e.getKey(), "?"));
        }

        // BLOCK_HEADER
        System.out.println("\n=== BLOCK_HEADER ===");
        int bhCount = 0;
        for (DwgObject obj : objects.values()) {
            if (obj.getRawTypeCode() == 48) {
                bhCount++;
                try {
                    DwgBlockHeader bh = (DwgBlockHeader) obj;
                    System.out.printf("  [%d] h=0x%x name='%s'%n",
                        bhCount, obj.getHandle(), bh.getBlockName());
                } catch (Exception e) {
                    System.out.printf("  [%d] h=0x%x (cast err: %s)%n",
                        bhCount, obj.getHandle(), e.getMessage());
                }
            }
        }
        System.out.println("Total BLOCK_HEADER: " + bhCount);

        // INSERT
        System.out.println("\n=== INSERT ===");
        int insCount = 0;
        Map<Object, Integer> refCount = new LinkedHashMap<>();
        for (DwgObject obj : objects.values()) {
            if (obj.getRawTypeCode() == 7) {
                insCount++;
                try {
                    DwgInsert ins = (DwgInsert) obj;
                    Object bhRef = ins.getBlockHeaderHandle();
                    String name = "?";
                    // 查找对应的 BLOCK_HEADER
                    for (DwgObject obj2 : objects.values()) {
                        if (obj2.getRawTypeCode() == 48) {
                            Object h2 = obj2.getHandle();
                            if (bhRef != null && bhRef.equals(h2)) {
                                try {
                                    name = ((DwgBlockHeader) obj2).getBlockName();
                                } catch (Exception ex) {}
                            }
                        }
                    }
                    refCount.put(name, refCount.getOrDefault(name, 0) + 1);
                    if (insCount <= 20) {
                        System.out.printf("  [%d] block='%s'%n", insCount, name);
                    }
                } catch (Exception e) {}
            }
        }
        System.out.println("Total INSERT: " + insCount);
        System.out.println("\n引用统计:");
        for (Map.Entry<Object, Integer> e : refCount.entrySet()) {
            System.out.printf("  '%s': %d 次%n", e.getKey(), e.getValue());
        }
    }
}

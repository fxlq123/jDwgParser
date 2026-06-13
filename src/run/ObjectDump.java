package run;

import io.dwg.api.DwgDocument;
import io.dwg.api.DwgReader;
import io.dwg.entities.DwgObject;

import java.io.File;
import java.util.Map;

public class ObjectDump {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "samples/2018/Dynblocks.dwg";
        File f = new File(path);
        if (!f.exists()) { System.out.println("文件不存在: " + path); return; }

        DwgDocument doc = DwgReader.defaultReader().open(f.toPath());
        System.out.println("=== " + path + " (version: " + doc.version() + ") ===");
        System.out.println("对象总数: " + (doc.objectMap() == null ? 0 : doc.objectMap().size()));
        System.out.println();

        Map<Long, DwgObject> map = doc.objectMap();
        if (map == null) return;

        // print all objects
        for (DwgObject o : map.values()) {
            String hex = String.format("0x%04X", o.rawTypeCode());
            String name = o.objectType().name();
            String cls = o.getClass().getSimpleName();
            String handle = String.format("0x%X", o.handle());
            System.out.printf("handle=%-10s rawType=%-8s (%s) -> %s / %s%n",
                handle, hex, o.rawTypeCode(), name, cls);
        }

        // Check for BLOCK_HEADER by class
        System.out.println();
        System.out.println("--- 按 Java 类查找 BLOCK_HEADER ---");
        for (DwgObject o : map.values()) {
            if (o.getClass().getSimpleName().contains("Block") ||
                o.getClass().getSimpleName().contains("Insert")) {
                System.out.printf("  handle=0x%X rawType=0x%04X class=%s%n",
                    o.handle(), o.rawTypeCode(), o.getClass().getSimpleName());
            }
        }
    }
}

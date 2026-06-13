package run;

import io.dwg.core.io.BitInput;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.format.common.DwgFileStructureHandler;
import io.dwg.format.common.DwgFileStructureHandlerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

public class ListSections {
    public static void main(String[] args) throws Exception {
        String[] testFiles = {
            args.length > 0 ? args[0] : "samples/2018/Dynblocks.dwg",
            "samples/2018/circle.dwg",
            "samples/2013/circle.dwg",
            "samples/2010/circle.dwg",
            "samples/2007/circle.dwg",
            "samples/2004/circle.dwg",
            "samples/2000/circle.dwg",
        };

        for (String path : testFiles) {
            File f = new File(path);
            if (!f.exists()) { System.out.println("SKIP: " + path); continue; }

            byte[] data = Files.readAllBytes(f.toPath());
            System.out.println("\n=== " + path + " ===");
            System.out.println("Version: " + new String(data, 0, 6));

            try {
                DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.detect(data);
                Map<String, SectionInputStream> sections = handler.readSections(
                    new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data)), null);

                System.out.println("Sections: " + sections.size());
                for (Map.Entry<String, SectionInputStream> e : sections.entrySet()) {
                    byte[] raw = e.getValue().rawBytes();
                    System.out.printf("  [%s] %d bytes%n", e.getKey(), raw.length);

                    if (e.getKey().toLowerCase().contains("class")) {
                        System.out.print("  First 32 bytes: ");
                        for (int i = 0; i < Math.min(32, raw.length); i++) {
                            System.out.printf("%02X ", raw[i] & 0xFF);
                        }
                        System.out.println();
                    }
                }
            } catch (Exception ex) {
                System.out.println("Error: " + ex.getMessage());
            }
        }
    }
}

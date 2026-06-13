package run;

import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.io.SectionInputStream;
import io.dwg.format.common.DwgFileStructureHandler;
import io.dwg.format.common.DwgFileStructureHandlerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

public class DebugSections {
    public static void main(String[] args) throws Exception {
        String[] files = {
            "samples/2018/Dynblocks.dwg",
            "samples/2013/circle.dwg",
            "samples/2010/circle.dwg",
        };

        for (String path : files) {
            File f = new File(path);
            if (!f.exists()) { System.out.println("SKIP: " + path); continue; }

            byte[] data = Files.readAllBytes(f.toPath());
            System.out.println("\n=== " + path + " [" + new String(data, 0, 6) + "] ===");

            DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.detect(data);
            System.out.println("Handler class: " + handler.getClass().getName());
            System.out.println("Handler version: " + handler.version());
            System.out.println("Handler.version() supports detected? " + 
                handler.supports(handler.version()));

            Map<String, SectionInputStream> sections = handler.readSections(
                new ByteBufferBitInput(java.nio.ByteBuffer.wrap(data)), null);
            System.out.println("Sections: " + sections.size());
        }
    }
}

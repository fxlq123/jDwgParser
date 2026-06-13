package run;

import io.dwg.api.DwgReader;
import io.dwg.api.DwgDocument;
import io.dwg.entities.DwgEntity;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;
import io.dwg.entities.concrete.DwgInsert;
import io.dwg.entities.concrete.DwgLayer;

import java.nio.file.Paths;
import java.util.List;

public class TryParse2018 {
    public static void main(String[] args) {
        try {
            String path = "samples/2018/Dynblocks.dwg";
            System.out.println("Reading: " + path);
            
            DwgReader reader = DwgReader.defaultReader();
            DwgDocument doc = reader.open(Paths.get(path));
            
            System.out.println("Version: " + doc.version());
            System.out.println("Objects total: " + doc.objectMap().size());
            
            List<DwgEntity> entities = doc.entities();
            System.out.println("\n=== Entities: " + entities.size() + " ===");
            for (int i = 0; i < Math.min(30, entities.size()); i++) {
                DwgEntity e = entities.get(i);
                System.out.println("  [" + i + "] " + e.getClass().getSimpleName() + " " + e);
            }
            
            List<DwgBlockHeader> blocks = doc.objectsOfType(DwgBlockHeader.class);
            System.out.println("\n=== Block headers (BLOCK): " + blocks.size() + " ===");
            for (int i = 0; i < Math.min(30, blocks.size()); i++) {
                System.out.println("  [" + i + "] " + blocks.get(i));
            }
            
            List<DwgInsert> refs = doc.objectsOfType(DwgInsert.class);
            System.out.println("\n=== Block references (INSERT): " + refs.size() + " ===");
            for (int i = 0; i < Math.min(30, refs.size()); i++) {
                System.out.println("  [" + i + "] " + refs.get(i));
            }
            
            List<DwgLayer> layers = doc.layers();
            System.out.println("\n=== Layers: " + layers.size() + " ===");
            for (DwgLayer l : layers) {
                System.out.println("  " + l);
            }
            
        } catch (Exception e) {
            System.out.println("Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }
}

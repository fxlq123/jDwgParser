package run;

import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.api.DwgReader;
import io.dwg.api.DwgDocument;
import io.dwg.core.version.DwgVersion;
import io.dwg.entities.DwgObject;
import io.dwg.entities.concrete.DwgBlockHeader;

import java.nio.file.Paths;
import java.util.Map;

public class DiagnoseR2018Blocks {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        if (args.length > 0) path = args[0];
        
        System.out.println("文件: " + path);
        System.out.println("================================================");
        
        DwgDocument doc = DwgReader.defaultReader().open(Paths.get(path));
        System.out.println("版本: " + doc.version());
        System.out.println("对象总数: " + doc.objectMap().size());
        
        // 统计对象类型
        Map<String, Integer> typeCounts = new java.util.LinkedHashMap<>();
        for (DwgObject obj : doc.objectMap().values()) {
            String name = obj.getClass().getSimpleName();
            typeCounts.merge(name, 1, Integer::sum);
        }
        
        System.out.println("\n对象类型分布:");
        for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
            System.out.printf("  %-30s: %d%n", e.getKey(), e.getValue());
        }
        
        // 找到 BLOCK_HEADER 对象
        int blockCount = 0;
        for (DwgObject obj : doc.objectMap().values()) {
            if (obj instanceof DwgBlockHeader) {
                blockCount++;
                DwgBlockHeader bh = (DwgBlockHeader) obj;
                System.out.println("\n--- Block #" + blockCount + " ---");
                System.out.println("  blockName: '" + bh.blockName() + "'");
                System.out.println("  flags: 0x" + String.format("%04X", bh.flags()));
                System.out.println("  basePoint: " + bh.basePoint());
                System.out.println("  xrefPath: '" + bh.xrefPath() + "'");
                
                // 分析 blockName 字节
                String name = bh.blockName();
                if (name != null && !name.isEmpty()) {
                    byte[] nameBytes = name.getBytes("UTF-16LE");
                    System.out.print("  blockName bytes (UTF-16LE): ");
                    for (int i = 0; i < Math.min(32, nameBytes.length); i++) {
                        System.out.printf("%02X ", nameBytes[i]);
                    }
                    System.out.println();
                    
                    // 尝试不同编码
                    try {
                        byte[] raw = name.getBytes("US-ASCII");
                        System.out.print("  blockName as ASCII bytes: ");
                        for (int i = 0; i < Math.min(32, raw.length); i++) {
                            System.out.printf("%02X ", raw[i]);
                        }
                        System.out.println();
                    } catch (Exception e) {}
                }
                
                // 分析坐标
                if (bh.basePoint() != null) {
                    double x = bh.basePoint().x();
                    double y = bh.basePoint().y();
                    double z = bh.basePoint().z();
                    System.out.printf("  X: %e (bits: 0x%016X)%n", x, Double.doubleToRawLongBits(x));
                    System.out.printf("  Y: %e (bits: 0x%016X)%n", y, Double.doubleToRawLongBits(y));
                    System.out.printf("  Z: %e (bits: 0x%016X)%n", z, Double.doubleToRawLongBits(z));
                }
            }
        }
        
        if (blockCount == 0) {
            System.out.println("\n警告: 没有找到 BLOCK_HEADER 对象!");
        }
    }
}

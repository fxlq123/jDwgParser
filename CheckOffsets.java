import java.nio.file.Files;
import java.nio.file.Paths;

public class CheckOffsets {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        
        // 尝试不同的偏移 - 检查 handles 部分解析结果
        int[] offsets = {0x52b9, 0x532f, 0x534e, 0x5370, 0x538f, 0x539d, 0x53ab, 0x53bc, 0x53cd, 0x53eb, 0x53f9, 0x6ccd, 0x6ce2, 0x11216, 0x5a60, 0x5a82};
        
        for (int off : offsets) {
            System.out.println("\n=== @0x" + Integer.toHexString(off));
            for (int i = 0; i < 32 && off + i < data.length; i++) {
                if (i % 16 == 0) System.out.println();
                System.out.printf("%02x ", data[off+i] & 0xFF);
            }
            System.out.println();
            
            // 读 MS: byte0 | (byte1 << 8)
            int lo = data[off] & 0xFF;
            int hi = data[off + 1] & 0xFF;
            int w = lo | (hi << 8);
            System.out.println("MS LE: w=0x" + Integer.toHexString(w) + " (" + w + "), bit15=" + ((w >> 15) & 1));
            
            // 如果 bit15=0: size=w & 0x7FFF
            int size = w & 0x7FFF;
            System.out.println("  size=" + size);
            
            // BS 类型
            int b2 = data[off + 2] & 0xFF;
            int opcode = (b2 >> 6) & 3;
            System.out.println("  BS byte=0x" + String.format("%02x", b2) + " opcode=" + opcode);
        }
    }
}

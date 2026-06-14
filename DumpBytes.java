import java.nio.file.Files;
import java.nio.file.Paths;

public class DumpBytes {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        
        dumpRange(data, 0x5320, 80, "Around 0x532d");
        dumpRange(data, 0x532d, 40, "@0x532d (after first object)");
        dumpRange(data, 0x5a60, 100, "Around *Model_Space @0x5a82");
        
        // 还可以检查:0x52b9 前的几个字节
        dumpRange(data, 0x52a0, 50, "Before 0x52b9");
    }
    
    static void dumpRange(byte[] data, int start, int len, String title) {
        System.out.println("=== " + title + " ===");
        for (int i = 0; i < len; i += 16) {
            int addr = start + i;
            System.out.printf("%04x: ", addr);
            for (int j = 0; j < 16; j++) {
                if (i + j < len && addr + j < data.length)
                    System.out.printf("%02x ", data[addr + j] & 0xFF);
                else
                    System.out.print("   ");
            }
            System.out.print(" |");
            for (int j = 0; j < 16; j++) {
                if (i + j < len && addr + j < data.length) {
                    int c = data[addr + j] & 0xFF;
                    System.out.print(c >= 32 && c < 127 ? (char)c : '.');
                }
            }
            System.out.println("|");
        }
        System.out.println();
    }
}

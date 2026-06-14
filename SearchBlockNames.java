import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class SearchBlockNames {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));

        // 搜索已知的块名称
        String[] knownNames = {
            "*Model_Space", "*Paper_Space", "*Paper_Space0",
            "SW_NOTE", "SW_NOTE_0", "TITLE", "标注"
        };

        System.out.println("=== 搜索已知块名称 ===\n");

        for (String name : knownNames) {
            byte[] searchBytes = name.getBytes();
            System.out.println("搜索: '" + name + "' (" + searchBytes.length + " bytes)");

            for (int i = 0; i < data.length - searchBytes.length; i++) {
                boolean found = true;
                for (int j = 0; j < searchBytes.length; j++) {
                    if (data[i + j] != searchBytes[j]) {
                        found = false;
                        break;
                    }
                }
                if (found) {
                    System.out.println("  找到 @ 0x" + Integer.toHexString(i) + ": ");
                    System.out.print("    上下文: ");
                    for (int j = Math.max(0, i - 10); j < Math.min(data.length, i + name.length() + 10); j++) {
                        int b = data[j] & 0xFF;
                        if (b >= 32 && b < 127) System.out.print((char)b);
                        else System.out.printf("[%02X]", b);
                    }
                    System.out.println();

                    // 显示前面的字节
                    System.out.print("    前 20 字节: ");
                    for (int j = 0; j < 20; j++) {
                        System.out.printf("%02X ", data[i - 10 + j] & 0xFF);
                    }
                    System.out.println();
                }
            }
        }

        // 也搜索以 * 开头的标识符
        System.out.println("\n=== 搜索所有以 * 开头的标识符 ===");
        for (int i = 0; i < data.length - 20; i++) {
            if (data[i] == '*' && data[i+1] >= 32 && data[i+1] < 127) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < 30 && i + j < data.length; j++) {
                    int b = data[i + j] & 0xFF;
                    if (b >= 32 && b < 127) sb.append((char)b);
                    else break;
                }
                if (sb.length() > 2) {
                    System.out.printf("  @ 0x%x: '%s'%n", i, sb.toString());
                    i += sb.length();  // 跳过这个名称
                }
            }
        }
    }
}

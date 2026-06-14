import java.nio.file.*;
import java.util.*;

public class DebugHandles {
    static long readMC(byte[] data, int[] posRef) {
        int idx = posRef[0];
        long result = 0;
        int count = 0;
        while (true) {
            int b = data[idx] & 0xFF;
            idx++;
            count++;
            result = (result << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) break;
            if (count > 5) break;
        }
        posRef[0] = idx;
        return result;
    }

    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        
        // Try each position from 0x4f00 to 0x5300
        System.out.println("Looking for handles section (first handle=1, first offset=0x52b9)");
        
        int bestStart = -1;
        int bestCount = 0;
        
        for (int headerSkip = 0; headerSkip <= 10; headerSkip++) {
            for (int start = 0x4f00; start < 0x5300; start++) {
                try {
                    int[] pos = {start + headerSkip};
                    long handle = 0;
                    long offset = 0;
                    int count = 0;
                    boolean firstOk = false;
                    
                    for (int i = 0; i < 2000; i++) {
                        if (pos[0] >= data.length - 3) break;
                        long hdelta = readMC(data, pos);
                        long odelta = readMC(data, pos);
                        if (hdelta < 0 || odelta < 0) break;
                        handle += hdelta;
                        offset += odelta;
                        if (handle > 100000 || offset >= data.length) break;
                        if (i == 0 && handle == 1 && offset == 0x52b9) {
                            firstOk = true;
                        }
                        count = i + 1;
                    }
                    
                    if (firstOk && count > bestCount) {
                        bestCount = count;
                        bestStart = start;
                        System.out.println(String.format("  Candidate: start=0x%x (+%d header) -> %d entries",
                            start, headerSkip, count));
                        // Print first 5 entries
                        int[] pos2 = {start + headerSkip};
                        long h = 0, o = 0;
                        for (int i = 0; i < 5; i++) {
                            long hd = readMC(data, pos2);
                            long od = readMC(data, pos2);
                            h += hd; o += od;
                            System.out.println(String.format("    [%d] handle=0x%x offset=0x%x (delta: h=%d o=%d)",
                                i, h, o, hd, od));
                        }
                        System.out.println();
                    }
                } catch (Exception e) {}
            }
        }
        
        if (bestStart < 0) {
            System.out.println("No good handle section found. Let's check raw bytes around 0x4f41:");
            for (int i = 0; i < 64; i++) {
                int b = data[0x4f41 + i] & 0xFF;
                if (i % 16 == 0) System.out.print(String.format("0x%04x: ", 0x4f41 + i));
                System.out.print(String.format("%02x ", b));
                if (i % 16 == 15) System.out.println();
            }
        }
    }
}

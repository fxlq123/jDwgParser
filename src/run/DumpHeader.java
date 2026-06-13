package run;

import io.dwg.core.io.BitInput;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.util.ByteUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;

public class DumpHeader {
    public static void main(String[] args) throws Exception {
        String[] files = {
            "samples/2018/Dynblocks.dwg",
            "samples/2007/circle.dwg",
        };

        for (String path : files) {
            File f = new File(path);
            if (!f.exists()) { System.out.println("SKIP: " + path); continue; }

            byte[] data = Files.readAllBytes(f.toPath());
            String sig = new String(data, 0, 6);
            System.out.println("\n=== " + path + " [" + sig + "] ===");

            // Dump bytes 0x80-0x180 (first part of header region)
            System.out.println("Bytes 0x80-0x180 (header data):");
            for (int row = 0; row < 8; row++) {
                int offset = 0x80 + row * 32;
                System.out.printf("  0x%03X: ", offset);
                for (int col = 0; col < 32; col++) {
                    System.out.printf("%02X ", data[offset + col] & 0xFF);
                }
                System.out.println();
            }

            // Try reading header fields without RS decoding
            System.out.println("\nDirect LE32 reads (without RS):");
            for (int i = 0; i < 64; i += 4) {
                int val = (int) ByteUtils.readLE32(data, 0x80 + i);
                long val64 = ByteUtils.readLE64(data, 0x80 + i);
                if (val != 0) {
                    System.out.printf("  0x%03X: LE32=0x%08X (%d)  LE64=0x%016X%n",
                        0x80 + i, val, val, val64);
                }
            }
        }
    }
}

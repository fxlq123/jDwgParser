package run;

import io.dwg.core.util.ByteUtils;
import io.dwg.core.util.Lz77Decompressor;

import java.io.File;
import java.nio.file.Files;

public class DeepHeader {
    public static void main(String[] args) throws Exception {
        String path = "samples/2018/Dynblocks.dwg";
        File f = new File(path);
        if (!f.exists()) { System.out.println("File not found"); return; }

        byte[] data = Files.readAllBytes(f.toPath());
        String sig = new String(data, 0, 6);
        System.out.println("[" + sig + "] " + data.length + " bytes");

        // Extract header region: 0x80 to 0x80+0x3d8 (0x80 to 0x458)
        byte[] region = new byte[0x3d8];
        System.arraycopy(data, 0x80, region, 0, 0x3d8);

        // Try various deinterleavings and interpretations
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] processed;
            String label;

            switch (attempt) {
                case 0: processed = region; label = "RAW 0x80"; break;
                case 1: processed = deinterleave(region, 3, 239); label = "R2007 deinterleave (3×239)"; break;
                case 2: processed = deinterleave(region, 4, 192); label = "4×192 deinterleave"; break;
                case 3: processed = deinterleave(region, 2, 384); label = "2×384 deinterleave"; break;
                case 4:
                    // Try: skip first 0x80 bytes, read raw from 0x100
                    processed = new byte[region.length - 0x80];
                    System.arraycopy(region, 0x80, processed, 0, processed.length);
                    label = "RAW 0x100";
                    break;
                case 5:
                    // Try: deinterleave then skip first 32 bytes
                    byte[] d5 = deinterleave(region, 3, 239);
                    if (d5 != null && d5.length > 32) {
                        processed = new byte[d5.length - 32];
                        System.arraycopy(d5, 32, processed, 0, processed.length);
                    } else { processed = null; }
                    label = "deinterleave skip 32";
                    break;
                case 6:
                    // Try: reverse byte order within 8-byte groups
                    processed = new byte[region.length];
                    for (int i = 0; i < region.length; i++) {
                        int group = (i / 8) * 8;
                        int pos = i % 8;
                        processed[i] = region[group + (7 - pos)];
                    }
                    label = "reversed 8-byte groups";
                    break;
                default:
                    // Try deinterleave with RS-style but different skip for parity
                    processed = deinterleave(region, 3, 255); // include parity
                    label = "full deinterleave (3×255)";
            }

            if (processed == null) continue;
            System.out.println("\n--- " + label + " (" + processed.length + " bytes) ---");

            // Show first 96 bytes
            for (int i = 0; i < Math.min(96, processed.length); i += 16) {
                System.out.printf("  [%04X] ", i);
                for (int j = 0; j < 16 && i + j < processed.length; j++) {
                    System.out.printf("%02X ", processed[i + j] & 0xFF);
                }
                System.out.println();
            }

            // Try to find valid comprLen at various offsets
            for (int cOff = 0; cOff < Math.min(64, processed.length - 4); cOff += 4) {
                int cl = (int) ByteUtils.readLE32(processed, cOff);
                if (cl > 0 && cl < 500) {
                    System.out.printf("  Possible comprLen=%d at offset %d (0x%X)%n", cl, cOff, cOff);
                    // Try LZ77 from 8 bytes after
                    int dataOff = cOff + 8;
                    if (dataOff + cl <= processed.length) {
                        byte[] comp = new byte[cl];
                        System.arraycopy(processed, dataOff, comp, 0, cl);
                        try {
                            byte[] decomp = new Lz77Decompressor().decompress(comp, 1000);
                            if (decomp != null && decomp.length > 0) {
                                System.out.printf("  LZ77 from offset %d: %d bytes decompressed%n",
                                    dataOff, decomp.length);
                                System.out.printf("  First 48 bytes: ");
                                for (int i = 0; i < Math.min(48, decomp.length); i++) {
                                    System.out.printf("%02X ", decomp[i] & 0xFF);
                                    if ((i + 1) % 16 == 0) System.out.print("\n                 ");
                                }
                                System.out.println();

                                // Try reading header fields
                                for (int off : new int[]{56, 80, 88, 176, 192, 200, 216}) {
                                    if (off + 8 <= decomp.length) {
                                        long v = ByteUtils.readLE64(decomp, off);
                                        System.out.printf("    [%03X]: 0x%016X (%d)%n", off, v, v);
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // Try: find strings like "ACDB" or known section names in the header region
        System.out.println("\n--- Scanning for ASCII/UTF16 strings ---");
        byte[] full = new byte[0x400];
        System.arraycopy(data, 0x80, full, 0, 0x400);
        for (int i = 0; i < full.length - 4; i++) {
            if (full[i] >= 0x20 && full[i] < 0x7F && full[i+1] == 0 &&
                full[i+2] >= 0x20 && full[i+2] < 0x7F && full[i+3] == 0) {
                // Found UTF-16LE start
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < full.length && full[j] >= 0x20 && full[j] < 0x7F && full[j+1] == 0; j += 2) {
                    sb.append((char)full[j]);
                }
                if (sb.length() >= 4) {
                    System.out.printf("  [0x%X] UTF-16LE: '%s'%n", 0x80 + i, sb.substring(0, Math.min(sb.length(), 40)));
                    i += sb.length() * 2;
                }
            }
        }
    }

    private static byte[] deinterleave(byte[] data, int blocks, int dataSize) {
        if (data == null || data.length < blocks * dataSize) return null;
        try {
            byte[] result = new byte[blocks * dataSize];
            for (int i = 0; i < blocks; i++) {
                for (int j = 0; j < dataSize; j++) {
                    result[i * dataSize + j] = data[i + j * blocks];
                }
            }
            return result;
        } catch (Exception e) { return null; }
    }
}

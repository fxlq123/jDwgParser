import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DebugLE16 {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        // 测试: objSize = LE uint16, 然后 BS typeCode
        // 扫描 0x5000-0x6000
        System.out.println("=== objSize = LE uint16, 扫描 0x5000-0x6000 ===");
        scanLE16(data, version, 0x5000, 0x1000);

        System.out.println("\n=== objSize = LE uint16, 扫描 0x6000-0x8000 ===");
        scanLE16(data, version, 0x6000, 0x2000);

        System.out.println("\n=== objSize = LE uint16, 扫描 0x8000-0xB000 ===");
        scanLE16(data, version, 0x8000, 0x3000);

        System.out.println("\n=== objSize = LE uint16, 扫描 0xB000-0x11943 ===");
        scanLE16(data, version, 0xB000, 0x11943 - 0xB000);
    }

    static void scanLE16(byte[] data, DwgVersion version, int start, int length) {
        int pos = start;
        int end = Math.min(start + length, data.length);
        Map<Integer, Integer> typeCounts = new LinkedHashMap<>();
        int found = 0, totalSize = 0;
        List<Long> bHeaderHandles = new ArrayList<>();
        List<Long> bEndHandles = new ArrayList<>();
        List<Long> insertHandles = new ArrayList<>();
        List<String> bHeaderNames = new ArrayList<>();

        while (pos < end - 4) {
            // LE uint16 objSize
            int objSize = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
            if (objSize <= 3 || objSize > 2000) {
                pos++;
                continue;
            }

            // BS typeCode
            int b1 = data[pos+2] & 0xFF;
            int opcode = (b1 >> 6) & 0x3;
            int typeCode;
            if (opcode == 1) {
                int b2 = data[pos+3] & 0xFF;
                typeCode = ((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3);
            } else if (opcode == 0) {
                int b2 = data[pos+3] & 0xFF;
                int b3 = data[pos+4] & 0xFF;
                typeCode = (((b1 & 0x3F) << 2) | ((b2 >> 6) & 0x3))
                         | ((((b2 & 0x3F) << 2) | ((b3 >> 6) & 0x3)) << 8);
            } else {
                pos++;
                continue;
            }

            typeCounts.put(typeCode, typeCounts.getOrDefault(typeCode, 0) + 1);
            found++;
            totalSize += objSize;

            // 解析 BLOCK_HEADER
            if (typeCode == 48) {
                try {
                    ByteBuffer bb = ByteBuffer.wrap(data);
                    ByteBufferBitInput input = new ByteBufferBitInput(bb);
                    input.seek(pos * 8L);
                    BitStreamReader r = new BitStreamReader(input, version);

                    // objSize (LE uint16)
                    int _size = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
                    r.seek(pos * 8L + 16);  // skip 2 bytes

                    int _tc = r.readBitShort();
                    if (_tc != 48) { pos += objSize; continue; }

                    // common
                    int bitsize = r.readBitLong();
                    long h = r.readHandle();
                    bHeaderHandles.add(h);

                    try {
                        int eedSize = (data[(int)(r.position()/8)] & 0xFF) | ((data[(int)(r.position()/8)+1] & 0xFF) << 8);
                        if (eedSize > 0 && eedSize < 100) r.seek(r.position() + 16 + eedSize * 8L);
                        else {
                            // try modular short
                            int ms = r.readModularShort();
                            if (ms > 0 && ms < 1000) r.seek(r.position() + ms * 8L);
                        }
                    } catch (Exception ex) {}

                    try { r.readHandle(); } catch (Exception ex) {}
                    try {
                        int n = r.readBitLong();
                        for (int i = 0; i < n; i++) { try { r.readHandle(); } catch (Exception ex) { break; } }
                    } catch (Exception ex) {}
                    try { r.readHandle(); } catch (Exception ex) {}

                    // 1-byte text
                    String name = readByteText(r);
                    bHeaderNames.add(name);
                } catch (Exception ex) {}
            }

            if (typeCode == 49) {
                try {
                    ByteBuffer bb = ByteBuffer.wrap(data);
                    ByteBufferBitInput input = new ByteBufferBitInput(bb);
                    input.seek(pos * 8L + 16);  // skip 2 bytes for LE uint16
                    BitStreamReader r = new BitStreamReader(input, version);

                    int _tc = r.readBitShort();
                    int bitsize = r.readBitLong();
                    long h = r.readHandle();
                    bEndHandles.add(h);
                } catch (Exception ex) {}
            }

            if (typeCode == 7) {
                try {
                    ByteBuffer bb = ByteBuffer.wrap(data);
                    ByteBufferBitInput input = new ByteBufferBitInput(bb);
                    input.seek(pos * 8L + 16);
                    BitStreamReader r = new BitStreamReader(input, version);

                    int _tc = r.readBitShort();
                    int bitsize = r.readBitLong();
                    long h = r.readHandle();
                    insertHandles.add(h);
                } catch (Exception ex) {}
            }

            pos += objSize;
        }

        System.out.println("找到 " + found + " 个对象, 总字节 " + totalSize);
        System.out.println("类型分布:");
        for (Map.Entry<Integer, Integer> e : typeCounts.entrySet()) {
            System.out.printf("  type=%3d (0x%02X): %4d  %s%n",
                e.getKey(), e.getKey(), e.getValue(), typeName(e.getKey()));
        }
        if (!bHeaderHandles.isEmpty()) {
            System.out.println("BLOCK_HEADER handles: " + bHeaderHandles);
            System.out.println("BLOCK_HEADER names: " + bHeaderNames);
        }
        if (!bEndHandles.isEmpty()) System.out.println("BLOCK_END handles: " + bEndHandles);
        if (!insertHandles.isEmpty()) System.out.println("INSERT handles: " + insertHandles);
    }

    static String readByteText(BitStreamReader r) {
        try {
            long pos = r.position();
            long byteAligned = ((pos + 7) / 8) * 8;
            r.seek(byteAligned);
            int len = 0;
            for (int i = 0; i < 8; i++) len = (len << 1) | (r.getInput().readBit() ? 1 : 0);
            if (len <= 0 || len > 100) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int ch = 0;
                for (int j = 0; j < 8; j++) ch = (ch << 1) | (r.getInput().readBit() ? 1 : 0);
                sb.append((char)ch);
            }
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    static String typeName(int code) {
        String[] names = {
            "UNUSED", "TEXT", "ATTDEF", "ATTRIB", "SEQEND", "ENDBLK", "", "INSERT", "MINSERT",
            "", "V2D", "V3D", "VMESH", "VPF", "VPFF",
            "PL2D", "PL3D", "ARC", "CIRCLE", "LINE",
            "DIMO", "DIML", "DIMA", "DIM3",
            "DIM2", "DIMR", "DIMD", "POINT", "FACE",
            "PLPF", "PLPM", "SOLID", "TRACE", "SHAPE", "VPORT",
            "EL", "SPL", "REG", "S3D", "BODY", "RAY", "XLINE", "DICT",
            "", "MTEXT", "LEAD", "TOL", "MLINE", "BLKH", "BLKE",
            "LTYPE", "LAYER", "STYLE"
        };
        if (code >= 0 && code < names.length && names[code] != null && !names[code].isEmpty()) return names[code];
        switch(code) {
            case 0x3C: return "GROUP";
            case 0x4B: return "LWPLINE";
            case 0x4C: return "HATCH";
            case 0x4D: return "XRECORD";
            case 0x50: return "LAYOUT";
            default: return "UNKNOWN";
        }
    }
}

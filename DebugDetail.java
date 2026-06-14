import io.dwg.core.io.BitStreamReader;
import io.dwg.core.io.ByteBufferBitInput;
import io.dwg.core.version.DwgVersion;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DebugDetail {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
            "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg"));
        DwgVersion version = DwgVersion.R2000;

        // 查找 0x5000-0x6000 中所有 type=48 的对象，逐字节分析
        System.out.println("=== 查找 0x5000-0x6000 中所有 BLOCK_HEADER (type=48) ===");
        findAndDump(data, version, 0x5000, 0x1000, 48, "BLOCK_HEADER");

        System.out.println("\n=== 查找 0x5000-0x6000 中所有 BLOCK_END (type=49) ===");
        findAndDump(data, version, 0x5000, 0x1000, 49, "BLOCK_END");

        System.out.println("\n=== 查找 0x5000-0x6000 中所有 INSERT (type=7) ===");
        findAndDump(data, version, 0x5000, 0x1000, 7, "INSERT");

        // 查找后面区域的
        System.out.println("\n=== 查找 0xB000-0x11943 中所有 INSERT (type=7) ===");
        findAndDump(data, version, 0xB000, 0x11943 - 0xB000, 7, "INSERT");

        // 查找 0x6000-0xB000 中可能的 BLOCK_HEADER
        System.out.println("\n=== 查找 0x6000-0xB000 中所有 BLOCK_HEADER (type=48) ===");
        findAndDump(data, version, 0x6000, 0x5000, 48, "BLOCK_HEADER");

        // 查找 0x6000-0xB000 中的 BLOCK_END
        System.out.println("\n=== 查找 0x6000-0xB000 中所有 BLOCK_END (type=49) ===");
        findAndDump(data, version, 0x6000, 0x5000, 49, "BLOCK_END");
    }

    static void findAndDump(byte[] data, DwgVersion version, int start, int length, int targetType, String name) {
        int pos = start;
        int end = Math.min(start + length, data.length);
        int found = 0;

        while (pos < end - 4) {
            // LE uint16 objSize
            int objSize = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8);
            if (objSize <= 3 || objSize > 2000 || pos + objSize > end) {
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

            if (typeCode == targetType) {
                found++;
                System.out.println("\n*** " + name + " @ 0x" + Integer.toHexString(pos) + " objSize=" + objSize + " ***");
                // Dump 字节
                StringBuilder sb = new StringBuilder("  bytes: ");
                for (int i = 0; i < Math.min(objSize, 64); i++) {
                    sb.append(String.format("%02X ", data[pos+i] & 0xFF));
                }
                System.out.println(sb.toString());

                // 尝试解析
                try {
                    ByteBuffer bb = ByteBuffer.wrap(data);
                    ByteBufferBitInput input = new ByteBufferBitInput(bb);
                    input.seek(pos * 8L + 16); // skip 2 bytes objSize
                    BitStreamReader r = new BitStreamReader(input, version);

                    int _tc = r.readBitShort();
                    System.out.println("  typeCode: " + _tc);

                    // 接下来可能是 bitsize (BL)
                    try {
                        int bitsize = r.readBitLong();
                        System.out.println("  bitsize (BL): " + bitsize);
                    } catch (Exception e) { System.out.println("  bitsize err: " + e); }

                    // handle
                    try {
                        long h = r.readHandle();
                        System.out.println("  entity handle: " + h + " (0x" + Long.toHexString(h) + ")");
                    } catch (Exception e) { System.out.println("  handle err: " + e); }

                    // EED size (MS)
                    try {
                        int eedSize = r.readModularShort();
                        System.out.println("  eed size (MS): " + eedSize);
                        if (eedSize > 0 && eedSize < 200) r.seek(r.position() + eedSize * 8L);
                    } catch (Exception e) { System.out.println("  eed err: " + e); }

                    // owner handle
                    try { long oh = r.readHandle(); System.out.println("  owner: 0x" + Long.toHexString(oh)); }
                    catch (Exception e) { System.out.println("  owner err: " + e); }

                    // num reactors
                    try { int nr = r.readBitLong(); System.out.println("  reactors: " + nr);
                        for (int i = 0; i < nr; i++) { try { long rh = r.readHandle(); System.out.println("    r" + i + ": 0x" + Long.toHexString(rh)); } catch (Exception ex) {break;} }
                    } catch (Exception e) { System.out.println("  reactors err: " + e); }

                    // xdict handle
                    try { long xh = r.readHandle(); System.out.println("  xdict: 0x" + Long.toHexString(xh)); }
                    catch (Exception e) { System.out.println("  xdict err: " + e); }

                    // BLOCK_HEADER 特有字段
                    if (targetType == 48) {
                        // block name: 1-byte length + ASCII
                        try {
                            String bname = readByteText(r);
                            System.out.println("  block name: '" + bname + "'");
                        } catch (Exception e) { System.out.println("  name err: " + e); }

                        try { int flags = r.readBitShort(); System.out.println("  flags: " + flags); } catch (Exception e) { System.out.println("  flags err: " + e); }
                        try { double[] pt = r.read3BitDouble(); System.out.println("  basePoint: (" + pt[0] + ", " + pt[1] + ", " + pt[2] + ")"); } catch (Exception e) { System.out.println("  basePoint err: " + e); }
                        try { String xref = readByteText(r); System.out.println("  xref path: '" + xref + "'"); } catch (Exception e) { System.out.println("  xref err: " + e); }
                    }

                    // INSERT 特有字段
                    if (targetType == 7) {
                        try { long bh = r.readHandle(); System.out.println("  block_header: 0x" + Long.toHexString(bh)); } catch (Exception e) { System.out.println("  bh err: " + e); }
                        try { double[] pt = r.read3BitDouble(); System.out.println("  insPoint: (" + pt[0] + ", " + pt[1] + ", " + pt[2] + ")"); } catch (Exception e) { System.out.println("  insPoint err: " + e); }
                        try { double[] sc = r.read3BitDouble(); System.out.println("  scale: (" + sc[0] + ", " + sc[1] + ", " + sc[2] + ")"); } catch (Exception e) { System.out.println("  scale err: " + e); }
                        try { double rot = r.readBitDouble(); System.out.println("  rotation: " + rot); } catch (Exception e) { System.out.println("  rotation err: " + e); }
                    }

                    // BLOCK_END 特有字段
                    if (targetType == 49) {
                        try { int flags = r.readBitShort(); System.out.println("  flags: " + flags); } catch (Exception e) { System.out.println("  flags err: " + e); }
                    }
                } catch (Exception ex) {
                    System.out.println("  parse exception: " + ex);
                }
            }

            pos += objSize;
        }
        System.out.println("共找到 " + found + " 个 " + name);
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
}

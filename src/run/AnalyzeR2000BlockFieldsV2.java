package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;
import java.nio.file.Paths;
import java.util.*;
import java.io.PrintStream;
import java.io.FileOutputStream;

/**
 * 分析 R2000 BLOCK 定义对象的位级解析，输出到文件
 */
public class AnalyzeR2000BlockFieldsV2 {

    static byte[] data;
    static PrintStream out;

    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] fileData = java.nio.file.Files.readAllBytes(Paths.get(filename));
        data = fileData;

        out = new PrintStream(new FileOutputStream("block_analysis.txt"), true);

        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);
        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        out.println("=== R2000 BLOCK 结构分析 ===\n");

        // 收集 type 0x30 (BLOCK_HEADER) 和 0x31 (BLOCK) 的 handles
        List<Long> blockHeaderHandles = new ArrayList<>();
        List<Long> blockHandles = new ArrayList<>();
        List<Long> insertHandles = new ArrayList<>();

        for (long h : registry.allHandles()) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0) continue;
            ByteBufferBitInput bb = new ByteBufferBitInput(data);
            bb.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bb, DwgVersion.R2000);
            try {
                r.readModularShort();
                int tc = r.readBitShort();
                if (tc == 0x30) blockHeaderHandles.add(h);
                else if (tc == 0x31) blockHandles.add(h);
                else if (tc == 0x07) insertHandles.add(h);
            } catch (Exception ignored) {}
        }

        out.println("BLOCK_HEADER (0x30) count: " + blockHeaderHandles.size());
        out.println("BLOCK (0x31) count: " + blockHandles.size());
        out.println("INSERT (0x07) count: " + insertHandles.size());
        out.println();

        // 详细分析 BLOCK_HEADER
        out.println("========== BLOCK_HEADER (0x30) 详细分析 ==========");
        for (long h : blockHeaderHandles) {
            long offset = registry.offsetFor(h).orElse(-1L);
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();

            out.println("【handle=0x" + Long.toHexString(h) + " @offset=" + offset + " obj_size=" + objSize + "】");

            // 字节级 dump
            out.print("  bytes 0-32: ");
            for (int i = 4; i < Math.min(36, objSize); i++) {
                out.printf("%02X ", data[(int)offset + i] & 0xFF);
            }
            out.println();
            out.print("  ascii:       ");
            for (int i = 4; i < Math.min(36, objSize); i++) {
                int b = data[(int)offset + i] & 0xFF;
                out.print((b >= 32 && b < 127) ? ((char)b) : '.');
            }
            out.println();

            // 解析 object common (H:owner + BS:num_reactors + H[]:reactors + H:xdict)
            try {
                long owner = r.readHandle();
                out.println("  owner_handle: 0x" + Long.toHexString(owner));

                int numReactors = r.readBitShort();
                out.println("  num_reactors(BS): " + numReactors);

                if (numReactors > 0 && numReactors < 50) {
                    for (int i = 0; i < numReactors; i++) {
                        long rh = r.readHandle();
                        if (i < 3) out.println("   reactor[" + i + "]: 0x" + Long.toHexString(rh));
                    }
                }

                long xdict = r.readHandle();
                out.println("  xdict_handle: 0x" + Long.toHexString(xdict));

                long afterCommon = bbuf.position();
                out.println("  @bit after common: " + afterCommon + " (byte " + (afterCommon/8) + ")");

                // 接下来尝试不同的字段读取模式
                // 模式 A: block_name(TV) + flags(BS) + base_point(3RD) + xref_path(TV)
                // 模式 B: 表条目 (entries[])
                // 模式 C: BLOCK_HEADER 表对象: entry_handles[]
                // 模式 D: 先读取 H:block_end + block_name + entries

                // 打印当前位置附近的字节，帮助理解
                int curByte = (int)(afterCommon / 8);
                int remainBits = (int)(afterCommon % 8);
                out.print("  @byte " + curByte + " remaining " + remainBits + " bits: ");
                for (int i = 0; i < 24; i++) {
                    if (curByte + i < data.length) out.printf("%02X ", data[curByte + i] & 0xFF);
                }
                out.println();

                // 尝试 TV 读取 block_name (R2000 = T: BS length + ASCII bytes)
                long pos = bbuf.position();
                try {
                    String name = r.readText();
                    out.println("  [T] block_name: '" + name + "'");
                } catch (Exception e) {
                    out.println("  [T] failed: " + e.getMessage());
                }
                bbuf.seek(pos);

                // 尝试 TU (R2000 实际可能是 BS length + UTF-16LE 文本)
                try {
                    int nameLen = r.readBitShort();
                    out.print("  [TU-尝试] nameLen=" + nameLen);
                    if (nameLen > 0 && nameLen < 200) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < nameLen; i++) {
                            int lo = r.getInput().readBits(8) & 0xFF;
                            int hi = r.getInput().readBits(8) & 0xFF;
                            int cp = lo | (hi << 8);
                            sb.append(cp < 128 && cp >= 32 ? (char)cp : '?');
                        }
                        out.println(", text: '" + sb.toString() + "'");
                    } else out.println();
                } catch (Exception e) {
                    out.println("  [TU] failed: " + e.getMessage());
                }
                bbuf.seek(pos);

                // 尝试直接读取 handle 数组
                try {
                    for (int i = 0; i < 10; i++) {
                        long h2 = r.readHandle();
                        if (i < 5) out.println("  handle[" + i + "]: 0x" + Long.toHexString(h2));
                    }
                } catch (Exception e) {
                    out.println("  handle array failed: " + e.getMessage());
                }

            } catch (Exception e) {
                out.println("  parse error: " + e.getMessage());
            }
            out.println();
        }

        // 详细分析 BLOCK (0x31)
        out.println("========== BLOCK (0x31) 详细分析 ==========");
        int count = 0;
        for (long h : blockHandles) {
            if (count++ > 15) break;
            long offset = registry.offsetFor(h).orElse(-1L);
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();

            out.println("【handle=0x" + Long.toHexString(h) + " @offset=" + offset + " obj_size=" + objSize + "】");
            out.print("  bytes4-: ");
            for (int i = 4; i < Math.min(36, objSize); i++) {
                out.printf("%02X ", data[(int)offset + i] & 0xFF);
            }
            out.println();
            out.print("  ascii:    ");
            for (int i = 4; i < Math.min(36, objSize); i++) {
                int b = data[(int)offset + i] & 0xFF;
                out.print((b >= 32 && b < 127) ? ((char)b) : '.');
            }
            out.println();

            try {
                long owner = r.readHandle();
                out.println("  owner_handle: 0x" + Long.toHexString(owner));
                int numReactors = r.readBitShort();
                out.println("  num_reactors: " + numReactors);
                if (numReactors > 0 && numReactors < 20) {
                    for (int i = 0; i < numReactors; i++) r.readHandle();
                }
                long xdict = r.readHandle();
                out.println("  xdict_handle: 0x" + Long.toHexString(xdict));

                long pos = bbuf.position();
                out.println("  after common @bit=" + pos);

                // 现在 block 特定字段
                // 尝试：block_name(BS length + ASCII)
                try {
                    int nameLen = r.readBitShort();
                    out.print("  nameLen(BS): " + nameLen);
                    if (nameLen > 0 && nameLen < 200) {
                        byte[] nameBytes = new byte[nameLen];
                        for (int i = 0; i < nameLen; i++) {
                            nameBytes[i] = (byte)(r.getInput().readBits(8) & 0xFF);
                        }
                        out.println(", ASCII name: '" + new String(nameBytes, java.nio.charset.StandardCharsets.US_ASCII) + "'");
                    } else out.println();
                } catch (Exception e) {
                    out.println("  BS+ASCII failed: " + e.getMessage());
                }
                bbuf.seek(pos);

                // 尝试: block_name TV (BS length + UTF-16LE)
                try {
                    int nameLen = r.readBitShort();
                    out.print("  nameLen(TV): " + nameLen);
                    if (nameLen > 0 && nameLen < 200) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < nameLen; i++) {
                            int lo = r.getInput().readBits(8) & 0xFF;
                            int hi = r.getInput().readBits(8) & 0xFF;
                            int cp = lo | (hi << 8);
                            sb.append(cp < 128 && cp >= 32 ? (char)cp : '?');
                        }
                        out.println(", UTF-16: '" + sb.toString() + "'");
                    } else out.println();
                } catch (Exception e) {
                    out.println("  TV failed: " + e.getMessage());
                }
                bbuf.seek(pos);

                // 尝试作为 block record（block_name + handle refs）
                // 可能结构: block_name(T) + block_end_handle(H) + first_entity_handle(H)
                try {
                    String n = r.readText();
                    out.println("  readText name: '" + n + "'");
                    long be = r.readHandle();
                    out.println("  block_end_handle: 0x" + Long.toHexString(be));
                } catch (Exception e) {
                    out.println("  name+block_end failed: " + e.getMessage());
                }
                bbuf.seek(pos);

                out.println();
            } catch (Exception e) {
                out.println("  error: " + e.getMessage());
                out.println();
            }
        }

        // INSERT 实体分析
        out.println("========== INSERT (0x07) 详细分析 ==========");
        count = 0;
        for (long h : insertHandles) {
            if (count++ > 5) break;
            long offset = registry.offsetFor(h).orElse(-1L);
            ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
            bbuf.seek(offset * 8L);
            BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
            int objSize = r.readModularShort();
            int typeCode = r.readBitShort();

            out.println("【INSERT handle=0x" + Long.toHexString(h) + " @offset=" + offset + " obj_size=" + objSize + "】");

            try {
                // entity header
                int bitsize = readRawLong(r);
                out.println("  bitsize(RL): " + bitsize);
                long entHandle = r.readHandle();
                out.println("  entity_handle: 0x" + Long.toHexString(entHandle));

                // EED loop
                int eedCount = 0;
                int eedSize = r.readBitShort();
                while (eedSize != 0 && eedSize > 0 && eedSize < 0x7FFF && eedCount < 20) {
                    r.readHandle();
                    for (int i = 0; i < eedSize; i++) r.getInput().readBits(8);
                    eedSize = r.readBitShort();
                    eedCount++;
                }
                out.println("  EED loop entries: " + eedCount);

                long pos = bbuf.position();
                out.println("  after header @bit=" + pos);

                // common entity data
                // 尝试不同顺序

                // Method A: preview_exists + entmode + ...
                bbuf.seek(pos);
                try {
                    boolean pe = r.getInput().readBit();
                    out.println("  methodA preview_exists=" + pe);
                    if (pe) {
                        long ps = readRawLong(r);
                        bbuf.seek(bbuf.position() + ps * 8L);
                        out.println("  preview skipped size=" + ps);
                    }
                    int em = r.getInput().readBits(2);
                    int numr = r.readBitLong();
                    out.println("  methodA entmode=" + em + " num_reactors=" + numr);
                    if (numr > 0 && numr < 20) for (int i = 0; i < numr; i++) r.readHandle();
                    long xdict = r.readHandle();
                    long layer = r.readHandle();
                    long ltype = r.readHandle();
                    out.println("  methodA xdict=0x" + Long.toHexString(xdict) +
                        " layer=0x" + Long.toHexString(layer) +
                        " ltype=0x" + Long.toHexString(ltype));
                    int color = r.readBitShort();
                    out.println("  methodA color=" + color);

                    // block header handle (关键)
                    long bh = r.readHandle();
                    out.println("  methodA BLOCK_HEADER_HANDLE: 0x" + Long.toHexString(bh) + " <<<");

                    double sx = r.readBitDouble();
                    double sy = r.readBitDouble();
                    double sz = r.readBitDouble();
                    out.printf("  methodA scale: (%.4f, %.4f, %.4f)\n", sx, sy, sz);
                    double rot = r.readBitDouble();
                    out.printf("  methodA rotation: %.6f\n", rot);
                    double ix = r.readBitDouble();
                    double iy = r.readBitDouble();
                    double iz = r.readBitDouble();
                    out.printf("  methodA insertion: (%.4f, %.4f, %.4f)\n", ix, iy, iz);
                } catch (Exception e) {
                    out.println("  methodA failed: " + e.getMessage());
                }
            } catch (Exception e) {
                out.println("  outer error: " + e.getMessage());
            }
            out.println();
        }

        out.println("分析完成");
        out.close();
    }

    private static int readRawLong(BitStreamReader r) {
        BitInput input = r.getInput();
        long b0 = input.readBits(8) & 0xFF;
        long b1 = input.readBits(8) & 0xFF;
        long b2 = input.readBits(8) & 0xFF;
        long b3 = input.readBits(8) & 0xFF;
        return (int)(b0 | (b1 << 8) | (b2 << 16) | (b3 << 24));
    }
}

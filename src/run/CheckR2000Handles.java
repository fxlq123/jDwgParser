package run;

import io.dwg.core.io.*;
import io.dwg.core.version.DwgVersion;
import io.dwg.format.common.*;
import io.dwg.sections.handles.*;

import java.nio.file.Paths;
import java.util.*;

/**
 * 验证 R2000 Handles Section 解析是否正确
 */
public class CheckR2000Handles {
    public static void main(String[] args) throws Exception {
        String filename = "210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg";
        byte[] data = java.nio.file.Files.readAllBytes(Paths.get(filename));

        DwgFileStructureHandler handler = DwgFileStructureHandlerFactory.forVersion(DwgVersion.R2000);
        ByteBufferBitInput input = new ByteBufferBitInput(data);
        FileHeaderFields header = handler.readHeader(input);

        input = new ByteBufferBitInput(data);
        Map<String, SectionInputStream> sections = handler.readSections(input, header);

        SectionInputStream handlesSec = sections.get("AcDb:Handles");
        byte[] hbytes = handlesSec.rawBytes();

        System.out.println("=== Handles Section 信息 ===");
        System.out.println("  大小: " + hbytes.length + " 字节");

        // 前 32 字节 hex dump
        System.out.println("  前 64 字节:");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(64, hbytes.length); i++) {
            sb.append(String.format("%02X ", hbytes[i] & 0xFF));
            if ((i + 1) % 16 == 0) sb.append("\n  ");
        }
        System.out.println(sb.toString());

        // 用原始方式解析: 参考 libredwg spec
        // R2000 handles section has pages
        // Each page: handle_delta(MS), offset_delta(MS), page_size(MS) or terminator(0x00)
        // Actually R2000 handles section is simpler than R2007
        // Let's look at the existing parser

        HandlesSectionParser hsp = new HandlesSectionParser();
        HandleRegistry registry = hsp.parse(handlesSec, DwgVersion.R2000);

        System.out.println("  解析到的对象数 (HandleRegistry): " + registry.allHandles().size());

        // 显示前 20 个 handle-offset 对
        System.out.println("\n  前 20 个 handles:");
        int n = 0;
        List<Long> sortedHandles = new ArrayList<>(registry.allHandles());
        Collections.sort(sortedHandles);
        for (long h : sortedHandles) {
            if (n >= 20) break;
            java.util.Optional<Long> off = registry.offsetFor(h);
            System.out.printf("    handle=0x%X  offset=%s%n", h, off.map(Object::toString).orElse("N/A"));
            n++;
        }

        // 按 type code 分组，看看有哪些类型
        System.out.println("\n=== 对象类型分布 ===");
        Map<Integer, Integer> typeCount = new HashMap<>();
        Map<Integer, List<Long>> typeHandles = new HashMap<>();
        for (long h : sortedHandles) {
            long offset = registry.offsetFor(h).orElse(-1L);
            if (offset < 0 || offset >= data.length - 4) continue;

            try {
                ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                bbuf.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
                int objSize = r.readModularShort();
                if (objSize <= 0 || objSize > 0x4000) continue;
                int type = r.readBitShort();
                typeCount.merge(type, 1, Integer::sum);
                typeHandles.computeIfAbsent(type, k -> new ArrayList<>()).add(h);
            } catch (Exception ignored) {}
        }

        // 按类型数排序输出
        typeCount.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .forEach(e -> System.out.printf("  type=0x%02X (%d) : %d 个%n",
                e.getKey(), e.getKey(), e.getValue()));

        // 检查 block_header (0x30) 和 block (0x31) 实际数据
        for (int code : new int[]{0x30, 0x31}) {
            List<Long> hs = typeHandles.get(code);
            if (hs == null || hs.isEmpty()) continue;

            System.out.println("\n=== type=0x" + Integer.toHexString(code) + " 原始数据样例 ===");
            long first = hs.get(0);
            long offset = registry.offsetFor(first).orElse(-1L);
            System.out.println("handle=0x" + Long.toHexString(first) + " @offset=" + offset);

            if (offset > 0 && offset < data.length - 8) {
                int size = (int)Math.min(128, data.length - offset);
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < size; i++) {
                    hex.append(String.format("%02X ", data[(int)offset + i] & 0xFF));
                    if ((i + 1) % 16 == 0) hex.append("\n  ");
                }
                System.out.println("  " + hex.toString());

                // Try simple byte-aligned read:
                // MS size: first byte high-nibble flag
                int firstB = data[(int)offset] & 0xFF;
                boolean msExt = (firstB & 0x80) != 0;
                int objSize2 = firstB & 0x7F;
                int typeIdx = 1;
                if (msExt) {
                    objSize2 = (objSize2 << 8) | (data[(int)offset + 1] & 0xFF);
                    typeIdx = 2;
                }
                System.out.println("  byte-ML obj_size: " + objSize2 + " (msExt=" + msExt + ")");
                int typeBS = (data[(int)offset + typeIdx] & 0xFF) | ((data[(int)offset + typeIdx + 1] & 0xFF) << 8);
                System.out.println("  byte-LE16 type_code: 0x" + Integer.toHexString(typeBS));

                // R2000 uses BS for type_code (bit-short with opcode)
                // BS encoding: 2 bits opcode + rest
                // opcode 00: read 12 bits signed
                // opcode 01: read 4 bits unsigned + 16 bits = 20 bits total... actually varies
                // Let me try bit-level properly

                ByteBufferBitInput bbuf = new ByteBufferBitInput(data);
                bbuf.seek(offset * 8L);
                BitStreamReader r = new BitStreamReader(bbuf, DwgVersion.R2000);
                int mSize = r.readModularShort();
                System.out.println("  MS obj_size: " + mSize);
                int tCode = r.readBitShort();
                System.out.println("  BS type_code: 0x" + Integer.toHexString(tCode) + " (" + tCode + ")");
                System.out.println("  bit pos after type: " + bbuf.position());

                // Now try to understand: is the rest byte-aligned or bit-packed?
                // Try as byte-aligned:
                int bitPos = (int)bbuf.position();
                int bytePos = bitPos / 8;
                int extraBits = bitPos % 8;
                System.out.println("  到 type_code 后 byte pos=" + bytePos + ", 剩余 bits=" + extraBits);

                // Print next 32 bytes
                StringBuilder nextHex = new StringBuilder();
                for (int i = 0; i < 32 && bytePos + i < data.length; i++) {
                    nextHex.append(String.format("%02X ", data[bytePos + i] & 0xFF));
                }
                System.out.println("  next bytes: " + nextHex.toString());

                // Try reading BS length + text directly
                try {
                    int nameLen = r.readBitShort();
                    System.out.println("  (bit) nameLen (BS): " + nameLen);
                    if (nameLen > 0 && nameLen < 200) {
                        StringBuilder nameSb = new StringBuilder();
                        long cur = bbuf.position();
                        for (int i = 0; i < nameLen; i++) {
                            int b = (int)((cur + i * 8 < (long)data.length * 8L) ?
                                data[(int)((cur + i * 8) / 8)] & 0xFF : 0);
                            nameSb.append((b >= 32 && b < 127) ? (char)b : '?');
                        }
                        System.out.println("  (raw) text bytes: '" + nameSb + "'");
                        bbuf.seek(cur + nameLen * 8L);
                    }
                } catch (Exception ex) {
                    System.out.println("  bit read failed: " + ex.getMessage());
                }
            }
        }
    }
}
